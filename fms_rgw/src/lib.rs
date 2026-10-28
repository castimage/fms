//! FMS的Rust网关库，装配与启动逻辑提取为库，供二进制main与集成测试共用
//! (run/`build_state`/`build_router`/serve)
//!
//! 模块装配，compression全局最外层，layer后加者为外层，即 `mw_access_log` 次外层(压缩
//! 关闭时最外层)接 cors，再到 `/api`/{*rest}
//! 安全链(trace、filter、rate、auth、sign 由外到内，`route_layer`同样后加者为外层)，随后 ws/proxy 分发
//! ws/proxy分发，`/healthz`仅trace，fallback 则交 `static_handler`。
#![forbid(unsafe_code)]

pub mod auth;
pub mod circuit;
pub mod config;
pub mod cors;
pub mod filter;
pub mod headers;
pub mod lb;
pub mod log;
pub mod nonce;
pub mod proxy;
pub mod ratelimit;
use ratelimit::RateLimitBackend;
pub mod sign;
pub mod state;
pub mod r#static;
pub mod types;
pub mod ws;

use axum::Router;
use axum::body::Body;
use axum::extract::State;
use axum::http::header::CONTENT_TYPE;
use axum::http::{Response, StatusCode};
use axum::middleware;
use axum::routing::{any, get};
use circuit::CircuitState;
use hyper::body::Incoming;
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::server::conn::auto;
use hyper_util::service::TowerToHyperService;
use rustls::crypto::ring;
use state::AppState;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::net::TcpListener;
use tokio::sync::Semaphore;
use tracing::{error, info, warn};

/// 同步入口，二进制main调用，配置加载后做密钥校验，再初始化 tracing，随后 `build_state`，最后 serve
pub fn run() -> Result<(), Box<dyn std::error::Error>> {
    let mut cfg = config::load()?;
    config::apply_env_overrides(&mut cfg);

    // 必填密钥校验，启动期失败eprintln + exit，拒绝空值、占位值与 <32字符弱密钥，
    // 占位密钥可被伪造jwt与X-FMS-Signature完全绕过认证与后端验签
    let jwt_ok = validate_secret(&cfg.auth.jwt_secret);
    let sign_ok = validate_secret(&cfg.sign.sign_secret);
    if !jwt_ok || !sign_ok {
        eprintln!(
            "[fms_rgw] 启动失败：[auth].jwt_secret 与 [sign].sign_secret 必须配置强密钥 \
             (≥32 字符随机字节，禁止空值/占位符/过短值；\
             config/fms_rgw.yaml 或环境变量 FMS_RGW_JWT_SECRET / FMS_RGW_SIGN_SECRET)"
        );
        std::process::exit(1);
    }

    // cors白名单非空校验(与密钥校验同一模式，避免build_router panic堆栈外泄配置信息)
    if cfg.cors.allowed_origins.is_empty() {
        eprintln!(
            "[fms_rgw] 启动失败：[cors].allowed_origins 不能为空(CORS 白名单必须显式配置，拒绝全部跨域需显式声明)"
        );
        std::process::exit(1);
    }

    // tracing，json输出 + env-filter，级别取配置，构造不可失败
    let filter = tracing_subscriber::EnvFilter::new(&cfg.log.level);
    tracing_subscriber::fmt()
        .json()
        .with_env_filter(filter)
        .try_init()
        .map_err(|e| format!("tracing 初始化失败：{e}"))?;

    // rustls加密提供者，ring，重复安装幂等
    let _ = ring::default_provider().install_default();

    let state = build_state(cfg)?;
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    rt.block_on(async move { serve(state).await })
}

