//! 多上游负载均衡池(`UpstreamPool`)，每实例独立`UpstreamClient` + 健康位 + 在途计数，
//! 支持round-robin / random / least-conn / ip-hash / auto五种模式，转发失败自动重试
//! 下一个健康实例，连续失败 ≥3标记down，故障剔除，健康探测成功恢复，重新纳入。
//! 随机与哈希委托成熟库，random 用 rand 线程随机源，ip-hash 用 fnv crate，
//!   算法与替换前一致。
//! servers为空时内部等价vec![base_url]，单上游任何模式行为一致，
//!   现有`FMS_RGW_UPSTREAM`单url部署行为不变。
//! 熔断粒度，全局`CircuitBreaker`保留为整体兜底(forward任一实例成功即成功，
//!   最后一次错误才失败)，实例级故障剔除/恢复由本池的健康位管理(健康探测 + 转发
//!   连续失败共用`mark_healthy` / `record_failure`)。
//!
//! 调用关系，`AppState` 的 upstream 字段(`state.rs`)供 `proxy_handler`/`ws_handler`
//! 转发使用(`proxy.rs`/`ws.rs`)，`circuit::health_probe_loop` 调 `probe_all` 周期探测
#![forbid(unsafe_code)]

use crate::config::UpstreamCfg;
use crate::proxy::{ProxyError, UpstreamClient, UpstreamRequestParts, build_upstream_request_from};
use crate::types::BufferedBody;
use axum::body::Body;
use axum::http::{Method, Request, Response};
use bytes::Bytes;
use rand::Rng;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::time::Duration;
use tracing::{info, warn};

/// 连续转发失败达到此阈值即标记down，故障剔除，等待健康探测恢复。
/// 连续 3 次失败即剔除，单次偶发超时或抖动不触发，避免抖动导致全池反复剔除，
/// 3 次连续失败基本可判定实例不可用；与健康探测周期，默认 5s，配合，剔除后探测失败也维持 down
const CONSECUTIVE_FAIL_THRESHOLD: u64 = 3;

/// 负载均衡模式，配置字符串解析，未知值回退round-robin并告警
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LbMode {
    RoundRobin,
    Random,
    LeastConn,
    IpHash,
    /// auto = least-conn，池默认启用健康探测与故障剔除，单上游时任何模式行为一致
    Auto,
}

impl LbMode {
    fn parse(s: &str) -> LbMode {
        match s.trim().to_ascii_lowercase().as_str() {
            "round-robin" => Self::RoundRobin,
            "random" => Self::Random,
            "least-conn" => Self::LeastConn,
            "ip-hash" => Self::IpHash,
            "auto" => Self::Auto,
            other => {
                warn!(mode = %other, "未知负载均衡模式，回退 round-robin");
                Self::RoundRobin
            }
        }
    }
}

/// 单个上游实例，独立客户端 + 健康位 + 在途计数 + 连续失败计数
pub struct UpstreamEntry {
    client: UpstreamClient,
    /// 健康标记，主动探测与转发失败共用，false = 剔除出选择集
    healthy: AtomicBool,
    /// 在途请求数(least-conn依据，发送前原子自增、返回后自减，见`InFlightGuard`)
    in_flight: AtomicU64,
    /// 连续转发失败计数(≥`CONSECUTIVE_FAIL_THRESHOLD`标记down，成功/探测成功清零)
    consecutive_failures: AtomicU64,
    /// 实例`base_url`，日志 / 探测url组装用
    base_url: String,
}

impl UpstreamEntry {
    fn new(client: UpstreamClient, base_url: String) -> Self {
        Self {
            client,
            healthy: AtomicBool::new(true),
            in_flight: AtomicU64::new(0),
            consecutive_failures: AtomicU64::new(0),
            base_url,
        }
    }

    fn is_healthy(&self) -> bool {
        self.healthy.load(Ordering::Acquire)
    }

