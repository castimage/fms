//! 全局共享类型，纯叶子，无装配依赖，`TraceCtx`/`BufferedBody`由中间件写入extensions，
//! `GatewayError` 经 `error_response` 生成统一错误体{"code","message","data","traceId"}，
//! `mw_trace`为安全链最外层trace层，另含`trace_id_of` / `gen_trace_id` / 客户端ip提取
//!
//! 注，`AppState`与其测试构造在`state.rs`，装配根集中，本模块无环
#![forbid(unsafe_code)]

use axum::body::Body;
use axum::extract::State;
use axum::http::header::{CONTENT_TYPE, HeaderValue, RETRY_AFTER};
use axum::http::{Extensions, Request, Response, StatusCode};
use axum::middleware::Next;
use bytes::Bytes;
use serde_json::json;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use uuid::Uuid;

use crate::state::AppState;

/// 请求上下文，由`mw_trace`创建并写入`req.extensions`
#[derive(Clone)]
pub struct TraceCtx {
    pub trace_id: String,
    pub started: Instant,
}

/// 缓冲后的请求体，由`mw_sign`写入`req.extensions`，≤10MB，utf-8已校验
#[derive(Clone)]
pub struct BufferedBody(pub Bytes);

/// 统一错误类型映射响应，错误码对齐统一响应体约定
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GatewayError {
    BadRequest(String), // 400, code 40001参数错误
    Unauthorized,       // 401, code 40100未认证
    TokenExpired,       // 401, code 40101 token过期
    Forbidden(String), // 403, code 40300无权限(ws.rs ws Origin白名单拒绝时构造)
    NotFound(String),   // 404, code 40400不存在
    MethodNotAllowed,   // 405, code 40500方法不允许，TRACE等显式禁用
    PayloadTooLarge,    // 413, code 40001请求体超限
    RateLimited {
        retry_after_secs: u64,
    }, // 429, code 42900限流(必带Retry-After)
    CircuitOpen {
        retry_after_secs: u64,
    }, // 503, code 50300上游熔断
    UpstreamUnavailable, // 503, code 50000上游不可达
    Internal(String),   // 500, code 50000内部错误
}