/// 启动期密钥强度校验，非空、长度 ≥ 32、非占位值(`CHANGE_ME`等弱形态)，false即启动失败
fn validate_secret(secret: &str) -> bool {
    if secret.is_empty() || secret.len() < 32 {
        return false;
    }
    let lower = secret.to_ascii_lowercase();
    if lower.contains("change_me") || lower.contains("placeholder") || lower.contains("xxxx") {
        return false;
    }
    // 全部同一字符，如 "aaaaaaaa..."视为占位弱密钥
    let first = secret.as_bytes()[0];
    if secret.bytes().all(|b| b == first) {
        return false;
    }
    true
}

/// 组装`AppState`，upstream/日志等可失败项在启动期暴露
pub fn build_state(cfg: config::Config) -> Result<Arc<AppState>, Box<dyn std::error::Error>> {
    // 多上游负载均衡池，servers空 = 单上游，内部等价vec![base_url]，
    // 任一url非法则启动期失败
    let upstream = Arc::new(lb::UpstreamPool::new(&cfg.upstream)?);
    let circuit_cfg = cfg.circuit.clone();
    let shards = cfg.limits.shards;
    let max_keys = cfg.limits.max_keys;

    // 限流后端装配，memory，默认| redis，多网关实例共享计数，redis缺url或
    // backend非法一律启动失败，fail-closed，配置错误不静默回退
    let limiter: Arc<dyn RateLimitBackend> = match cfg.limits.backend.as_str() {
        "memory" => Arc::new(ratelimit::RateLimiter::new(shards, max_keys)),
        "redis" => {
            let url = cfg
                .limits
                .redis_url
                .as_deref()
                .filter(|u| !u.is_empty())
                .ok_or_else(|| {
                    Box::<dyn std::error::Error>::from(
                        "limits.backend=redis 但 limits.redis_url 为空(fail-closed)",
                    )
                })?;
            Arc::new(
                ratelimit::RedisRateLimiter::new(url).map_err(|e| {
                    Box::<dyn std::error::Error>::from(format!("redis 限流后端初始化失败: {e}"))
                })?,
            )
        }
        other => {
            return Err(Box::<dyn std::error::Error>::from(format!(
                "limits.backend 非法: {other}(仅支持 memory | redis)"
            )))
        }
    };

    // ip白名单配置校验，非法条目启动期fail-closed，不静默忽略
    filter::parse_allowlist(&cfg.server.ip_allowlist).map_err(|e| {
        Box::<dyn std::error::Error>::from(format!("server.ip_allowlist 非法: {e}"))
    })?;

    // 登出撤销后端装配，memory，进程内，默认| redis(多网关实例共享撤销，复用
    // limits.redis_url连接)，redis缺url或非法backend启动失败，fail-closed，
    // 与限流后端同模式，运行期Redis不可达为尽力而为，进程内表兜底
    let deny: Arc<state::TokenDenyList> = match cfg.auth.deny_store.as_str() {
        "memory" => Arc::new(state::TokenDenyList::new()),
        "redis" => {
            let url = cfg
                .limits
                .redis_url
                .as_deref()
                .filter(|u| !u.is_empty())
                .ok_or_else(|| {
                    Box::<dyn std::error::Error>::from(
                        "auth.deny_store=redis 但 limits.redis_url 为空(fail-closed)",
                    )
                })?;
            let remote = state::RedisDenyRemote::new(url).map_err(|e| {
                Box::<dyn std::error::Error>::from(format!("redis 登出撤销后端初始化失败: {e}"))
            })?;
            Arc::new(state::TokenDenyList::with_remote(Some(Arc::new(remote))))
        }
        other => {
            return Err(Box::<dyn std::error::Error>::from(format!(
                "auth.deny_store 非法: {other}(仅支持 memory | redis)"
            )))
        }
    };

    // 访问日志器，采样 + 有界通道 + 后台sink批量双写 / pgmq http中转投递
    // (backend_base_url = 上游地址，多实例取servers[0]作为pgmq投递目标，
    // servers空时 = base_url，单上游等价 base_url)
    let backend_base = if cfg.upstream.servers.is_empty() {
        cfg.upstream.base_url.clone()
    } else {
        cfg.upstream.servers[0].clone()
    };
    let logger = log::AccessLogger::new(&cfg.log, &backend_base)?;

    Ok(Arc::new(AppState {
        cfg: Arc::new(cfg),
        limiter,
        circuit: Arc::new(circuit::CircuitBreaker::new(circuit_cfg)),
        upstream,
        logger: Arc::new(logger),
        deny,
        started_at: Instant::now(),
    }))
}

