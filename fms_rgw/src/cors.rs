//! cors白名单层构造，仅放行`allowed_origins`白名单origin，不支持通配符，空列表全拒绝，
//! `allow_credentials`=true配合显式白名单(禁止`*`)，预检OPTIONS在cors层短路
//! (位于`mw_access_log`之内，预检仍会被记录访问日志)。
//! 调用关系，`main::build_router`构造`Router`时调用
#![forbid(unsafe_code)]

use crate::config::CorsCfg;
use axum::http::Method;
use axum::http::header::{HeaderName, HeaderValue};
use std::time::Duration;
use thiserror::Error;
use tower_http::cors::CorsLayer;

/// cors配置错误
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum CorsError {
    /// `allowed_origins`为空，= 拒绝全部跨域，配置层面明确拒绝而不是通配
    #[error("allowed_origins 为空(CORS 白名单不能为空列表)")]
    EmptyOriginList,
    /// 方法/头/origin字符串非法(无法转为`HeaderValue` / `HeaderName` / `Method`)
    #[error("非法 CORS 配置项：{0}")]
    InvalidHeader(String),
}

/// 构造cors层(tower-http `CorsLayer`，白名单模式，`allow_credentials`=true)
pub fn cors_layer(cfg: &CorsCfg) -> Result<CorsLayer, CorsError> {
    if cfg.allowed_origins.is_empty() {
        return Err(CorsError::EmptyOriginList);
    }

    let origins: Vec<HeaderValue> = cfg
        .allowed_origins
        .iter()
        .map(|o| HeaderValue::from_str(o).map_err(|_| CorsError::InvalidHeader(o.clone())))
        .collect::<Result<_, _>>()?;
    // tower-http的AllowMethods实现From<Vec<Method>>，直接转换即可
    let methods: Vec<Method> = cfg
        .allowed_methods
        .iter()
        .map(|m| m.parse().map_err(|_| CorsError::InvalidHeader(m.clone())))
        .collect::<Result<_, CorsError>>()?;
    let headers: Vec<HeaderName> = cfg
        .allowed_headers
        .iter()
        .map(|h| {
            HeaderName::from_bytes(h.as_bytes()).map_err(|_| CorsError::InvalidHeader(h.clone()))
        })
        .collect::<Result<_, _>>()?;
    let expose: Vec<HeaderName> = cfg
        .expose_headers
        .iter()
        .map(|h| {
            HeaderName::from_bytes(h.as_bytes()).map_err(|_| CorsError::InvalidHeader(h.clone()))
        })
        .collect::<Result<_, _>>()?;

    // 白名单模式 + allow_credentials=true
    Ok(CorsLayer::new()
        .allow_origin(origins)
        .allow_methods(methods)
        .allow_headers(headers)
        .expose_headers(expose)
        .allow_credentials(true)
        .max_age(Duration::from_secs(cfg.max_age_secs)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::Request;
    use tower::ServiceExt;

    #[test]
    fn empty_origin_list_is_rejected() {
        let cfg = CorsCfg::default();
        assert!(matches!(cors_layer(&cfg), Err(CorsError::EmptyOriginList)));
    }

    #[test]
    fn invalid_origin_rejected() {
        let cfg = CorsCfg {
            allowed_origins: vec!["http://ok.example".to_string(), "not a header\x01value".to_string()],
            ..Default::default()
        };
        assert!(matches!(cors_layer(&cfg), Err(CorsError::InvalidHeader(_))));
    }

    #[test]
    fn valid_config_builds_layer() {
        let cfg = CorsCfg {
            allowed_origins: vec!["https://fms.example.com".to_string()],
            allowed_methods: vec!["GET".to_string(), "POST".to_string()],
            ..Default::default()
        };
        assert!(cors_layer(&cfg).is_ok());
    }

    #[test]
    fn invalid_method_or_header_rejected() {
        let mut cfg = CorsCfg {
            allowed_origins: vec!["https://fms.example.com".to_string()],
            allowed_methods: vec!["GE T".to_string()], // 非法方法token，含空格
            ..Default::default()
        };
        assert!(matches!(cors_layer(&cfg), Err(CorsError::InvalidHeader(_))));
        cfg.allowed_methods = vec!["GET".to_string()];
        cfg.allowed_headers = vec!["bad header\nname".to_string()]; // 非法头名，含控制字符
        assert!(matches!(cors_layer(&cfg), Err(CorsError::InvalidHeader(_))));
    }

    fn behavior_cfg() -> CorsCfg {
        CorsCfg {
            allowed_origins: vec!["https://fms.example.com".to_string()],
            allowed_methods: vec!["GET".to_string(), "POST".to_string()],
            allowed_headers: vec!["Authorization".to_string(), "X-Trace-Id".to_string()],
            expose_headers: vec!["X-Trace-Id".to_string()],
            max_age_secs: 3600,
        }
    }

    fn cors_router() -> axum::Router {
        let layer = cors_layer(&behavior_cfg()).expect("测试配置合法");
        axum::Router::new()
            .route("/x", axum::routing::get(|| async { "ok" }))
            .layer(layer)
    }

    #[tokio::test]
    async fn preflight_allowed_origin_gets_cors_headers() {
        let app = cors_router();
        let req = Request::builder()
            .method("OPTIONS")
            .uri("/x")
            .header("origin", "https://fms.example.com")
            .header("access-control-request-method", "POST")
            .header("access-control-request-headers", "Authorization")
            .body(axum::body::Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.expect("oneshot");
        assert_eq!(
            resp.headers()
                .get("access-control-allow-origin")
                .and_then(|v| v.to_str().ok()),
            Some("https://fms.example.com"),
            "白名单 origin 预检必须放行并回显 origin"
        );
        assert_eq!(
            resp.headers()
                .get("access-control-allow-credentials")
                .and_then(|v| v.to_str().ok()),
            Some("true"),
            "allow_credentials 必须为 true"
        );
        let methods = resp.headers().get_all("access-control-allow-methods");
        let methods = methods
            .iter()
            .filter_map(|v| v.to_str().ok())
            .collect::<Vec<_>>()
            .join(", ");
        assert!(
            methods.contains("POST") && methods.contains("GET"),
            "预检应返回允许方法，实际 {methods}"
        );
        assert_eq!(
            resp.headers()
                .get("access-control-max-age")
                .and_then(|v| v.to_str().ok()),
            Some("3600"),
            "预检应带 max-age"
        );
    }

    #[tokio::test]
    async fn preflight_disallowed_origin_gets_no_cors_headers() {
        let app = cors_router();
        let req = Request::builder()
            .method("OPTIONS")
            .uri("/x")
            .header("origin", "https://evil.example.com")
            .header("access-control-request-method", "POST")
            .body(axum::body::Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.expect("oneshot");
        // 浏览器跨域放行的决定性依据是Allow-Origin回显，缺该头即跨域失败
        // tower-http仍会设置allow-credentials/allow-methods，但浏览器一律拒绝
        assert!(
            !resp.headers().contains_key("access-control-allow-origin"),
            "非白名单 origin 不得获得 allow-origin(跨域失败)"
        );
    }

    #[tokio::test]
    async fn actual_request_allowed_origin_exposes_headers() {
        let app = cors_router();
        let req = Request::builder()
            .method("GET")
            .uri("/x")
            .header("origin", "https://fms.example.com")
            .body(axum::body::Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.expect("oneshot");
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        assert_eq!(
            resp.headers()
                .get("access-control-allow-origin")
                .and_then(|v| v.to_str().ok()),
            Some("https://fms.example.com")
        );
        let expose = resp
            .headers()
            .get("access-control-expose-headers")
            .and_then(|v| v.to_str().ok());
        assert!(
            expose.is_some_and(|s| s.contains("X-Trace-Id") || s.contains("x-trace-id")),
            "应暴露 X-Trace-Id，实际 {expose:?}"
        );
    }
}