impl GatewayError {
    fn status(&self) -> StatusCode {
        match self {
            GatewayError::BadRequest(_) => StatusCode::BAD_REQUEST,
            GatewayError::Unauthorized | GatewayError::TokenExpired => StatusCode::UNAUTHORIZED,
            GatewayError::Forbidden(_) => StatusCode::FORBIDDEN,
            GatewayError::NotFound(_) => StatusCode::NOT_FOUND,
            GatewayError::MethodNotAllowed => StatusCode::METHOD_NOT_ALLOWED,
            GatewayError::PayloadTooLarge => StatusCode::PAYLOAD_TOO_LARGE,
            GatewayError::RateLimited { .. } => StatusCode::TOO_MANY_REQUESTS,
            GatewayError::CircuitOpen { .. } | GatewayError::UpstreamUnavailable => {
                StatusCode::SERVICE_UNAVAILABLE
            }
            GatewayError::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    fn code(&self) -> i64 {
        match self {
            GatewayError::BadRequest(_) | GatewayError::PayloadTooLarge => 40001,
            GatewayError::Unauthorized => 40100,
            GatewayError::TokenExpired => 40101,
            GatewayError::Forbidden(_) => 40300,
            GatewayError::NotFound(_) => 40400,
            GatewayError::MethodNotAllowed => 40500,
            GatewayError::RateLimited { .. } => 42900,
            GatewayError::CircuitOpen { .. } => 50300,
            GatewayError::UpstreamUnavailable | GatewayError::Internal(_) => 50000,
        }
    }

    fn message(&self) -> String {
        match self {
            GatewayError::BadRequest(m)
            | GatewayError::Forbidden(m)
            | GatewayError::NotFound(m)
            | GatewayError::Internal(m) => m.clone(),
            GatewayError::MethodNotAllowed => "method not allowed".to_string(),
            GatewayError::Unauthorized => "未认证".to_string(),
            GatewayError::TokenExpired => "token 过期".to_string(),
            GatewayError::PayloadTooLarge => "请求体超限".to_string(),
            GatewayError::RateLimited { .. } => "rate limited".to_string(),
            GatewayError::CircuitOpen { .. } => "upstream circuit open".to_string(),
            GatewayError::UpstreamUnavailable => "上游不可达".to_string(),
        }
    }
}

/// 统一错误响应体，{code, message, data: null, traceId}四字段结构，data置null占位
/// 前端拦截器按四字段解析，所有中间件/处理器错误路径必须经此返回
/// 禁止外部输入路径裸panic / unwrap
pub fn error_response(err: GatewayError, trace_id: &str) -> Response<Body> {
    let status = err.status();
    let body = json!({
        "code": err.code(),
        "message": err.message(),
        "data": null,
        "traceId": trace_id,
    })
    .to_string();

    // 不变量，静态状态码 + 常量头名 + 合法json，构造不可能失败
    let mut resp = Response::builder()
        .status(status)
        .header(CONTENT_TYPE, "application/json")
        .body(Body::from(body))
        .expect("错误响应构造不可失败：静态头与合法 JSON");

    if let Some(retry_after_secs) = match &err {
        GatewayError::RateLimited { retry_after_secs }
        | GatewayError::CircuitOpen { retry_after_secs } => Some(*retry_after_secs),
        _ => None,
    } && let Ok(hv) = HeaderValue::from_str(&retry_after_secs.to_string())
    {
        resp.headers_mut().insert(RETRY_AFTER, hv);
    }
    resp
}

/// 生成uuid v7小写文本(`X-Trace-Id` / `req_id` / nonce复用)
pub fn gen_trace_id() -> String {
    Uuid::now_v7().to_string()
}

/// trace确定性采样序号，全局共享，跨请求单调递增，seq % 100 < `sample_rate`才采样，
/// 非随机数、审计可复现，与`log.sample_rate`独立，trace为百分比语义，100=全量
static TRACE_SEQ: AtomicU64 = AtomicU64::new(0);

/// 请求级采样判定槽，`mw_access_log`，全局最外层最先执行并写入extensions，
/// `mw_trace`读取复用，同一请求前后判定一致，避免各层各自取序号导致判定漂移
#[derive(Clone, Copy)]
pub struct TraceSampled(pub bool);

/// 纯采样判定，不消耗序号，供测试精确断言，seq % 100 < rate(rate为百分比0-100，
/// >100视为100全量、0恒不采样)，任意100个连续序号恰有rate个命中
pub fn trace_sampled_at(seq: u64, rate: u32) -> bool {
    seq % 100 < u64::from(rate.min(100))
}

/// 确定性采样判定，百分比语义，`sample_rate` ≥ 100全量，不消耗序号，否则消费全局
/// 序号seq，seq % 100 < rate才采样。非随机数、审计可复现，与`log.sample_rate`独立
/// trace为百分比语义，100=全量
pub fn trace_sampled(sample_rate: u32) -> bool {
    if sample_rate >= 100 {
        return true;
    }
    trace_sampled_at(TRACE_SEQ.fetch_add(1, Ordering::Relaxed), sample_rate)
}

/// 提取客户端ip，取连接对端地址(`ConnectInfo`，不可伪造)，绝不信赖客户端可控的
/// `X-Forwarded-For`，伪造xff可绕过ip限流，反向代理部署由前置代理重写`X-Real-IP`，本网关不解析
pub fn extract_client_ip(req: &Request<Body>) -> Option<String> {
    extract_client_ip_from_ext(req.extensions())
}

/// 从extensions读取对端地址，req拆分为parts后extensions仍在parts上
pub(crate) fn extract_client_ip_from_ext(ext: &Extensions) -> Option<String> {
    ext.get::<axum::extract::connect_info::ConnectInfo<std::net::SocketAddr>>()
        .map(|ci| ci.0.ip().to_string())
        .filter(|s| !s.is_empty())
}

/// 访问日志用户槽，请求级共享状态，`mw_access_log`先于`mw_auth`执行，无法直接读
/// `Claims`，故next前置槽于extensions，`mw_auth`验签后写入`claims.sub`，返回后`access_log`读取
#[derive(Clone, Default)]
pub(crate) struct LogUserSlot(pub Arc<std::sync::Mutex<Option<String>>>);

/// 从请求扩展取`trace_id`(`mw_trace`注入)，缺失时兜底空串(trace降采样下未采样请求
/// 不生成新id，日志`req_id`与错误响应traceId为空)，各中间件/处理器统一复用
pub fn trace_id_of(req: &Request<Body>) -> String {
    req.extensions()
        .get::<TraceCtx>()
        .map(|t| t.trace_id.clone())
        .unwrap_or_default()
}

/// 当前unix秒(`sign.rs`生产使用，auth/ws测试复用)
pub(crate) fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_secs() as i64
}

/// 中间件，安全链最外层，traceId确定性采样 + 生成/透传 + 计时。采样判定
/// (`trace.sample_rate`百分比语义，默认100%全量采样)由全局最外层的`mw_access_log`
/// 先行判定并经extensions.`TraceSampled`共享，本层读取复用，采样的请求，合法uuid
/// 的`X-Trace-Id`透传复用，否则生成新uuid，注入`TraceCtx`并写回请求头，未采样请求，
/// 不透传不生成，移除客户端旧值，防旧traceId穿到上游/日志，`X-Trace-Id`不注入、
/// `TraceCtx`.`trace_id`为空串(日志`req_id` / 错误响应traceId兜底为空)
pub async fn mw_trace(
    State(state): State<Arc<AppState>>,
    mut req: Request<Body>,
    next: Next,
) -> Response<Body> {
    // 采样判定，mw_access_log已判定则复用，同一次请求判定一致，否则自行判定，防御性兜底
    let sampled = req
        .extensions()
        .get::<TraceSampled>()
        .map(|s| s.0)
        .unwrap_or_else(|| trace_sampled(state.cfg.trace.sample_rate));

    let trace_id = if sampled {
        let id = req
            .headers()
            .get("x-trace-id")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| Uuid::parse_str(s).ok())
            .map(|u| u.to_string())
            .unwrap_or_else(gen_trace_id);
        if let Ok(hv) = HeaderValue::from_str(&id) {
            req.headers_mut().insert("x-trace-id", hv);
        }
        id
    } else {
        // 未采样，不透传不生成，移除客户端旧值，防旧traceId穿到上游/日志
        req.headers_mut().remove("x-trace-id");
        String::new()
    };