/// 路由装配，compression全局最外层，layer后加者为外层，即 `mw_access_log` 次外层(压缩
/// 关闭时最外层)接 cors，再到 `/api`/{*rest}
/// 安全链，运行时由外层到内层依次 trace、filter、rate、auth、sign，随后分发
/// ws/proxy，`/healthz`仅追加trace，不限流不认证，fallback 则交 `static_handler`。
///
/// `route_layer`后加者在最外层，故按最内层先加的反序添加，filter先于auth拦截攻击，
/// auth先剥离客户端伪造的`X-FMS-*`并注入身份头，sign最后注入`X-FMS-Signature`
/// 不被auth剥离逻辑误删，可送达上游验签
pub fn build_router(state: Arc<AppState>) -> Router {
    let ip_allowlist_mw = middleware::from_fn_with_state(state.clone(), filter::mw_ip_allowlist);
    let trace_mw = middleware::from_fn_with_state(state.clone(), types::mw_trace);
    let filter_mw = middleware::from_fn_with_state(state.clone(), filter::mw_input_filter);
    let rate_mw = middleware::from_fn_with_state(state.clone(), ratelimit::mw_rate_limit);
    let auth_mw = middleware::from_fn_with_state(state.clone(), auth::mw_auth);
    let sign_mw = middleware::from_fn_with_state(state.clone(), sign::mw_sign);
    let log_mw = middleware::from_fn_with_state(state.clone(), log::mw_access_log);

    // cors配置错误属启动期不变量，空白名单 = 配置拒绝跨域，须显式配置，panic退出
    let cors_layer = match cors::cors_layer(&state.cfg.cors) {
        Ok(l) => l,
        Err(e) => panic!("CORS 配置错误(启动期不变量)：{e}"),
    };

    // 响应体压缩层(gzip/deflate/br/zstd按Accept-Encoding协商)，enabled时才装配，
    // 仅作用于响应体，请求头处理不受影响，自动加Vary: Accept-Encoding/Content-Encoding，
    // predicate与DefaultPredicate同构(SizeAbove min_length + 排除GRPC/图片/sse，
    // 已压缩内容与ws升级由层内部跳过)
    let compression_layer = if state.cfg.server.compression.enabled {
        use tower_http::compression::predicate::{NotForContentType, Predicate, SizeAbove};
        use tower_http::compression::CompressionLevel;
        let min = state.cfg.server.compression.min_length.min(u16::MAX as usize) as u64;
        let predicate = SizeAbove::new(min)
            .and(NotForContentType::GRPC)
            .and(NotForContentType::IMAGES)
            .and(NotForContentType::SSE);
        Some(
            tower_http::compression::CompressionLayer::new()
                .quality(CompressionLevel::Precise(state.cfg.server.compression.level))
                .compress_when(predicate),
        )
    } else {
        None
    };

    let router = Router::new()
        .route("/api/{*rest}", any(ws::ws_handler))
        .route_layer(sign_mw)
        .route_layer(auth_mw)
        .route_layer(rate_mw)
        .route_layer(filter_mw)
        .route_layer(trace_mw)
        .route_layer(ip_allowlist_mw) // 最外层，ip白名单最先判定，非白名单直接丢弃
        // /healthz在安全链route_layer之后注册则豁免限流/认证，下一行route_layer
        // 作用于其上已注册的全部路由(/api与/healthz)，/healthz获得trace，
        // /api的trace层被重复套用(幂等，两层复用同一TraceSampled采样判定，
        // 外层生成/透传后内层重写同值TraceCtx与请求头，无副作用)
        .route("/healthz", get(health_handler))
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            types::mw_trace,
        ))
        .fallback(r#static::static_handler)
        .layer(cors_layer)
        .layer(log_mw)
        // 全局爬虫ua拦截，cors/log外层、压缩内层，覆盖全部路径含静态与
        // /healthz，爬虫主目标为公开面首页/登录页，仅/api面拦截防不住
        .layer(middleware::from_fn_with_state(
            state.clone(),
            filter::mw_crawler_block,
        ));

    // 压缩层为全局最外层layer，后加者为外层，响应路径经 handler 到 cors头，再到日志，安全头
    // 随后压缩(压缩在日志之后施加，日志latency_ms不含压缩耗时，ws 101/小响应由predicate
    // SizeAbove + NotForContentType跳过)
    match compression_layer {
        Some(l) => router.layer(l).with_state(state),
        None => router.with_state(state),
    }
}