    /// 更新健康位(`health_probe_loop`探测结果与转发连续失败共用)，
    /// 转 `healthy` 时清零连续失败计数重新累计，转不健康仅置位
    fn mark_healthy(&self, ok: bool) {
        let prev = self.healthy.swap(ok, Ordering::AcqRel);
        if ok {
            self.consecutive_failures.store(0, Ordering::Relaxed);
            if !prev {
                info!(url = %self.base_url, "实例恢复健康，重新纳入负载均衡");
            }
        } else if prev {
            warn!(url = %self.base_url, "实例标记不健康(剔除出选择集)");
        }
    }

    /// 记录一次转发失败，连续失败 ≥ 阈值时标记down，故障剔除。
    /// 返回本次是否刚被剔除，供调用方日志，阈值内失败不剔除，防抖动
    fn record_failure(&self) -> bool {
        let n = self.consecutive_failures.fetch_add(1, Ordering::Relaxed) + 1;
        if n >= CONSECUTIVE_FAIL_THRESHOLD && self.healthy.swap(false, Ordering::AcqRel) {
            warn!(url = %self.base_url, failures = n, "连续转发失败达到阈值，实例标记 down(待健康探测恢复)");
            true
        } else {
            false
        }
    }

    /// 转发成功，清零连续失败计数，从零重新计数
    fn record_success(&self) {
        self.consecutive_failures.store(0, Ordering::Relaxed);
    }

    /// 发送前调用，在途计数 +1，返回守卫(`Drop`时自动归还)。
    /// 用守卫而非显式 `fetch_sub` 归还，async 发送可能在 await 点被取消或中断，
    /// 守卫的 `Drop` 保证计数必归还，语义等价显式 finally，least-conn 的计数
    /// 不因取消而漂移
    fn begin_request(&self) -> InFlightGuard<'_> {
        self.in_flight.fetch_add(1, Ordering::Relaxed);
        InFlightGuard(&self.in_flight)
    }
}

/// 在途请求守卫(见`UpstreamEntry::begin_request`)
pub(crate) struct InFlightGuard<'a>(&'a AtomicU64);

impl Drop for InFlightGuard<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

/// ws目标实例，选定后固定，长连接粘性，
/// idx = 池内下标，发送用，authority/`base_url` = 该实例地址(请求重建用，
/// `Host`重写 + 上游uri组装，与选中实例一致)
pub struct WsTarget {
    pub idx: usize,
    pub authority: String,
    pub base_url: String,
}

/// 多上游负载均衡池(`AppState`.upstream持有，`Arc`共享)
pub struct UpstreamPool {
    entries: Vec<UpstreamEntry>,
    mode: LbMode,
    /// round-robin轮转计数
    rr_counter: AtomicUsize,
    /// 转发失败自动重试次数上限，仅重试健康实例
    retry_count: usize,
}

impl UpstreamPool {
    /// 构建池，servers非空用servers，否则等价vec![base_url]，单上游兼容，
    /// 任一url非法则 `InvalidUri`，启动期失败。每实例独立连接池
    /// (`UpstreamClient`自身带池)，实例间互不干扰
    pub fn new(cfg: &UpstreamCfg) -> Result<Self, ProxyError> {
        let urls: Vec<String> = if cfg.servers.is_empty() {
            vec![cfg.base_url.clone()]
        } else {
            cfg.servers.clone()
        };

        let mut entries = Vec::with_capacity(urls.len());
        for url in urls {
            let client = UpstreamClient::new(
                url.clone(),
                Duration::from_secs(cfg.request_timeout_secs),
                Duration::from_secs(cfg.idle_timeout_secs),
                cfg.pool_max_idle_per_host,
            )?;
            entries.push(UpstreamEntry::new(client, url));
        }

        info!(
            servers = entries.len(),
            mode = %cfg.mode,
            retry_count = cfg.retry_count,
            "上游负载均衡池构建完成"
        );
        Ok(Self {
            entries,
            mode: LbMode::parse(&cfg.mode),
            rr_counter: AtomicUsize::new(0),
            retry_count: cfg.retry_count,
        })
    }

