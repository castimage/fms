//! 安全响应头与`X-FMS-*`白名单重写，`strip_client_fms_headers`剥离客户端全部`X-FMS-*`头
//! (防伪造第一道闸，每个请求执行，含`/healthz`与静态)，`inject_identity`注入身份头
//! (值严格取自`Claims`)，`inject_trace`注入`X-Trace-Id`，`apply_security_headers`六类安全
//! 响应头，hsts仅tls时出现
//!
//! 调用关系，auth / ws / proxy / log模块
#![forbid(unsafe_code)]

use axum::body::Body;
use axum::http::Response;
use axum::http::header::{HeaderMap, HeaderName, HeaderValue};
use uuid::Uuid;

/// 剥离请求中客户端自带的全部`X-FMS-*`头，大小写不敏感前缀匹配
pub fn strip_client_fms_headers(headers: &mut HeaderMap) {
    // 先收集头名再统一移除，避免迭代中修改
    let names: Vec<HeaderName> = headers
        .keys()
        .filter(|k| k.as_str().to_ascii_lowercase().starts_with("x-fms-"))
        .cloned()
        .collect();
    for name in names {
        headers.remove(name);
    }
}

/// 注入身份头(仅auth验签通过后调用，值严格取自`Claims`，不取自任何客户端输入)
pub fn inject_identity(headers: &mut HeaderMap, user_id: &Uuid, role: &str) {
    // 构造失败即忽略，uuid/role恒为合法头值文本
    if let Ok(v) = HeaderValue::from_str(&user_id.to_string()) {
        headers.insert(X_FMS_USER_ID, v);
    }
    if let Ok(v) = HeaderValue::from_str(role) {
        headers.insert(X_FMS_ROLE, v);
    }
}

/// 注入`X-Trace-Id`: <`trace_id`>，空串，trace未采样/缺失兜底不注入，未采样请求
/// 不写空头(日志`req_id`与错误响应traceId亦为空串，全链路一致降噪)
pub fn inject_trace(headers: &mut HeaderMap, trace_id: &str) {
    if trace_id.is_empty() {
        return;
    }
    if let Ok(v) = HeaderValue::from_str(trace_id) {
        headers.insert(X_TRACE_ID, v);
    }
}

