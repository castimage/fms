//! jsonl访问日志，stdout + 文件双写，`mw_access_log`为`Router`次外层全局layer
//! 压缩层之下，后加者为外层，压缩关闭时最外层，包裹cors与安全链，
//! next完成后统一施加安全响应头
//! 唯一一次，覆盖4xx/5xx并剥离客户端`X-FMS-*`头，组装9字段日志行，
//! cors预检虽在cors层短路，但请求先经本层，次外层，预检同样记录访问日志
//!
//! 降噪架构，请求路径零阻塞，`mw_access_log`只做确定性采样(`AtomicU64`序号
//! % `sample_rate` == 0，非随机数)+ 有界通道`try_send`，满则丢弃计数，绝不阻塞，
//! 后台sink任务按`batch_size` / `batch_interval_ms`攒批，一次`write_all` + 一次
//! flush批量双写stdout + 文件，非每请求flush。pgmq投递同样经http中转，
//! 网关不直连pg/pgmq，批量POST后端`/internal/log-batch`
//! 该路径豁免网关签名但需内部token校验。日志可丢，请求路径绝不受影响，
//! sink随进程退出，无需优雅drain(注释见`spawn_log_sink`)。
//!
//! 调用关系，main组装`AppState`时经`build_state`构造logger，`serve_with_shutdown`
//! spawn sink后台任务，`mw_access_log`为全局中间件
#![forbid(unsafe_code)]

use crate::config::{LogCfg, LogPgmqCfg};
use crate::headers::{apply_security_headers, inject_trace, strip_client_fms_headers};
use crate::state::AppState;
use crate::types::{LogUserSlot, TraceCtx, TraceSampled, extract_client_ip, gen_trace_id, now_secs, trace_sampled};
use axum::body::Body;
use axum::extract::State;
use axum::http::header::USER_AGENT;
use axum::http::{HeaderValue, Request, Response, Uri};
use thiserror::Error;
use axum::middleware::Next;
use chrono::{SecondsFormat, Utc};
use http_body_util::BodyExt;
use hyper_util::client::legacy::Client;
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::rt::TokioExecutor;
use serde::Serialize;
use std::fs::{File, OpenOptions};
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use uuid::Uuid;

/// 访问日志条目，jsonl一行一对象
#[derive(Debug, Clone, Serialize)]
pub struct AccessLogEntry {
    /// RFC3339 UTC，毫秒精度
    pub ts: String,
    /// = `trace_id`
    pub req_id: String,
    pub method: String,
    /// 原始请求路径(含query字符串，长度截断`cfg.log.path_max_len`，默认1024)
    pub path: String,
    pub status: u16,
    /// started 到响应完成的毫秒数
    pub latency_ms: f64,
    pub ip: Option<String>,
    /// `claims.sub`，匿名请求为null
    pub user: Option<String>,
    /// `User-Agent`截断`cfg.log.ua_max_len`，默认256
    pub ua: Option<String>,
}

/// 日志错误
#[derive(Debug, Error)]
pub enum LogError {
    #[error("日志写入失败：{0}")]
    Io(#[from] std::io::Error),
}

/// 访问日志器，请求路径只做 采样判定 + 有界通道`try_send`，非阻塞，满则丢弃计数，
/// 双写，stdout + 可选文件与pgmq http投递全部移入后台sink任务批量执行。
///
/// 字段说明，file = 追加模式`BufWriter` + `Mutex`(运行期仅sink任务独占，`write`
/// 保留供测试/调试同步写入)，rx = 有界通道接收端，仅启动期由`spawn_log_sink`
/// 取走一次，seq = 确定性采样序号，`dropped_logs` = 通道满丢弃累计计数，
/// `last_warn_secs` = 丢弃WARN防刷屏，每秒至多一次，
/// `pgmq_client` = pgmq投递客户端(`new`一次构造、跨批复用，keep-alive保活)
pub struct AccessLogger {
    file: Mutex<Option<BufWriter<File>>>,
    tx: mpsc::Sender<AccessLogEntry>,
    rx: Mutex<Option<mpsc::Receiver<AccessLogEntry>>>,
    seq: AtomicU64,
    dropped_logs: AtomicUsize,
    last_warn_secs: AtomicU64,
    sample_rate: u32,
    batch_size: usize,
    batch_interval_ms: u64,
    /// path字段截断长度(`cfg.log.path_max_len`，默认1024)
    path_max_len: usize,
    /// ua字段截断长度(`cfg.log.ua_max_len`，默认256)
    ua_max_len: usize,
    pgmq: LogPgmqCfg,
    /// 后端`base_url`，pgmq http中转目标 = 网关唯一后端
    backend_base_url: String,
    /// pgmq投递http客户端，`new`一次性构造、跨批keep-alive复用同一连接
    /// (每批新建客户端会反复重建连接池对象、每批对后端新建tcp连接，与代理侧
    /// `UpstreamClient`的复用方式不一致)
    pgmq_client: Client<HttpConnector, Body>,
}

impl AccessLogger {
    /// 构造，`access_file`(来自`cfg.log.access_file`)为`None` 则仅 stdout，为`Some` 则
    /// 自动创建父目录并以追加模式打开。有界通道容量取`cfg.log.channel_capacity`
    /// 下限1防御。`pgmq.endpoint` / `backend_base_url`组合为投递url。
    pub fn new(cfg: &LogCfg, backend_base_url: &str) -> Result<Self, LogError> {
        let file = match cfg.access_file.as_deref() {
            Some(p) => {
                let p = PathBuf::from(p);
                if let Some(parent) = p.parent()
                    && !parent.as_os_str().is_empty()
                {
                    std::fs::create_dir_all(parent).map_err(LogError::Io)?;
                }
                let f = OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&p)
                    .map_err(LogError::Io)?;
                Some(BufWriter::new(f))
            }
            None => None,
        };