    /// 池内实例数，日志用
    pub fn server_count(&self) -> usize {
        self.entries.len()
    }

    /// 健康实例数，日志 / 全局熔断兜底判断
    pub fn healthy_count(&self) -> usize {
        self.entries.iter().filter(|e| e.is_healthy()).count()
    }

    /// 更新实例健康位(`health_probe_loop`调用，转发失败走`record_failure`自管理)
    pub fn mark_healthy(&self, idx: usize, ok: bool) {
        if let Some(entry) = self.entries.get(idx) {
            entry.mark_healthy(ok);
        }
    }

    /// 按配置模式选择一个健康实例(只选healthy=true，全down 则返回 `None`)，
    /// round-robin 原子计数轮转，从起点线性探测第一个健康实例，
    /// random 用 rand 线程随机源定随机起点后线性探测，
    /// least-conn 选在途请求数最小者，并发下为近似值，平手取序号小者，确定性，
    /// ip-hash 按 fnv1a 取模定位后线性探测第一个健康实例，简化一致性哈希，
    ///   实例数变化会重映射，会话粘性为尽力而为，无ip时回退round-robin，
    /// auto 等价 least-conn，自动健康管理由探测与转发失败共用，
    ///   单上游时退化为直连，任何模式行为一致
    pub fn pick(&self, client_ip: Option<&str>) -> Option<usize> {
        if self.entries.is_empty() {
            return None;
        }

        match self.mode {
            LbMode::IpHash => match client_ip {
                Some(ip) => {
                    let start = (fnv1a(ip) % self.entries.len() as u64) as usize;
                    self.probe_from(start)
                }
                None => self.round_robin(),
            },
            LbMode::Random => {
                let start = rand::rng().random_range(0..self.entries.len());
                self.probe_from(start)
            }
            LbMode::LeastConn | LbMode::Auto => self.least_conn(),
            LbMode::RoundRobin => self.round_robin(),
        }
    }

    /// 从start起环形线性探测第一个健康实例(全down 则返回 `None`)
    fn probe_from(&self, start: usize) -> Option<usize> {
        let n = self.entries.len();
        for off in 0..n {
            let idx = (start + off) % n;
            if self.entries[idx].is_healthy() {
                return Some(idx);
            }
        }
        None
    }

    fn round_robin(&self) -> Option<usize> {
        let n = self.entries.len();
        let start = self.rr_counter.fetch_add(1, Ordering::Relaxed) % n;
        self.probe_from(start)
    }

    fn least_conn(&self) -> Option<usize> {
        let mut best: Option<(usize, u64)> = None;
        for (idx, e) in self.entries.iter().enumerate() {
            if !e.is_healthy() {
                continue;
            }
            let inflight = e.in_flight.load(Ordering::Relaxed);
            match best {
                // 平手取序号小者，least-conn在并发下读的是近似值，平手规则不追求
                // 绝对公平，确定性优先，round-robin模式天然互补
                Some((_, min)) if min <= inflight => {}
                _ => best = Some((idx, inflight)),
            }
        }
        best.map(|(idx, _)| idx)
    }