    let ctx = TraceCtx {
        trace_id,
        started: Instant::now(),
    };
    // TraceCtx写入extensions供内层读取
    req.extensions_mut().insert(ctx);
    next.run(req).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::state::test_state_with;
    use axum::http::header::RETRY_AFTER;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    #[tokio::test]
    async fn error_response_status_and_code_mapping() {
        // 429限流，带Retry-After
        let resp = error_response(
            GatewayError::RateLimited {
                retry_after_secs: 7,
            },
            "t1",
        );
        assert_eq!(resp.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(
            resp.headers()
                .get(RETRY_AFTER)
                .and_then(|v| v.to_str().ok()),
            Some("7")
        );
        let body = resp
            .into_body()
            .collect()
            .await
            .expect("body collect")
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 42900);
        assert_eq!(v["traceId"], "t1");
        // 错误响应必须含data:null占位，四字段统一结构
        assert!(
            v.get("data").is_some(),
            "错误响应必须携带 data 字段(null 占位)"
        );
        assert!(v["data"].is_null(), "错误路径 data 必须为 null");
        // 401未认证 / token过期
        assert_eq!(
            error_response(GatewayError::Unauthorized, "t").status(),
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            error_response(GatewayError::TokenExpired, "t").status(),
            StatusCode::UNAUTHORIZED
        );
        // 503熔断 / 上游不可达
        assert_eq!(
            error_response(
                GatewayError::CircuitOpen {
                    retry_after_secs: 5
                },
                "t"
            )
            .status(),
            StatusCode::SERVICE_UNAVAILABLE
        );
        assert_eq!(
            error_response(GatewayError::UpstreamUnavailable, "t").status(),
            StatusCode::SERVICE_UNAVAILABLE
        );
        // 405，TRACE等显式禁用方法
        assert_eq!(
            error_response(GatewayError::MethodNotAllowed, "t").status(),
            StatusCode::METHOD_NOT_ALLOWED
        );
        // 413 / 500 / 404
        assert_eq!(
            error_response(GatewayError::PayloadTooLarge, "t").status(),
            StatusCode::PAYLOAD_TOO_LARGE
        );
        assert_eq!(
            error_response(GatewayError::Internal("x".to_string()), "t").status(),
            StatusCode::INTERNAL_SERVER_ERROR
        );
        assert_eq!(
            error_response(GatewayError::NotFound("x".to_string()), "t").status(),
            StatusCode::NOT_FOUND
        );
    }