        let cap = cfg.channel_capacity.max(1);
        let (tx, rx) = mpsc::channel(cap);

        // pgmq投递客户端，一次性构造、跨批复用(hyper-util legacy，同proxy.rs用法，
        // 与业务代理保持独立互不干扰，但连接在本客户端内跨批keep-alive复用)
        let pgmq_client = Client::builder(TokioExecutor::new()).build(HttpConnector::new());

        Ok(Self {
            file: Mutex::new(file),
            tx,
            rx: Mutex::new(Some(rx)),
            seq: AtomicU64::new(0),
            dropped_logs: AtomicUsize::new(0),
            last_warn_secs: AtomicU64::new(0),
            sample_rate: cfg.sample_rate.max(1),
            batch_size: cfg.batch_size.max(1),
            batch_interval_ms: cfg.batch_interval_ms.max(1),
            path_max_len: cfg.path_max_len.max(1),
            ua_max_len: cfg.ua_max_len.max(1),
            pgmq: cfg.pgmq.clone(),
            backend_base_url: backend_base_url.trim_end_matches('/').to_string(),
            pgmq_client,
        })
    }

    /// 请求路径提交入口，非阻塞，绝不阻塞/失败请求路径，
    ///确定性采样(rate == 1全量，否则`AtomicU64`序号自增，seq % rate == 0才记录，

    ///    非随机数，审计可复现)，
    ///有界通道`try_send`，满则丢弃本条目并计数`dropped_logs`，WARN每秒至多一次