    /// 转发，http路径，按模式选实例，重建上游请求，交该实例 send，
    /// 失败按`retry_count`重试下一个健康实例，连续失败 ≥3标记down，故障剔除，
    /// 全部重试失败返回最后一次错误。
    ///
    /// 请求重建，axum `Body`不可`Clone`、`Request`无法整体克隆，先取出
    /// `BufferedBody`，bytes 与头模板(`HeaderMap`可`Clone`)，每次尝试基于模板为
    /// 目标实例重建(body为`Bytes` clone，仅引用计数，开销可忽略)，
    /// 重建逻辑与`UpstreamClient::forward`共用`build_upstream_request_from`，防漂移
    pub async fn forward(
        &self,
        req: Request<Body>,
        client_ip: Option<&str>,
    ) -> Result<Response<Body>, ProxyError> {
        let (parts, _) = req.into_parts();

        // 请求体，mw_sign已缓冲进extensions，直接取用，重试复用
        let body_bytes: Bytes = parts
            .extensions
            .get::<BufferedBody>()
            .map(|b| b.0.clone())
            .unwrap_or_else(Bytes::new);

        // 头模板，重建逻辑含剥离/重写副作用，每次尝试独立克隆
        let headers_template = parts.headers.clone();
        let method = parts.method.clone();
        let path = parts.uri.path().to_string();
        let query = parts.uri.query().map(String::from);
        let mut last_err = ProxyError::Connect;

        // 尝试次数 = 初次 + retry_count次重试，默认2 则最多3次尝试
        for attempt in 0..=self.retry_count {
            let Some(idx) = self.pick(client_ip) else {
                // 无健康实例，重试只会重复失败，立即返回最后一次错误。
                // 单上游全down时退化为快速失败，恢复由健康探测循环驱动
                warn!(healthy = self.healthy_count(), "无健康上游实例可转发");
                break;
            };
            let entry = &self.entries[idx];

            let up_req = match build_upstream_request_from(
                &UpstreamRequestParts {
                    headers: headers_template.clone(),
                    method: method.clone(),
                    path: path.clone(),
                    query: query.clone(),
                    body_bytes: body_bytes.clone(),
                },
                client_ip,
                &entry.base_url,
                entry.client.upstream_authority(),
            ) {
                Ok(r) => r,
                Err(e) => {
                    last_err = e;
                    continue;
                }
            };

            let guard = entry.begin_request();
            let result = entry.client.send(up_req).await;
            drop(guard);

            match result {
                Ok(resp) => {
                    entry.record_success();
                    return Ok(resp);
                }
                Err(e) => {
                    last_err = e;
                    let down = entry.record_failure();
                    warn!(url = %entry.base_url, attempt, error = %last_err, down, "上游实例转发失败，尝试下一健康实例");
                }
            }
        }
        Err(last_err)
    }

    /// ws 握手目标选取，有 ip 时按 ip-hash，无 ip 时按 round-robin，选定后固定，
    /// 长连接粘性(握手后双向泵全程同一实例，不参与重试，实例 down 时握手失败
    /// 由全局熔断兜底)。无健康实例则返回 `None`
    pub fn ws_target(&self, client_ip: Option<&str>) -> Option<WsTarget> {
        let idx = match client_ip {
            Some(ip) => {
                let start = (fnv1a(ip) % self.entries.len() as u64) as usize;
                self.probe_from(start)
            }
            None => self.round_robin(),
        }?;

        let entry = &self.entries[idx];
        Some(WsTarget {
            idx,
            authority: entry.client.upstream_authority().to_string(),
            base_url: entry.base_url.clone(),
        })
    }

    /// ws 握手发送，发往 `ws_target` 选定的实例，固定粘性，返回响应与上游 authority。
    /// 请求由调用方按 `WsTarget` 的 `base_url`/authority 重建(`Host` 已重写)，此处直发
    pub async fn send_for_ws(
        &self,
        req: Request<Body>,
        target: &WsTarget,
    ) -> Result<(Response<Body>, String), ProxyError> {
        let entry = &self.entries[target.idx];
        let guard = entry.begin_request();
        let resp = entry.client.send(req).await;
        drop(guard);
        resp.map(|r| (r, entry.client.upstream_authority().to_string()))
    }