/// 后台任务，限流表 + 登出令牌黑名单按`limits.cleanup_interval_secs`周期清理
/// nonce去重表由后端侧负责，网关不持有
async fn periodic_cleanup(state: Arc<AppState>) {
    let interval_secs = state.cfg.limits.cleanup_interval_secs;
    loop {
        tokio::time::sleep(Duration::from_secs(interval_secs)).await;
        state.limiter.cleanup();
        state.deny.cleanup().await;
    }
}

/// tls优先，alpn h2/http1.1双协议，hyper-util auto按协商结果选协议，upgrade路径供ws升级，
/// 证书缺失/加载失败仅在`allow_insecure_fallback`=true时降级明文，生产直接启动失败。
/// 关闭信号由内部`ctrl_c`/SIGTERM触发(二次`ctrl_c`强制退出)，测试用
/// `serve_with_shutdown` 注入外部信号通道
pub async fn serve(state: Arc<AppState>) -> Result<(), Box<dyn std::error::Error>> {
    serve_with_shutdown(state, None).await
}

/// [0]的可注入关闭信号版本，`None` = 内部watch通道由`ctrl_c`/SIGTERM触发，与serve一致，
/// `Some`，rx = 外部触发，测试用，不注册os信号，关闭路径可完整断言。
///
/// 完整启动序，先 `build_router`，再解析监听地址，随后 spawn后台任务(健康探测
/// `health_probe_loop`、`limits.cleanup_interval_secs`周期清理限流表、访问日志sink
/// 批量双写)后做 tls装配(双证书齐备则加载rustls `Acceptor`，alpn h2/http1.1，
/// 缺失或加载失败时按`allow_insecure_fallback`决定降级明文或启动失败，生产缺证书
/// 必须启动失败绝不静默降级)后 tcp监听，随后进入 accept 循环。
///
/// accept循环，watch关闭信号，changed 则停止 accept 或 accept 新连接(失败仅WARN
/// 继续监听)，每连接先取`Semaphore`许可(`server.max_connections`兼作in-flight
/// 计数，满则拒绝新连接)，tls握手，失败仅WARN或明文后spawn `serve_connection`，
/// permit随任务结束释放。
///
/// 优雅关闭，收到信号后停止accept，等待全部in-flight连接完成(`conn_sem`重新
/// acquire全部`max_connections`许可即无在途连接)，最长`server.graceful_shutdown_secs`，
/// 超时强制退出，二次`ctrl_c`由`shutdown_signal`任务处理为立即退出。
///
/// `state`，已装配的全局状态，配置/上游/限流/熔断/日志
///
/// `external_shutdown`，`None` = 内部注册os信号，生产，`Some`，rx = 外部触发
/// 测试注入，关闭路径可断言
///
/// @return正常退出Ok，tls强制失败，无证书且不允许降级等返回`Err`
pub async fn serve_with_shutdown(
    state: Arc<AppState>,
    external_shutdown: Option<tokio::sync::watch::Receiver<()>>,
) -> Result<(), Box<dyn std::error::Error>> {
    let router = build_router(state.clone());
    let addr: SocketAddr = state.cfg.server.listen.parse()?;
    let max_conns = state.cfg.server.max_connections;
    let grace_secs = state.cfg.server.graceful_shutdown_secs;
    info!(listen = %addr, servers = state.upstream.server_count(), "fms_rgw 启动，上游 {}", state.cfg.upstream.base_url);

    // 后台任务，访问日志sink(批量双写 + 可选pgmq投递，随进程退出，无需优雅
    // drain，日志可丢，请求路径绝不受影响)+ 健康探测 + 周期清理
    log::spawn_log_sink(state.logger.clone());
    tokio::spawn(circuit::health_probe_loop(state.clone()));
    tokio::spawn(periodic_cleanup(state.clone()));

    // rustls加密提供者，ring(幂等，serve内安装保证测试直调serve不依赖run)
    let _ = ring::default_provider().install_default();

    // 明文降级受allow_insecure_fallback控制，默认false，生产缺证书必须启动失败，绝不静默降级
    let tls_acceptor: Option<Arc<tokio_rustls::TlsAcceptor>> = match (
        &state.cfg.tls.cert,
        &state.cfg.tls.key,
    ) {
        (Some(cert), Some(key)) => match load_tls_acceptor(cert, key) {
            Ok(acceptor) => {
                info!(cert = %cert, key = %key, "TLS 已启用(HTTPS，ALPN h2/http1.1)");
                Some(Arc::new(acceptor))
            }
            Err(e) if state.cfg.tls.allow_insecure_fallback => {
                warn!(error = %e, "TLS 证书加载失败，降级明文 HTTP(allow_insecure_fallback=true)");
                None
            }
            Err(e) => {
                error!(error = %e, "TLS 证书加载失败且 allow_insecure_fallback=false，拒绝启动");
                return Err(format!("TLS 证书加载失败: {e}").into());
            }
        },
        _ if state.cfg.tls.allow_insecure_fallback => {
            warn!("TLS 证书缺失，降级明文 HTTP(allow_insecure_fallback=true)");
            None
        }
        _ => {
            error!("TLS 证书缺失且 allow_insecure_fallback=false，拒绝启动");
            return Err("TLS 证书未配置(生产必须配置证书)".into());
        }
    };

    // tcp监听 + 优雅关闭信号，watch，停止accept + 连接层graceful，外部可注入测试
    let listener = TcpListener::bind(addr).await?;
    info!(tls = tls_acceptor.is_some(), "fms_rgw 监听就绪");
    let shutdown_rx = match external_shutdown {
        Some(rx) => rx,
        None => {
            // 内部通道，tx交由shutdown_signal任务持有(ctrl_c/SIGTERM时广播)
            let (tx, rx) = tokio::sync::watch::channel(());
            let state_for_shutdown = state.clone();
            tokio::spawn(async move { shutdown_signal(state_for_shutdown, tx).await });
            rx
        }
    };

    // Router是Clone，每连接clone一份服务注入客户端ip，限流/日志/代理依赖
    let builder = auto::Builder::new(TokioExecutor::new());
    let conn_sem = Arc::new(Semaphore::new(max_conns));
    let mut shutdown_rx = shutdown_rx;

    loop {
        tokio::select! {
            _ = shutdown_rx.changed() => break, // 关闭信号，停止accept
            accepted = listener.accept() => {
                let (stream, peer) = match accepted {
                    Ok(v) => v,
                    Err(e) => { warn!(error = %e, "accept 失败，继续监听"); continue; }
                };
                // 连接数上限保护(Semaphore兼作in-flight计数)
                let permit = match Arc::clone(&conn_sem).try_acquire_owned() {
                    Ok(p) => p,
                    Err(_) => {
                        warn!(peer = %peer, max_conns, "连接数已达上限，拒绝新连接");
                        drop(stream);
                        continue;
                    }
                };
                let router = router.clone();
                let builder = builder.clone();
                let tls_acceptor = tls_acceptor.clone();
                let shutdown_rx = shutdown_rx.clone();
                tokio::spawn(async move {
                    match &tls_acceptor {
                        Some(acceptor) => match acceptor.accept(stream).await {
                            // tls，alpn由握手协商，h2/http1.1，auto按协商结果选协议
                            Ok(tls_stream) => {
                                serve_connection(&builder, router, peer, shutdown_rx, TokioIo::new(tls_stream)).await;
                            }
                            Err(e) => {
                                warn!(peer = %peer, error = %e, "TLS 握手失败");
                            }
                        },
                        None => {
                            // 明文http(allow_insecure_fallback模式)，auto默认http1
                            serve_connection(&builder, router, peer, shutdown_rx, TokioIo::new(stream)).await;
                        }
                    }
                    drop(permit); // 释放连接计数
                });
            }
        }
    }

    // 停止accept，等待全部in-flight连接结束(最长graceful_shutdown_secs，超时强制)
    info!(grace_secs, "收到关闭信号，优雅关闭(停止接受新连接，等待 in-flight 请求)");
    let grace = tokio::time::sleep(Duration::from_secs(grace_secs));
    tokio::pin!(grace);
    let wait_all = conn_sem.acquire_many_owned(max_conns as u32);
    tokio::pin!(wait_all);
    tokio::select! {
        _ = wait_all.as_mut() => info!("全部 in-flight 连接已完成，退出"),
        _ = grace.as_mut() => warn!(grace_secs, "优雅关闭超时，强制退出"),
    }
    Ok(())
}