    ///    防刷屏，通道已关闭，进程退出则静默丢弃。
    pub fn submit(&self, entry: AccessLogEntry) {
        // 采样判定，确定性序号，非随机，rate=1全量保审计
        if self.sample_rate > 1
            && !self
                .seq
                .fetch_add(1, Ordering::Relaxed)
                .is_multiple_of(self.sample_rate as u64)
        {
            return;
        }
        match self.tx.try_send(entry) {
            Ok(()) => {}
            Err(mpsc::error::TrySendError::Full(_)) => {
                // 通道满，丢弃本条目并计数，日志可丢，请求路径绝不受影响
                let dropped = self.dropped_logs.fetch_add(1, Ordering::Relaxed) + 1;
                let now = now_secs() as u64;
                let last = self.last_warn_secs.load(Ordering::Relaxed);
                if last != now
                    && self
                        .last_warn_secs
                        .compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed)
                        .is_ok()
                {
                    tracing::warn!(dropped, "访问日志通道满，条目被丢弃(每秒至多提示一次)");
                }
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                // 通道已关闭，进程退出路径，静默丢弃
            }
        }
    }

    /// 同步单条双写，测试/调试用，生产路径走 submit，后台 sink 批量 flush
    pub fn write(&self, entry: &AccessLogEntry) -> Result<(), LogError> {
        self.flush_batch(std::slice::from_ref(entry))
    }

    /// 批量双写，sink任务调用，整批拼接一次`write_all` + 一次flush(非每请求
    /// flush，单次flush即静态小文件场景的qps瓶颈)，stdout失败仅WARN，
    /// 文件失败返回`Err`，调用方WARN，绝不panic
    fn flush_batch(&self, entries: &[AccessLogEntry]) -> Result<(), LogError> {
        if entries.is_empty() {
            return Ok(());
        }

        // 整批预拼接一次，stdout与文件共用，避免逐条重复拼接
        let mut buf = String::with_capacity(entries.len() * 128);
        for e in entries {
            buf.push_str(&Self::entry_to_json(e));
            buf.push('\n');
        }

        // stdout双写，不可失败，失败仅WARN不panic
        let stdout = std::io::stdout();
        let mut lock = stdout.lock();
        if let Err(e) = lock.write_all(buf.as_bytes()) {
            tracing::warn!(error = %e, "stdout 访问日志批量写入失败");
        }
        let _ = lock.flush();

        // 文件双写(失败返回Err，由调用方WARN)
        if let Ok(mut guard) = self.file.lock()
            && let Some(w) = guard.as_mut()
        {
            w.write_all(buf.as_bytes()).map_err(LogError::Io)?;
            w.flush().map_err(LogError::Io)?;
        }
        Ok(())
    }

    /// 批量flush + 可选pgmq投递，sink任务调用
    async fn flush_batch_and_deliver(&self, entries: &[AccessLogEntry]) {
        if let Err(e) = self.flush_batch(entries) {
            tracing::warn!(error = %e, "访问日志批量写入失败");
        }

        // pgmq投递，http中转，enabled && token非空才投递，失败仅WARN不重试
        // 断电容灾，丢弃本批并计数，日志可丢，请求路径绝不受影响
        if self.pgmq.enabled && !self.pgmq.token.is_empty() {
            self.deliver_pgmq(entries).await;
        }
    }

    /// pgmq http中转投递，批量POST后端{`base_url`}{endpoint}(`/internal/log-batch`，
    /// 豁免网关签名但需`X-FMS-Int-Token`内部token校验)后送后端批量
    /// `pgmq.send`(`q_log_access`)，后端链路已存在。body为json数组
    /// [{ts,req_id,method,path,status,latency_ms,ip,user,ua}](serde序列化
    /// `Vec`<`AccessLogEntry`>，字段与jsonl行一致)。超时取`cfg.log.pgmq.timeout_secs`
    /// 默认3s，弱网/大batch场景可调，失败WARN不重试。
    async fn deliver_pgmq(&self, entries: &[AccessLogEntry]) {
        // json数组序列化，该结构体序列化不可失败，防御性兜底
        let body = match serde_json::to_string(entries) {
            Ok(b) => b,
            Err(e) => {
                tracing::warn!(error = %e, "PGMQ 日志序列化失败，本批丢弃");
                return;
            }
        };

        // 内部token校验头，防御，配置异常则跳过本批，不打扰请求路径
        let token_hv = match HeaderValue::from_str(&self.pgmq.token) {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(error = %e, "PGMQ 内部 token 非法(X-FMS-Int-Token 头构造失败)，本批丢弃");
                return;
            }
        };
        let url = format!("{}{}", self.backend_base_url, self.pgmq.endpoint);
        let uri: Uri = match url.parse() {
            Ok(u) => u,
            Err(e) => {
                tracing::warn!(error = %e, url = %url, "PGMQ 投递 URL 非法，本批丢弃");
                return;
            }
        };

        // 复用new构造的投递客户端，跨批keep-alive复用同一tcp连接，
        // 避免连接池对象反复重建与跨批无连接复用
        let client = &self.pgmq_client;
        let req = Request::builder()
            .method("POST")
            .uri(uri)
            .header("content-type", "application/json")
            .header("x-fms-int-token", token_hv)
            .body(Body::from(body));
        let req = match req {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!(error = %e, "PGMQ 投递请求构造失败，本批丢弃");
                return;
            }
        };

        // 超时取配置(tokio::time::timeout包裹 发送 + 响应体收完，驱动连接可复用)，
        // 下限1s防御配置异常，0值会立即超时丢弃全部批次
        let timeout = Duration::from_secs(self.pgmq.timeout_secs.max(1));
        let send = async {
            let resp = match client.request(req).await {
                Ok(r) => r,
                Err(e) => {
                    tracing::warn!(error = %e, "PGMQ 日志投递失败，本批丢弃(日志可丢，请求路径不受影响)");
                    return None;
                }
            };
            let status = resp.status();
            let (_, body) = resp.into_parts();
            if let Err(e) = body.collect().await {
                tracing::warn!(error = %e, "PGMQ 投递响应体读取失败");
            }
            Some(status)
        };
        match tokio::time::timeout(timeout, send).await {
            Err(_) => {
                tracing::warn!(timeout_secs = self.pgmq.timeout_secs, "PGMQ 日志投递超时，本批丢弃(日志可丢，请求路径不受影响)");
            }
            Ok(Some(status)) if !status.is_success() => {
                tracing::warn!(status = %status.as_u16(), "PGMQ 日志投递被后端拒绝，本批丢弃");
            }
            _ => {}
        }
    }

    /// `serde_json::to_string`，供测试/调试复用，该结构体序列化不可失败
    pub fn entry_to_json(entry: &AccessLogEntry) -> String {
        serde_json::to_string(entry).unwrap_or_else(|_| "{}".to_string())
    }
}