/// 统一安全响应头，对所有响应执行，含4xx/5xx，hsts仅在`is_tls`=true时注入
pub fn apply_security_headers(resp: &mut Response<Body>, is_tls: bool) {
    const CSP: &str = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; \
        img-src 'self' data: blob: https:; connect-src 'self' ws: wss:; font-src 'self'; \
        object-src 'none'; base-uri 'self'; frame-ancestors 'self'; form-action 'self'";
    resp.headers_mut()
        .insert(CONTENT_SECURITY_POLICY, HeaderValue::from_static(CSP));
    resp.headers_mut()
        .insert(X_FRAME_OPTIONS, HeaderValue::from_static("SAMEORIGIN"));
    resp.headers_mut()
        .insert(X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    resp.headers_mut().insert(
        REFERRER_POLICY,
        HeaderValue::from_static("strict-origin-when-cross-origin"),
    );
    resp.headers_mut().insert(
        PERMISSIONS_POLICY,
        HeaderValue::from_static("camera=(), microphone=(), geolocation=()"),
    );
    if is_tls {
        resp.headers_mut().insert(
            STRICT_TRANSPORT_SECURITY,
            HeaderValue::from_static("max-age=31536000; includeSubDomains"),
        );
    }
}

/// `X-FMS-UserId`头名(`sign.rs`取身份段参与hmac输入时复用)
pub(crate) const X_FMS_USER_ID: HeaderName = HeaderName::from_static("x-fms-userid");
/// `X-FMS-Role`头名(`sign.rs`取身份段参与hmac输入时复用)
pub(crate) const X_FMS_ROLE: HeaderName = HeaderName::from_static("x-fms-role");
const X_TRACE_ID: HeaderName = HeaderName::from_static("x-trace-id");
const CONTENT_SECURITY_POLICY: HeaderName = HeaderName::from_static("content-security-policy");
const X_FRAME_OPTIONS: HeaderName = HeaderName::from_static("x-frame-options");
const X_CONTENT_TYPE_OPTIONS: HeaderName = HeaderName::from_static("x-content-type-options");
const REFERRER_POLICY: HeaderName = HeaderName::from_static("referrer-policy");
const PERMISSIONS_POLICY: HeaderName = HeaderName::from_static("permissions-policy");
const STRICT_TRANSPORT_SECURITY: HeaderName = HeaderName::from_static("strict-transport-security");

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strip_removes_all_fms_headers_case_insensitive() {
        let mut h = HeaderMap::new();
        h.insert(X_FMS_USER_ID, HeaderValue::from_static("attacker"));
        h.insert("X-FMS-Role", HeaderValue::from_static("admin"));
        h.insert("x-fms-signature", HeaderValue::from_static("fake"));
        h.insert("X-FMS-Anything-Else", HeaderValue::from_static("x"));
        h.insert("authorization", HeaderValue::from_static("Bearer t"));
        h.insert("x-fmsx-not-fms", HeaderValue::from_static("keep"));
        strip_client_fms_headers(&mut h);
        assert!(!h.contains_key(X_FMS_USER_ID));
        assert!(!h.contains_key("x-fms-role"));
        assert!(!h.contains_key("x-fms-signature"));
        assert!(!h.contains_key("x-fms-anything-else"));
        assert!(h.contains_key("authorization"));
        assert!(h.contains_key("x-fmsx-not-fms"), "非 X-FMS- 前缀头保留");
    }

    #[test]
    fn inject_identity_sets_user_and_role() {
        let uid = Uuid::now_v7();
        let mut h = HeaderMap::new();
        inject_identity(&mut h, &uid, "operator");
        assert_eq!(
            h.get(X_FMS_USER_ID).and_then(|v| v.to_str().ok()),
            Some(uid.to_string().as_str())
        );
        assert_eq!(
            h.get(X_FMS_ROLE).and_then(|v| v.to_str().ok()),
            Some("operator")
        );
    }

    #[test]
    fn inject_trace_sets_header_and_drops_invalid() {
        let mut h = HeaderMap::new();
        inject_trace(&mut h, "abc-123");
        assert_eq!(
            h.get("x-trace-id").and_then(|v| v.to_str().ok()),
            Some("abc-123")
        );
        // 非法头值，含换行/控制字符则不注入(HeaderValue::from_str失败即忽略，不panic)
        let mut h2 = HeaderMap::new();
        inject_trace(&mut h2, "bad\nvalue");
        assert!(!h2.contains_key("x-trace-id"));
        // 空串，未采样/缺失兜底则不注入，不写空头
        let mut h3 = HeaderMap::new();
        inject_trace(&mut h3, "");
        assert!(!h3.contains_key("x-trace-id"), "空 traceId 不得注入");
    }

    #[test]
    fn security_headers_exact_values_and_hsts_only_when_tls() {
        let mut resp = Response::new(Body::empty());
        apply_security_headers(&mut resp, false);
        assert_eq!(
            resp.headers()
                .get("content-security-policy")
                .and_then(|v| v.to_str().ok()),
            Some(
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; connect-src 'self' ws: wss:; font-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'self'; form-action 'self'"
            )
        );
        assert_eq!(
            resp.headers()
                .get("x-frame-options")
                .and_then(|v| v.to_str().ok()),
            Some("SAMEORIGIN")
        );
        assert_eq!(
            resp.headers()
                .get("x-content-type-options")
                .and_then(|v| v.to_str().ok()),
            Some("nosniff")
        );
        assert_eq!(
            resp.headers()
                .get("referrer-policy")
                .and_then(|v| v.to_str().ok()),
            Some("strict-origin-when-cross-origin")
        );
        assert_eq!(
            resp.headers()
                .get("permissions-policy")
                .and_then(|v| v.to_str().ok()),
            Some("camera=(), microphone=(), geolocation=()")
        );
        assert!(
            !resp.headers().contains_key("strict-transport-security"),
            "明文不注入 HSTS"
        );
        // tls时注入hsts且值精确
        apply_security_headers(&mut resp, true);
        assert_eq!(
            resp.headers()
                .get("strict-transport-security")
                .and_then(|v| v.to_str().ok()),
            Some("max-age=31536000; includeSubDomains")
        );
    }
}