/// 单连接服务，注入客户端ip 后经 `TowerToHyperService` 适配，再由 auto `Builder`(alpn选协议，
/// upgrade路径保留ws升级)，watch关闭信号则连接层 `graceful_shutdown`，等in-flight完成。
///
/// 职责链，`ConnectInfoService`向每个请求扩展注入`ConnectInfo`，peer(等价axum
/// `into_make_service_with_connect_info`，限流/日志/代理取客户端ip依赖此注入，随后
/// `TowerToHyperService`将tower `Service`适配为hyper服务，再交 `auto::Builder`
/// `serve_connection_with_upgrades`(http1/http2按alpn协商结果自动选择，upgrade
/// 路径保留供ws升级101使用)。
///
/// 关闭语义，watch关闭信号到达时对当前连接发起`graceful_shutdown`(停止接收新
/// 请求，等待已in-flight请求写完响应后断开，ws长连接则由外层超时兜底)，
/// 与`serve_with_shutdown`的全局优雅关闭衔接，保证每连接都完成收尾。
///
/// `builder`，auto `Builder`，跨连接共享，内部连接池
///
/// `router`，已装配的`Router`(每连接`Clone`一份，`Router`是`Clone`的)
///
/// `peer`，客户端`SocketAddr`(注入`ConnectInfo`)
///
/// `shutdown_rx`，关闭信号接收端，changed即优雅关闭本连接
///
/// `io`，已建立的连接(tls握手后的`TlsStream`或明文`TcpStream`)
async fn serve_connection<I>(
    builder: &auto::Builder<TokioExecutor>,
    router: Router,
    peer: SocketAddr,
    mut shutdown_rx: tokio::sync::watch::Receiver<()>,
    io: TokioIo<I>,
) where
    I: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let service = ConnectInfoService {
        inner: router,
        peer,
    };
    let hyper_service = TowerToHyperService::new(service);
    let conn = builder.serve_connection_with_upgrades(io, hyper_service);
    tokio::pin!(conn);
    tokio::select! {
        _ = conn.as_mut() => {}
        _ = shutdown_rx.changed() => {
            // 关闭信号，当前连接优雅关闭，等in-flight请求完成
            conn.as_mut().graceful_shutdown();
            let _ = conn.await;
        }
    }
}