/// 后台日志sink任务，从有界通道收条目攒批(`batch_size`或`batch_interval_ms`超时
/// 触发flush后批量双写 stdout + 文件，可选 pgmq http 投递。
///
/// 生命周期，由`serve_with_shutdown`在runtime内spawn(`build_state`为同步函数，
/// 不在runtime上下文，不可spawn)，通道发送端(`logger.tx`随`AppState`存活)随
/// 进程退出而关闭则本任务收 `None` 后 flush 残余即退出。无需优雅drain，日志可丢，
/// 请求路径绝不受影响，与优雅关闭衔接由进程退出兜底。
///
/// `logger`，已构造的`AccessLogger`，取走内部rx，仅可调用一次
pub fn spawn_log_sink(logger: Arc<AccessLogger>) {
    // 取走接收端，仅启动期一次，测试场景不spawn时rx保留在原处，通道不关闭
    let rx = match logger.rx.lock().ok().and_then(|mut g| g.take()) {
        Some(rx) => rx,
        None => {
            tracing::warn!("访问日志 sink 已启动或接收端缺失，跳过");
            return;
        }
    };
    tokio::spawn(async move { logger.sink_loop(rx).await });
}

impl AccessLogger {
    /// sink主循环，select攒批，收满`batch_size`立即flush，否则
    /// `batch_interval_ms`超时触发flush，不足批也按时落盘，保实时性。
    /// 通道关闭，进程退出则 flush 残余后退出。
    async fn sink_loop(&self, mut rx: mpsc::Receiver<AccessLogEntry>) {
        let batch_size = self.batch_size;
        let interval = Duration::from_millis(self.batch_interval_ms);
        let mut batch: Vec<AccessLogEntry> = Vec::with_capacity(batch_size.min(1024));
        let timer = tokio::time::sleep(interval);
        tokio::pin!(timer);
        loop {
            tokio::select! {
                maybe = rx.recv() => match maybe {
                    Some(entry) => {
                        batch.push(entry);
                        if batch.len() >= batch_size {
                            // 收满一批，flush后重置计时器，新一批从零起算
                            self.flush_batch_and_deliver(&batch).await;
                            batch.clear();
                            timer.as_mut().reset(tokio::time::Instant::now() + interval);
                        }
                    }
                    None => {
                        // 全部发送端关闭，进程退出，flush残余后退出，无需优雅drain
                        if !batch.is_empty() {
                            self.flush_batch_and_deliver(&batch).await;
                        }
                        return;
                    }
                },
                _ = timer.as_mut() => {
                    // 攒批超时，不足batch_size也落盘，保实时性，重置计时器
                    if !batch.is_empty() {
                        self.flush_batch_and_deliver(&batch).await;
                        batch.clear();
                    }
                    timer.as_mut().reset(tokio::time::Instant::now() + interval);
                }
            }
        }
    }
}