    #[test]
    fn gen_trace_id_is_uuid_v4() {
        let id = gen_trace_id();
        assert_eq!(id.len(), 36);
        assert!(Uuid::parse_str(&id).is_ok());
        assert_ne!(id, gen_trace_id());
    }

    #[test]
    fn trace_sampled_at_percentage_semantics() {
        // rate=100全量采样，任意序号都命中，rate>100视为100，配置防御
        assert!(trace_sampled_at(0, 100) && trace_sampled_at(99, 100) && trace_sampled_at(12345, 100));
        assert!(trace_sampled_at(77, 500), "rate>100 视为全量");
        // rate=0视为禁用trace记录，全不采样
        for seq in 0..1000 {
            assert!(!trace_sampled_at(seq, 0), "rate=0 必须全不采样");
        }
        // rate=10，任意100个连续序号恰10个命中，0,10,...,90，确定性可复现
        let hit10: u64 = (0..100).filter(|&s| trace_sampled_at(s, 10)).count() as u64;
        assert_eq!(hit10, 10, "rate=10 每 100 序号恰 10 命中");
        let hit25: u64 = (0..100).filter(|&s| trace_sampled_at(s, 25)).count() as u64;
        assert_eq!(hit25, 25, "rate=25 每 100 序号恰 25 命中");
        // 与序号起点无关，偏移100后仍是同样的命中个数
        let shifted: u64 = (100..200).filter(|&s| trace_sampled_at(s, 10)).count() as u64;
        assert_eq!(shifted, 10);
    }

    #[test]
    fn trace_sampled_consumes_global_seq_deterministically() {
        // rate=10消费全局序号，并发下各线程取不同序号，率10上下界统计断言，
        // rate=100短路不消耗序号恒真，全量路径零开销
        assert!(trace_sampled(100), "rate=100 恒采样");
        let mut hit = 0;
        let n = 2000;
        for _ in 0..n {
            if trace_sampled(10) {
                hit += 1;
            }
        }
        // 序号被并发测试交错消费仍为确定性取模，比例应在10% ± 容差内
        let ratio = hit as f64 / n as f64;
        assert!(
            (0.05..=0.15).contains(&ratio),
            "rate=10 采样比例应 ≈10%，实际 {ratio}"
        );
    }