/// 每连接服务包装，向请求扩展注入`ConnectInfo`后转发(等价axum `into_make_service_with_connect_info`)
#[derive(Clone)]
struct ConnectInfoService {
    inner: Router,
    peer: SocketAddr,
}

impl tower::Service<axum::http::Request<Incoming>> for ConnectInfoService {
    type Response = Response<Body>;
    type Error = std::convert::Infallible;
    type Future = std::pin::Pin<
        Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>,
    >;

    fn poll_ready(
        &mut self,
        _cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Result<(), Self::Error>> {
        std::task::Poll::Ready(Ok(()))
    }

    fn call(&mut self, mut req: axum::http::Request<Incoming>) -> Self::Future {
        req.extensions_mut()
            .insert(axum::extract::ConnectInfo(self.peer));
        let mut inner = self.inner.clone();
        Box::pin(async move { inner.call(req).await })
    }
}

/// 加载PEM证书/私钥后构造 rustls `ServerConfig`，alpn h2/http1.1，再建 `TlsAcceptor`，
/// 证书空/私钥缺失/不匹配均属加载失败(调用方按`allow_insecure_fallback`分支处理)
fn load_tls_acceptor(
    cert_path: &str,
    key_path: &str,
) -> Result<tokio_rustls::TlsAcceptor, Box<dyn std::error::Error>> {
    let certs = rustls_pemfile::certs(&mut std::io::BufReader::new(std::fs::File::open(
        cert_path,
    )?))
    .collect::<Result<Vec<_>, _>>()?;
    if certs.is_empty() {
        return Err("证书文件不含任何 PEM 证书".into());
    }
    let key =
        rustls_pemfile::private_key(&mut std::io::BufReader::new(std::fs::File::open(key_path)?))?
            .ok_or_else(|| "私钥文件不含 PEM 私钥".to_string())?;
    let mut config = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)?;
    // alpn，h2优先，http/1.1兜底，hyper-util auto按协商结果自动选协议
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Ok(tokio_rustls::TlsAcceptor::from(Arc::new(config)))
}