/// 字段长度截断，防日志注入
fn truncate(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

/// 日志路径组装，任意路径下对query中token= 参数统一脱敏(防ws握手jwt明文落盘，
/// 日志文件可能被采集/外传/轮转归档，与路径精确匹配解耦，变体路径同样脱敏)，
/// token值长度 > 8即掩码，短值保留，jwt恒 >8，路径截断长度取配置，默认1024
fn log_path(uri: &axum::http::Uri, max_path_len: usize) -> String {
    let p = uri.path();
    match uri.query() {
        Some(q) => truncate(&format!("{p}?{}", mask_token_param(q)), max_path_len),
        None => truncate(p, max_path_len),
    }
}

/// 对query中的token= 参数值统一脱敏，token=<值>，值长度 > 8 则回 token=***
/// 键大小写不敏感，其余参数原样保留，无token参数则原样返回。
fn mask_token_param(query: &str) -> String {
    query
        .split('&')
        .map(|pair| match pair.split_once('=') {
            Some((k, v)) if k.eq_ignore_ascii_case("token") && v.len() > 8 => {
                format!("{k}=***")
            }
            _ => pair.to_string(),
        })
        .collect::<Vec<_>>()
        .join("&")
}

/// 中间件(`Router`次外层全局layer，包裹全部路由)，剥离客户端`X-FMS-*`头(防伪造第一道闸，
/// 含`/healthz`与静态)在 next 完成后统一施加安全响应头 + 响应侧`X-Trace-Id`补齐，随后
/// 组装`AccessLogEntry`双写，失败仅WARN。
///
/// 执行序，收集请求侧信息(trace采样判定先行，`trace.sample_rate`百分比语义，与
/// `log.sample_rate`独立，判定写入extensions.`TraceSampled`供内层`mw_trace`复用，同一
/// 请求前后一致。采样命中，`trace_id`单一来源优先`TraceCtx`、其次请求头合法uuid透传、
/// 否则生成，写回请求头保证响应侧/日志`req_id`/上游三处一致，未采样，不透传不生成，
/// `req_id`兜底空串、响应侧不注入`X-Trace-Id`。ua/path截断长度取`cfg.log.ua_max_len` /
/// `path_max_len`，path经`log_path`组装，query中token= 长值统一掩码防ws握手jwt
/// 明文落盘，任意路径变体均脱敏)后注入 `LogUserSlot` 用户槽(本层先于`mw_auth`
/// 执行，验签后user经槽回填，随后剥离客户端`X-FMS-*`(每个请求含`/healthz`与静态)，再
/// next执行安全链后统一施加安全响应头，唯一一次施加点，覆盖4xx/5xx，tls下含hsts+
/// 响应侧`X-Trace-Id`，采样命中时则组装9字段 jsonl 双写(stdout + 可选文件，
/// 失败仅WARN不panic)。
///
/// 字段语义，`req_id` = `trace_id`，未采样为空串，user = 验签通过的`claims.sub`(匿名/
/// api-key/未认证为`None`)，`latency_ms` = started 至响应完成，ip取`ConnectInfo`
/// (不可伪造，无则`None`，不回退请求头)。
/// cors预检在cors层短路，不进入安全链，但本层为次外层包裹，压缩关闭时最外层，预检同样记录访问日志。
///
/// `state`，全局状态，tls配置决定hsts是否施加，logger双写目标
pub async fn mw_access_log(
    State(state): State<Arc<AppState>>,
    mut req: Request<Body>,
    next: Next,
) -> Response<Body> {
    // 请求侧信息，next消费req前收集
    // trace采样判定(trace.sample_rate百分比语义，与log.sample_rate独立)，本层为全局
    // 最外层最先执行，判定写入extensions供mw_trace复用，同一请求前后判定一致，
    // 未采样请求req_id为空、不透传/不注入traceId，降噪
    let sampled = trace_sampled(state.cfg.trace.sample_rate);
    req.extensions_mut().insert(TraceSampled(sampled));
    // trace_id单一来源，优先TraceCtx，其次请求头合法uuid透传(与mw_trace同规则)，否则生成，
    // 写回请求头，保证响应侧 / 日志req_id / 上游三处一致
    let trace_id = if sampled {
        req.extensions()
            .get::<TraceCtx>()
            .map(|t| t.trace_id.clone())
            .or_else(|| {
                req.headers()
                    .get("x-trace-id")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|s| Uuid::parse_str(s).ok())
                    .map(|u| u.to_string())
            })
            .unwrap_or_else(gen_trace_id)
    } else {
        String::new()
    };
    if sampled
        && let Ok(hv) = axum::http::HeaderValue::from_str(&trace_id)
    {
        req.headers_mut().insert("x-trace-id", hv);
    }

    let started = req
        .extensions()
        .get::<TraceCtx>()
        .map(|t| t.started)
        .unwrap_or_else(Instant::now);
    let ip = extract_client_ip(&req);
    let ua = req
        .headers()
        .get(USER_AGENT)
        .and_then(|v| v.to_str().ok())
        .map(|s| truncate(s, state.logger.ua_max_len));
    let method = req.method().as_str().to_string();
    // ws握手token脱敏见log_path，防jwt落盘泄露，截断长度取配置
    let path = log_path(req.uri(), state.logger.path_max_len);

    // 用户槽，本层先于mw_auth执行，验签后user经槽回填(见types::LogUserSlot)
    let user_slot = LogUserSlot::default();
    req.extensions_mut().insert(user_slot.clone());

    // 防伪造，每个请求(含/healthz与静态)先剥离客户端X-FMS-*头
    strip_client_fms_headers(req.headers_mut());

    // 执行安全链/处理器
    let resp = next.run(req).await;
    let mut resp = resp;

    // 统一安全响应头，唯一一次施加点+ 响应侧X-Trace-Id
    let is_tls = state.cfg.tls.cert.is_some() && state.cfg.tls.key.is_some();
    apply_security_headers(&mut resp, is_tls);
    // 未采样请求不注入响应侧X-Trace-Id，traceId为空串时不写空头
    if !trace_id.is_empty() {
        inject_trace(resp.headers_mut(), &trace_id);
    }

    // 访问日志(user = 验签通过的claims.sub，来自槽回填，未认证/匿名路径为None)
    let status = resp.status().as_u16();
    let user = user_slot.0.lock().ok().and_then(|g| g.clone());
    let latency_ms = started.elapsed().as_secs_f64() * 1000.0;
    let entry = AccessLogEntry {
        ts: Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true),
        req_id: trace_id,
        method,
        path,
        status,
        latency_ms,
        ip,
        user,
        ua,
    };

    // 提交，确定性采样 + 有界通道try_send，非阻塞，满则丢弃计数，请求路径零阻塞
    state.logger.submit(entry);
    resp
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::state::test_state_with;
    use tower::ServiceExt;

    #[test]
    fn entry_to_json_contains_all_fields() {
        let entry = AccessLogEntry {
            ts: "2026-08-09T00:00:00.000Z".to_string(),
            req_id: "uuid-1".to_string(),
            method: "POST".to_string(),
            path: "/api/gl/vouchers?page=1".to_string(),
            status: 200,
            latency_ms: 1.5,
            ip: Some("1.2.3.4".to_string()),
            user: Some("user-1".to_string()),
            ua: Some("curl/8.0".to_string()),
        };
        let json = AccessLogger::entry_to_json(&entry);
        let v: serde_json::Value = serde_json::from_str(&json).expect("合法 JSON");
        assert_eq!(v["method"], "POST");
        assert_eq!(v["status"], 200);
        assert_eq!(v["req_id"], "uuid-1");
        assert_eq!(v["ip"], "1.2.3.4");
        assert_eq!(v["ua"], "curl/8.0");
    }

    #[test]
    fn truncate_limits_length() {
        assert_eq!(truncate("abcde", 3), "abc");
        assert_eq!(truncate("abc", 5), "abc");
        assert_eq!(truncate("中文路径", 2), "中文");
    }

    #[test]
    fn log_path_masks_ws_token_and_truncates() {
        let uri: axum::http::Uri = "/api/nt/ws?token=eyJhbGciOiJIUzI1NiJ9.signature"
            .parse()
            .unwrap();
        assert_eq!(log_path(&uri, 1024), "/api/nt/ws?token=***", "WS token 必须脱敏");
        let uri: axum::http::Uri = "/api/nt/ws".parse().unwrap();
        assert_eq!(log_path(&uri, 1024), "/api/nt/ws", "无 query 原样记录");
        let uri: axum::http::Uri = "/api/gl/vouchers?page=1&size=20".parse().unwrap();
        assert_eq!(
            log_path(&uri, 1024),
            "/api/gl/vouchers?page=1&size=20",
            "非 WS 路径保留 query"
        );
        // 脱敏与路径规范解耦，任意路径的token= 长值统一掩码
        let uri: axum::http::Uri = "/api/x?token=eyJhbGciOiJIUzI1NiJ9.sig".parse().unwrap();
        assert_eq!(
            log_path(&uri, 1024),
            "/api/x?token=***",
            "非 WS 路径的长 token 同样脱敏"
        );
        let uri: axum::http::Uri = "//api/nt/ws?token=eyJhbGciOiJIUzI1NiJ9.sig"
            .parse()
            .unwrap();
        assert_eq!(
            log_path(&uri, 1024),
            "//api/nt/ws?token=***",
            "双斜杠变体必须脱敏(F3)"
        );
        let uri: axum::http::Uri = "/API/NT/WS?token=eyJhbGciOiJIUzI1NiJ9.sig".parse().unwrap();
        assert_eq!(
            log_path(&uri, 1024),
            "/API/NT/WS?token=***",
            "大小写变体必须脱敏(F3)"
        );
        // 短值，≤8字符视为非敏感参数保留，键大小写不敏感
        let uri: axum::http::Uri = "/api/x?token=abc".parse().unwrap();
        assert_eq!(log_path(&uri, 1024), "/api/x?token=abc", "短 token 值不掩码");
        let uri: axum::http::Uri = "/api/x?Token=eyJhbGciOiJIUzI1NiJ9.sig&page=1"
            .parse()
            .unwrap();
        assert_eq!(
            log_path(&uri, 1024),
            "/api/x?Token=***&page=1",
            "大小写键 + 多参数保留其余"
        );
        // 超长路径截断到配置上限，默认1024
        let long = format!("/{}", "a".repeat(2000));
        let uri: axum::http::Uri = long.parse().unwrap();
        assert_eq!(log_path(&uri, 1024).chars().count(), 1024);
        assert_eq!(log_path(&uri, 512).chars().count(), 512, "截断长度可配置");
    }

    async fn echo_ok() -> &'static str {
        "ok"
    }

    #[tokio::test]
    async fn mw_access_log_applies_security_headers_and_strips_fms() {
        let mut cfg = Config::default();
        cfg.auth.jwt_secret = "test-jwt-secret".to_string();
        cfg.sign.sign_secret = "test-sign-secret".to_string();
        cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
        cfg.trace.sample_rate = 100; // 本用例断言响应必带X-Trace-Id，须全量采样
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .route("/x", axum::routing::get(echo_ok))
            .route_layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_access_log,
            ))
            .with_state(state);
        let req = axum::http::Request::builder()
            .uri("/x")
            .header("x-fms-userid", "attacker-fake")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.expect("oneshot");
        // 统一安全响应头，明文则无 hsts，无 tls 配置
        assert!(resp.headers().contains_key("content-security-policy"));
        assert_eq!(
            resp.headers()
                .get("x-frame-options")
                .and_then(|v| v.to_str().ok()),
            Some("SAMEORIGIN")
        );
        assert!(!resp.headers().contains_key("strict-transport-security"));
        // 响应侧X-Trace-Id补齐(无TraceCtx时生成)
        let trace = resp
            .headers()
            .get("x-trace-id")
            .and_then(|v| v.to_str().ok());
        assert!(
            trace.is_some_and(|s| !s.is_empty()),
            "响应必须带 X-Trace-Id"
        );
    }

    #[tokio::test]
    async fn mw_access_log_trace_sampling_skips_response_trace_header() {
        // trace采样接线验证，rate=0，禁用则全部响应不带 X-Trace-Id，rate=100，全量
        // 则全部响应带。精确百分比已在types::trace_sampled_at纯函数单测精确断言，
        // 全局采样序号被并发测试交错消费，集成层只验证开关语义，确定性，不依赖序号
        for (rate, expect_traced) in [(0u32, 0usize), (100u32, 20usize)] {
            let mut cfg = Config::default();
            cfg.auth.jwt_secret = "test-jwt-secret".to_string();
            cfg.sign.sign_secret = "test-sign-secret".to_string();
            cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
            cfg.trace.sample_rate = rate;
            let state = test_state_with(cfg);
            let app = axum::Router::new()
                .route("/x", axum::routing::get(echo_ok))
                .route_layer(axum::middleware::from_fn_with_state(
                    state.clone(),
                    mw_access_log,
                ))
                .with_state(state);
            let mut traced = 0;
            for _ in 0..20 {
                let req = axum::http::Request::builder()
                    .uri("/x")
                    .body(Body::empty())
                    .unwrap();
                let resp = app.clone().oneshot(req).await.expect("oneshot");
                if resp
                    .headers()
                    .get("x-trace-id")
                    .is_some_and(|v| !v.as_bytes().is_empty())
                {
                    traced += 1;
                }
            }
            assert_eq!(
                traced, expect_traced,
                "rate={rate}：20 个请求应 {expect_traced} 个响应带 X-Trace-Id"
            );
        }
    }

    #[test]
    fn logger_writes_to_file() {
        let dir = std::env::temp_dir();
        let path = dir.join(format!("fms_rgw-log-test-{}.jsonl", std::process::id()));
        let cfg = LogCfg {
            access_file: Some(path.to_string_lossy().into_owned()),
            ..LogCfg::default()
        };
        let logger = AccessLogger::new(&cfg, "http://127.0.0.1:8081").expect("日志文件打开");
        let entry = test_entry(0);
        logger.write(&entry).expect("写入成功");
        let content = std::fs::read_to_string(&path).expect("读取日志文件");
        let _ = std::fs::remove_file(&path);
        assert!(content.contains("\"req_id\":\"r\""), "文件双写应含日志行");
        assert!(content.ends_with('\n'));
    }

    /// 测试用日志条目(`req_id`固定 "r"，便于断言)
    fn test_entry(i: u64) -> AccessLogEntry {
        AccessLogEntry {
            ts: format!("2026-08-09T00:00:00.{i:03}Z"),
            req_id: "r".to_string(),
            method: "GET".to_string(),
            path: "/healthz".to_string(),
            status: 200,
            latency_ms: 0.1,
            ip: None,
            user: None,
            ua: None,
        }
    }

    /// 取走接收端(测试内drain通道断言，生产路径由`spawn_log_sink`取走)
    fn take_rx(logger: &AccessLogger) -> mpsc::Receiver<AccessLogEntry> {
        logger.rx.lock().expect("rx 锁").take().expect("rx 未被取走")
    }

    #[test]
    fn submit_sample_rate_1_records_all() {
        // rate=1全量保审计，10条全部入通道
        let logger = AccessLogger::new(&LogCfg::default(), "http://127.0.0.1:8081").expect("构造");
        let mut rx = take_rx(&logger);
        for i in 0..10 {
            logger.submit(test_entry(i));
        }
        let mut got = 0;
        while rx.try_recv().is_ok() {
            got += 1;
        }
        assert_eq!(got, 10, "rate=1 必须全量记录");
        assert_eq!(logger.dropped_logs.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn submit_sample_rate_10_records_about_1_in_10() {
        // rate=10确定性采样，序号0,10,20,... 记录，seq % 10 == 0，非随机可复现
        let cfg = LogCfg {
            sample_rate: 10,
            ..LogCfg::default()
        };
        let logger = AccessLogger::new(&cfg, "http://127.0.0.1:8081").expect("构造");
        let mut rx = take_rx(&logger);
        for i in 0..100 {
            logger.submit(test_entry(i));
        }
        let mut got = 0;
        while let Ok(e) = rx.try_recv() {
            assert_eq!(e.req_id, "r");
            got += 1;
        }
        assert_eq!(got, 10, "100 条 × rate=10 应恰为 10 条");
        // 第二个100条周期，序号100,110,... 继续命中，序号跨周期连续，无重置
        for i in 100..200 {
            logger.submit(test_entry(i));
        }
        let mut got2 = 0;
        while rx.try_recv().is_ok() {
            got2 += 1;
        }
        assert_eq!(got2, 10, "跨周期序号连续，仍为 1/10");
    }

    #[test]
    fn submit_drops_when_channel_full_and_counts() {
        // 通道满，容量2，无人消费则丢弃并计数，请求路径不阻塞不报错
        let cfg = LogCfg {
            channel_capacity: 2,
            ..LogCfg::default()
        };
        let logger = AccessLogger::new(&cfg, "http://127.0.0.1:8081").expect("构造");
        // 不取走rx，通道无人消费，容量满后全部丢弃
        for i in 0..10 {
            logger.submit(test_entry(i));
        }
        let dropped = logger.dropped_logs.load(Ordering::Relaxed);
        assert!(dropped >= 8, "容量 2 提交 10 条至少丢弃 8 条，实际 {dropped}");
    }

    #[test]
    fn entries_serialize_as_json_array_for_pgmq() {
        // pgmq投递body格式，json数组[{ts,req_id,method,path,status,latency_ms,ip,user,ua}]
        let entries = vec![
            AccessLogEntry {
                ts: "2026-08-09T00:00:00.000Z".to_string(),
                req_id: "uuid-1".to_string(),
                method: "POST".to_string(),
                path: "/api/gl/vouchers?page=1".to_string(),
                status: 200,
                latency_ms: 1.5,
                ip: Some("1.2.3.4".to_string()),
                user: Some("user-1".to_string()),
                ua: Some("curl/8.0".to_string()),
            },
            test_entry(2),
        ];
        let json = serde_json::to_string(&entries).expect("数组序列化不可失败");
        let v: serde_json::Value = serde_json::from_str(&json).expect("合法 JSON");
        assert!(v.is_array(), "PGMQ 投递 body 必须是 JSON 数组");
        assert_eq!(v[0]["method"], "POST");
        assert_eq!(v[0]["status"], 200);
        assert_eq!(v[0]["req_id"], "uuid-1");
        assert_eq!(v[1]["path"], "/healthz");
        // 字段名与后端契约一致，9字段全量
        let obj = v[0].as_object().expect("数组元素为对象");
        for key in ["ts", "req_id", "method", "path", "status", "latency_ms", "ip", "user", "ua"] {
            assert!(obj.contains_key(key), "缺少字段 {key}");
        }
    }

    #[tokio::test]
    async fn sink_loop_flushes_batch_on_interval() {
        // 批量间隔触发，条目不足batch_size也按时flush，保实时性
        let cfg = LogCfg {
            batch_interval_ms: 30,
            batch_size: 100,
            access_file: Some(format!(
                "{}/fms_rgw-sink-test-{}.jsonl",
                std::env::temp_dir().to_string_lossy(),
                std::process::id()
            )),
            ..LogCfg::default()
        };
        let logger = Arc::new(AccessLogger::new(&cfg, "http://127.0.0.1:8081").expect("构造"));
        let rx = take_rx(&logger);
        let sink_logger = logger.clone();
        tokio::spawn(async move { sink_logger.sink_loop(rx).await });
        for i in 0..3 {
            logger.submit(test_entry(i));
        }
        // 等待interval过后文件应含3行
        tokio::time::sleep(Duration::from_millis(120)).await;
        let path = cfg.access_file.expect("路径");
        let content = std::fs::read_to_string(&path).expect("读取日志文件");
        let _ = std::fs::remove_file(&path);
        assert_eq!(content.lines().count(), 3, "超时 flush 应落盘全部条目");
    }
}