    #[tokio::test]
    async fn mw_trace_not_sampled_leaves_trace_id_empty() {
        // rate=0，禁用trace记录，不透传客户端合法uuid、不生成新id，
        // TraceCtx.trace_id空串(日志req_id / 错误响应traceId兜底空串)
        let mut cfg = Config::default();
        cfg.auth.jwt_secret = "test-jwt-secret".to_string();
        cfg.sign.sign_secret = "test-sign-secret".to_string();
        cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
        cfg.trace.sample_rate = 0;
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .route("/x", axum::routing::get(echo_trace))
            .route_layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_trace,
            ))
            .with_state(state);
        let req = Request::builder()
            .uri("/x")
            .header("x-trace-id", Uuid::now_v7().to_string())
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.expect("oneshot");
        let body = resp
            .into_body()
            .collect()
            .await
            .expect("collect")
            .to_bytes();
        assert_eq!(
            String::from_utf8(body.to_vec()).unwrap(),
            "",
            "未采样 TraceCtx.trace_id 必须为空串(含客户端旧值剔除)"
        );
    }

    #[tokio::test]
    async fn mw_trace_reuses_outer_sampling_decision() {
        // 采样判定由外层mw_access_log先行写入extensions.TraceSampled，本层只复用，
        // 同一次请求前后判定一致，本用例注入false断言未采样路径
        let app = trace_router(); // rate=100，但TraceSampled，false 优先
        let mut req = Request::builder()
            .uri("/x")
            .header("x-trace-id", Uuid::now_v7().to_string())
            .body(Body::empty())
            .unwrap();
        req.extensions_mut().insert(TraceSampled(false));
        let resp = app.clone().oneshot(req).await.expect("oneshot");
        let body = resp
            .into_body()
            .collect()
            .await
            .expect("collect")
            .to_bytes();
        assert_eq!(
            String::from_utf8(body.to_vec()).unwrap(),
            "",
            "复用外层未采样判定：不透传客户端 traceId"
        );
    }

    #[test]
    fn extract_client_ip_uses_connect_info_not_xff() {
        // 无ConnectInfo 则返回 None，绝不回退xff
        let req = Request::builder().uri("/x").body(Body::empty()).unwrap();
        assert_eq!(extract_client_ip(&req), None);
        // 注入ConnectInfo 则返回对端 ip
        let addr: std::net::SocketAddr = "10.0.0.8:54321".parse().unwrap();
        let mut req2 = Request::builder().uri("/x").body(Body::empty()).unwrap();
        req2.extensions_mut()
            .insert(axum::extract::connect_info::ConnectInfo(addr));
        // 即使携带伪造xff也不影响，限流key用对端地址
        req2.headers_mut()
            .insert("x-forwarded-for", HeaderValue::from_static("1.2.3.4"));
        assert_eq!(extract_client_ip(&req2), Some("10.0.0.8".to_string()));
    }

    /// 从extensions读取`TraceCtx`并回显`trace_id`，验证中间件注入
    async fn echo_trace(req: Request<Body>) -> String {
        req.extensions()
            .get::<TraceCtx>()
            .map(|t| t.trace_id.clone())
            .unwrap_or_else(|| "no-trace-ctx".to_string())
    }

    fn trace_router() -> axum::Router {
        let mut cfg = Config::default();
        cfg.auth.jwt_secret = "test-jwt-secret".to_string();
        cfg.sign.sign_secret = "test-sign-secret".to_string();
        cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
        // 透传/生成用例须全量采样，默认10%会随机性漏采样
        cfg.trace.sample_rate = 100;
        let state = test_state_with(cfg);
        axum::Router::new()
            .route("/x", axum::routing::get(echo_trace))
            .route_layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_trace,
            ))
            .with_state(state)
    }

    /// 验证trace头被写入请求侧，handler内可见，中间件只写请求头，
    /// 响应侧`X-Trace-Id`由`mw_access_log` / proxy / ws负责
    #[tokio::test]
    async fn mw_trace_passthrough_valid_uuid() {
        let app = trace_router();
        let trace_id = Uuid::now_v7().to_string();
        let req = Request::builder()
            .uri("/x")
            .header("x-trace-id", &trace_id)
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.expect("oneshot");
        let body = resp
            .into_body()
            .collect()
            .await
            .expect("collect")
            .to_bytes();
        // 合法uuid透传复用(handler从TraceCtx读到同一值)
        assert_eq!(String::from_utf8(body.to_vec()).unwrap(), trace_id);
    }

    #[tokio::test]
    async fn mw_trace_generates_new_for_invalid_or_missing() {
        let app = trace_router();
        // 非法uuid则生成新 uuid
        let req = Request::builder()
            .uri("/x")
            .header("x-trace-id", "not-a-uuid")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.expect("oneshot");
        let body = resp
            .into_body()
            .collect()
            .await
            .expect("collect")
            .to_bytes();
        let trace_id = String::from_utf8(body.to_vec()).unwrap();
        assert_ne!(trace_id, "not-a-uuid");
        assert!(Uuid::parse_str(&trace_id).is_ok(), "必须生成合法 uuid");
        // 缺失则同样生成
        let req2 = Request::builder().uri("/x").body(Body::empty()).unwrap();
        let resp2 = app.clone().oneshot(req2).await.expect("oneshot");
        let body2 = resp2
            .into_body()
            .collect()
            .await
            .expect("collect")
            .to_bytes();
        let trace2 = String::from_utf8(body2.to_vec()).unwrap();
        assert!(Uuid::parse_str(&trace2).is_ok());
        assert_ne!(trace2, trace_id, "每次请求应生成不同 trace_id");
    }
}