/// 等待`ctrl_c`/SIGTERM后广播关闭信号，停止accept + 逐连接`graceful_shutdown`
/// (等待in-flight，超时`server.graceful_shutdown_secs`强制断开)，二次`ctrl_c`立即退出
pub async fn shutdown_signal(state: Arc<AppState>, shutdown_tx: tokio::sync::watch::Sender<()>) {
    let ctrl_c = async {
        if let Err(e) = tokio::signal::ctrl_c().await {
            warn!(error = %e, "ctrl_c 信号注册失败");
        }
    };
    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut sig) => {
                sig.recv().await;
            }
            Err(e) => warn!(error = %e, "SIGTERM 信号注册失败"),
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
    info!(grace_secs = state.cfg.server.graceful_shutdown_secs, "收到关闭信号，优雅关闭(等待 in-flight 请求)");
    // 二次ctrl_c强制退出
    tokio::spawn(async {
        if tokio::signal::ctrl_c().await.is_ok() {
            std::process::exit(0);
        }
    });
    // 广播关闭信号，serve停止accept + 各连接graceful_shutdown
    let _ = shutdown_tx.send(());
}

/// `/healthz`自检，200 {"status":"ok","version":"0.1.0","uptime_secs":N,"circuit":"CLOSED","ts":"RFC3339"}
pub async fn health_handler(State(state): State<Arc<AppState>>) -> Response<Body> {
    let body = serde_json::json!({
        "status": "ok",
        "version": env!("CARGO_PKG_VERSION"),
        "uptime_secs": state.started_at.elapsed().as_secs(),
        "circuit": match state.circuit.state() {
            CircuitState::Closed => "CLOSED",
            CircuitState::HalfOpen => "HALF-OPEN",
            CircuitState::Open => "OPEN",
        },
        "ts": chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
    });

    // 不变量，静态状态码 + 常量头名 + 合法json，构造不可能失败
    Response::builder()
        .status(StatusCode::OK)
        .header(CONTENT_TYPE, "application/json")
        .body(Body::from(body.to_string()))
        .expect("healthz 响应构造不可失败：静态头与合法 JSON")
}