    /// 主动健康探测(`circuit::health_probe_loop`调用)，遍历池内全部实例，每实例
    /// GET {`base_url`}{`health_path`}(超时 `health_timeout`，直连不经安全链)，
    /// 结果成功则置健康、失败则置不健康，故障实例持续被探测，恢复后自动重新
    /// 纳入选择集。返回探测后健康实例数(供全局熔断 `record_probe` 判断)
    pub async fn probe_all(&self, health_path: &str, timeout: Duration) -> usize {
        let path = health_path.trim();

        for entry in &self.entries {
            let url = format!("{}{}", entry.base_url.trim_end_matches('/'), path);
            let req = match Request::builder()
                .method(Method::GET)
                .uri(url.as_str())
                .body(Body::empty())
            {
                Ok(r) => r,
                Err(e) => {
                    warn!(url = %url, error = %e, "健康探测：非法探测 URL，实例标记不健康");
                    entry.mark_healthy(false);
                    continue;
                }
            };

            let outcome = tokio::time::timeout(timeout, entry.client.send(req)).await;
            let ok = match outcome {
                Err(_) => {
                    warn!(url = %url, "健康探测超时");
                    false
                }
                Ok(Err(e)) => {
                    warn!(url = %url, error = %e, "健康探测失败");
                    false
                }
                Ok(Ok(resp)) => {
                    let success = resp.status().is_success();
                    if !success {
                        warn!(url = %url, status = %resp.status(), "健康探测非 2xx");
                    }
                    // 排空响应体使连接可回池复用
                    let _ = axum::body::to_bytes(resp.into_body(), 64 * 1024).await;
                    success
                }
            };

            entry.mark_healthy(ok);
        }

        self.healthy_count()
    }
}

/// FNV-1a 64位哈希，ip-hash用，委托 fnv crate，逐字节结果与替换前自实现一致
fn fnv1a(s: &str) -> u64 {
    use std::hash::Hasher;
    let mut h = fnv::FnvHasher::default();
    h.write(s.as_bytes());
    h.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 测试池构造，url只需合法(`UpstreamClient`惰性建连，不触网)
    fn test_pool(urls: Vec<String>, mode: &str) -> UpstreamPool {
        let cfg = UpstreamCfg {
            servers: urls,
            mode: mode.to_string(),
            ..UpstreamCfg::default()
        };
        UpstreamPool::new(&cfg).expect("测试池构造")
    }

    fn test_urls(n: usize) -> Vec<String> {
        (1..=n).map(|i| format!("http://127.0.0.1:{i}")).collect()
    }

    #[test]
    fn round_robin_rotates_in_order() {
        let pool = test_pool(test_urls(3), "round-robin");
        let mut seq = Vec::new();
        for _ in 0..6 {
            seq.push(pool.pick(None).unwrap());
        }
        assert_eq!(seq, vec![0, 1, 2, 0, 1, 2], "round-robin 按序轮转");
    }

    #[test]
    fn round_robin_skips_down_instance() {
        let pool = test_pool(test_urls(3), "round-robin");
        pool.mark_healthy(1, false);
        for _ in 0..12 {
            let picked = pool.pick(None).unwrap();
            assert_ne!(picked, 1, "剔除实例不得被选中");
            assert!(picked == 0 || picked == 2);
        }
    }

    #[test]
    fn recovery_reincluded_after_healthy() {
        let pool = test_pool(test_urls(2), "round-robin");
        pool.mark_healthy(1, false);
        for _ in 0..4 {
            assert_eq!(pool.pick(None), Some(0));
        }
        pool.mark_healthy(1, true);
        let mut picked_1 = false;
        for _ in 0..4 {
            picked_1 |= pool.pick(None) == Some(1);
        }
        assert!(picked_1, "恢复健康后实例应重新纳入选择集");
    }

    #[test]
    fn least_conn_picks_min_in_flight() {
        let pool = test_pool(test_urls(3), "least-conn");
        // 实例1在途2，实例0/2在途0则选序号最小的在途0者，实例0
        let g1 = pool.entries[1].begin_request();
        let _g1b = pool.entries[1].begin_request();
        assert_eq!(pool.pick(None), Some(0), "least-conn 选在途最小者");
        drop(g1);
        // 实例0在途1，其余0则选 1 或 2
        let _g0 = pool.entries[0].begin_request();
        let picked = pool.pick(None).unwrap();
        assert!(picked == 1 || picked == 2, "应避开在途 1 的实例 0，实际 {picked}");
    }

    #[test]
    fn ip_hash_stable_for_same_ip() {
        let pool = test_pool(test_urls(4), "ip-hash");
        let first = pool.pick(Some("203.0.113.7")).unwrap();
        for _ in 0..20 {
            assert_eq!(
                pool.pick(Some("203.0.113.7")).unwrap(),
                first,
                "健康状态不变时同 IP 恒选同实例(粘性)"
            );
        }
        // 无ip则回退 round-robin，轮转
        let a = pool.pick(None).unwrap();
        let b = pool.pick(None).unwrap();
        assert_ne!(a, b, "无 IP 时应轮转");
    }

    #[test]
    fn ip_hash_probes_to_next_healthy_when_start_down() {
        let pool = test_pool(test_urls(4), "ip-hash");
        let start = (fnv1a("203.0.113.7") % 4) as usize;
        pool.mark_healthy(start, false);
        let after = pool.pick(Some("203.0.113.7")).unwrap();
        assert_ne!(after, start, "起点实例 down 应线性探测到下一个健康实例");
    }

    #[test]
    fn healthy_count_tracks_marks() {
        let pool = test_pool(test_urls(3), "auto");
        assert_eq!(pool.healthy_count(), 3);
        pool.mark_healthy(1, false);
        assert_eq!(pool.healthy_count(), 2);
        pool.mark_healthy(1, true);
        assert_eq!(pool.healthy_count(), 3);
    }

    #[test]
    fn auto_mode_equals_least_conn() {
        let pool = test_pool(test_urls(3), "auto");
        let _g = pool.entries[2].begin_request();
        assert_eq!(pool.pick(None), Some(0), "auto = least-conn：选在途最小者");
    }

    #[test]
    fn unknown_mode_falls_back_round_robin() {
        let pool = test_pool(test_urls(2), "maglev");
        assert_eq!(pool.mode, LbMode::RoundRobin, "未知模式回退 round-robin");
    }

    #[test]
    fn consecutive_failures_mark_down_after_threshold() {
        let pool = test_pool(test_urls(1), "round-robin");
        assert!(!pool.entries[0].record_failure());
        assert!(!pool.entries[0].record_failure());
        assert!(pool.entries[0].record_failure(), "第 3 次连续失败应剔除");
        assert_eq!(pool.healthy_count(), 0);
        assert_eq!(pool.pick(None), None, "全 down 时无实例可选");
        // 成功，探测恢复后重新纳入
        pool.mark_healthy(0, true);
        assert_eq!(pool.healthy_count(), 1);
        assert_eq!(pool.pick(None), Some(0));
    }

    #[tokio::test]
    async fn forward_all_down_fails_fast_without_network() {
        let pool = test_pool(test_urls(2), "round-robin");
        pool.mark_healthy(0, false);
        pool.mark_healthy(1, false);
        let req = Request::builder()
            .uri("http://127.0.0.1:1/api/x")
            .body(Body::empty())
            .unwrap();
        let err = pool.forward(req, None).await.expect_err("全 down 应快速失败");
        assert!(matches!(err, ProxyError::Connect), "无健康实例返回 Connect 语义");
    }

    #[test]
    fn single_upstream_any_mode_behaves_same() {
        // 单上游兼容性，servers空则等价 vec![base_url]，任意模式唯一实例恒被选中
        for mode in ["round-robin", "random", "least-conn", "ip-hash", "auto"] {
            let pool = test_pool(Vec::new(), mode); // servers空则用 base_url，默认127.0.0.1:8081
            assert_eq!(pool.server_count(), 1, "servers 空 = 单上游(mode={mode})");
            assert_eq!(pool.pick(Some("1.2.3.4")), Some(0), "单上游恒选中 0(mode={mode})");
            assert_eq!(pool.pick(None), Some(0), "单上游无 IP 恒选中 0(mode={mode})");
        }
    }
}