#[cfg(test)]
mod tests {
    use super::*;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    #[tokio::test]
    async fn build_router_assembles_and_serves_healthz() {
        let state = state::test_state();
        let app = build_router(state);
        // 全链装配(compression最外层 + mw_access_log次外层 + cors + /healthz)下healthz可达
        let resp = app
            .oneshot(
                axum::http::Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .expect("oneshot");
        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[test]
    fn build_state_assembles_all_components() {
        let mut cfg = config::Config::default();
        cfg.auth.jwt_secret = "j".to_string();
        cfg.sign.sign_secret = "s".to_string();
        cfg.upstream.base_url = "http://127.0.0.1:8081".to_string();
        let state = build_state(cfg).expect("合法配置可组装 AppState");
        assert_eq!(state.cfg.auth.jwt_secret, "j");
        assert!(
            state
                .limiter
                .check("ip:t", 100, Duration::from_secs(60))
                .is_ok()
        );
        assert_eq!(state.circuit.state(), CircuitState::Closed);
        // 上游base_url非法则启动期报错，不等到请求期暴露
        let mut bad = config::Config::default();
        bad.upstream.base_url = "not a uri".to_string();
        assert!(build_state(bad).is_err(), "非法上游 URI 应在启动期失败");
    }

    #[test]
    fn validate_secret_strength_policy() {
        // 拒绝空值 / 占位值 / 过短 / 全同字符，接受32+ 随机密钥
        assert!(!validate_secret(""), "空值拒绝");
        assert!(!validate_secret("CHANGE_ME_JWT_SECRET"), "占位值拒绝");
        assert!(!validate_secret("CHANGE_ME_SIGN_SECRET"), "占位值拒绝");
        assert!(!validate_secret("short-key"), "过短拒绝");
        assert!(!validate_secret("a".repeat(40).as_str()), "全同字符拒绝");
        assert!(
            !validate_secret("change_me_1234567890abcdefghijklmnopqrs"),
            "change_me 子串拒绝"
        );
        assert!(
            validate_secret("k9f2mQ7vLx4pRt8sWz3nYb6cDe1hJg0aU"),
            "32+ 随机密钥接受"
        );
        assert!(
            validate_secret("test-jwt-secret-0123456789abcdef0123456789"),
            "普通强密钥接受"
        );
    }

    #[test]
    #[should_panic(expected = "CORS")]
    fn build_router_panics_on_empty_origin_whitelist() {
        // 空白名单 = 配置拒绝跨域，属启动期不变量错误则 panic，安全语义，拒绝而非通配
        let cfg = config::Config::default();
        let state = state::test_state_with(cfg);
        let _ = build_router(state);
    }

    #[tokio::test]
    async fn health_handler_reports_status_and_circuit() {
        let state = state::test_state();
        let app = Router::new()
            .route("/healthz", get(health_handler))
            .with_state(state);
        let resp = app
            .oneshot(
                axum::http::Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .expect("oneshot");
        assert_eq!(resp.status(), StatusCode::OK);
        assert_eq!(
            resp.headers()
                .get(CONTENT_TYPE)
                .and_then(|v| v.to_str().ok()),
            Some("application/json")
        );
        let body = resp
            .into_body()
            .collect()
            .await
            .expect("collect")
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["status"], "ok");
        assert_eq!(v["circuit"], "CLOSED", "新熔断器应为 CLOSED");
        assert_eq!(v["version"], env!("CARGO_PKG_VERSION"));
        assert!(v["uptime_secs"].as_u64().is_some());
        assert!(v["ts"].as_str().is_some_and(|s| !s.is_empty()));
    }
}
