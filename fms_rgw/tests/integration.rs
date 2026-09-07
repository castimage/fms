//! `tests/integration.rs`，中间件链集成测试(`tower::ServiceExt::oneshot`直调`build_router`)
//!
//! 覆盖，安全链顺序 log、cors、trace、filter、rate、auth、sign，再到 ws/proxy 分发，
//! - filter，路径穿越400、sql注入400、body超限413
//! - rate，ip维度429 + `Retry-After`
//! - auth，缺失401，40100、伪造密钥401，40100、过期40101、身份头注入 + `X-FMS-*`剥离
//! - sign，`X-FMS-Signature`存在且经`verify_signature_header`校验通过，body原样转发
//! - cors，白名单预检200，非白名单origin预检拒绝
//! - static，index/fallback/hash资源缓存头/dotfile 404
//! - proxy，响应透传，状态码/头/体+ 响应侧`X-Trace-Id`

mod common;

use axum::http::{Method, StatusCode};
use common::*;
use http_body_util::BodyExt;
use uuid::Uuid;

const SUB: &str = "3f9a2c1d-0000-4000-8000-000000000001";
const JWT_SECRET: &[u8] = b"test-jwt-secret";
const SIGN_SECRET: &[u8] = b"test-sign-secret";
/// 与`common::StaticFixture`写入的`index.html`内容标记一致
const INDEX_MARKER: &[u8] = b"INDEX-MARKER";

// healthz

#[tokio::test]
async fn healthz_returns_200_ok() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    let resp = oneshot(&router, request("GET", "/healthz")).await;
    let (status, headers, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::OK, "healthz 应返回 200");
    assert_eq!(body["status"], "ok", "healthz 应报告 ok");
    assert!(
        headers.get("x-trace-id").is_some(),
        "healthz 响应应带 X-Trace-Id"
    );
}

// filter层

#[tokio::test]
async fn filter_path_traversal_400() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    // .. 路径段与%2e%2e编码绕过均拒绝
    for uri in ["/api/../etc/passwd", "/api/gl/%2e%2e/%2e%2e/x"] {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "路径穿越应 400：{uri}");
        assert_eq!(body["code"], 40001, "路径穿越错误码应为 40001：{uri}");
        assert!(body["traceId"].is_string(), "错误响应应含 traceId");
    }
}

#[tokio::test]
async fn filter_sql_injection_400() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    // 明文与percent编码两种绕过
    for uri in [
        "/api/gl/vouchers?q=%27%20or%201=1%20--",
        "/api/gl/vouchers?q=union%20select",
    ] {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "SQL 注入应 400：{uri}");
        assert_eq!(body["code"], 40001);
    }
}

#[tokio::test]
async fn filter_body_too_large_413() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    // Content-Length声明超限，11MB > 10MB则 413，不等body，安全链sign层拦截
    let mut req = with_auth(
        request("POST", "/api/gl/vouchers"),
        &make_jwt(SUB, "operator", 3600, JWT_SECRET),
    );
    req.headers_mut()
        .insert("content-type", "application/json".parse().unwrap());
    req.headers_mut()
        .insert("content-length", "11534337".parse().unwrap());
    let resp = oneshot(&router, with_ip(req, "127.0.0.2")).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE, "声明超限应 413");
    assert_eq!(body["code"], 40001, "413 错误码应为 40001");
}

// rate层

#[tokio::test]
async fn rate_limit_429_with_retry_after() {
    let (router, mock) = router_with_mock(
        |cfg| {
            cfg.limits.ip_per_window = 3; // ip维度收紧到每窗3次
            cfg.limits.login_per_window = 100; // 排除登录维度干扰
        },
        None,
    )
    .await;
    for i in 0..3 {
        let resp = oneshot(
            &router,
            with_ip(request("GET", "/api/auth/login"), "127.0.0.3"),
        )
        .await;
        assert_eq!(
            resp.status(),
            StatusCode::OK,
            "第 {} 个请求应在限流额度内",
            i + 1
        );
    }
    // 第4个同ip请求则 429 + Retry-After
    let resp = oneshot(
        &router,
        with_ip(request("GET", "/api/auth/login"), "127.0.0.3"),
    )
    .await;
    let (status, headers, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "超限应 429");
    assert_eq!(body["code"], 42900);
    let retry: u64 = headers
        .get("retry-after")
        .expect("429 必带 Retry-After")
        .to_str()
        .unwrap()
        .parse()
        .expect("Retry-After 应为数字秒");
    assert!(retry >= 1, "Retry-After 最少 1s，实际 {retry}");
    // 前3个请求确实到了上游
    let hits = mock.take_filtered("/api/auth/login");
    assert_eq!(hits.len(), 3, "限流内请求应全部转发到上游");
}

// auth层

#[tokio::test]
async fn auth_missing_token_401() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    let resp = oneshot(&router, request("GET", "/api/gl/vouchers")).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED, "缺失 token 应 401");
    assert_eq!(body["code"], 40100);
}

#[tokio::test]
async fn auth_forged_key_401() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    // 用错误密钥签发的token，网关密钥为test-jwt-secret
    let token = make_jwt(SUB, "operator", 3600, b"wrong-secret");
    let resp = oneshot(
        &router,
        with_auth(request("GET", "/api/gl/vouchers"), &token),
    )
    .await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED, "伪造密钥应 401");
    assert_eq!(body["code"], 40100);
}

#[tokio::test]
async fn auth_expired_token_40101() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    let token = make_jwt(SUB, "operator", -3600, JWT_SECRET);
    let resp = oneshot(
        &router,
        with_auth(request("GET", "/api/gl/vouchers"), &token),
    )
    .await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED, "过期 token 应 401");
    assert_eq!(body["code"], 40101, "过期应返回 40101");
}

// auth + sign + proxy全链(含X-FMS-*剥离与注入、签名校验、body透传)

#[tokio::test]
async fn proxy_chain_injects_identity_and_valid_signature() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let sub = SUB;
    let token = make_jwt(sub, "operator", 3600, JWT_SECRET);
    let body = r#"{"action":"create","amount":100}"#;
    // 客户端伪造X-FMS-*，必须被剥离，不得直达上游
    let mut req = with_json_body("/api/gl/vouchers?page=2", body);
    req.headers_mut()
        .insert("x-fms-userid", "attacker".parse().unwrap());
    req.headers_mut()
        .insert("x-fms-role", "admin".parse().unwrap());
    req.headers_mut().insert(
        "x-fms-signature",
        "t=0&nonce=fake&sig=fake".parse().unwrap(),
    );
    let trace_id = Uuid::now_v7().to_string();
    req.headers_mut()
        .insert("x-trace-id", trace_id.parse().unwrap());
    let resp = oneshot(&router, with_ip(with_auth(req, &token), "127.0.0.4")).await;
    assert_eq!(resp.status(), StatusCode::OK, "有效 JWT 全链应 200");
    let _ = resp.into_body().collect().await.expect("响应体收集失败");

    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(hits.len(), 1, "应恰有一次上游转发");
    let up = &hits[0];
    // 方法与query原样转发
    assert_eq!(up.method, "POST", "方法应原样转发");
    assert_eq!(up.query.as_deref(), Some("page=2"), "query 应原样转发");
    // 身份注入(值严格取自Claims，伪造头被剥离)
    assert_eq!(
        up.header("x-fms-userid"),
        Some(sub),
        "上游应收到 Claims.sub"
    );
    assert_eq!(
        up.header("x-fms-role"),
        Some("operator"),
        "上游应收到 Claims.role"
    );
    // 签名头存在、格式合法且可对称校验
    let sig_header = up
        .header("x-fms-signature")
        .expect("上游应收到 X-FMS-Signature");
    assert!(
        sig_header.starts_with("t=")
            && sig_header.contains("&nonce=")
            && sig_header.contains("&sig="),
        "签名头应为 t=&nonce=&sig= 格式"
    );
    let body_hash = fms_rgw::sign::compute_body_hash(body.as_bytes());
    fms_rgw::sign::verify_signature_header(
        SIGN_SECRET,
        "POST",
        "/api/gl/vouchers",
        &body_hash,
        sig_header,
        common::now_secs(),
        sub,
        "operator",
    )
    .expect("X-FMS-Signature 应能通过对称校验(身份段 = 注入的 Claims 身份)");
    // 客户端注入的trace id原样透传
    assert_eq!(
        up.header("x-trace-id"),
        Some(trace_id.as_str()),
        "X-Trace-Id 应透传上游"
    );
    // body原样一致
    assert_eq!(up.body_str(), body, "请求体应原样转发上游");
}

#[tokio::test]
async fn proxy_passthrough_status_headers_and_trace() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    // 配置上游响应，201 + 自定义头 + 自定义体
    {
        let mut r = mock.response.lock().unwrap();
        r.status = 201;
        r.headers = vec![
            ("content-type".to_string(), "application/json".to_string()),
            ("x-upstream-mark".to_string(), "passthrough".to_string()),
        ];
        r.body = br#"{"upstream":"alive"}"#.to_vec();
    }
    let token = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    let resp = oneshot(
        &router,
        with_auth(request("GET", "/api/gl/vouchers"), &token),
    )
    .await;
    let (status, headers, body) = collect(resp).await;
    assert_eq!(status, StatusCode::CREATED, "上游状态码应透传");
    assert_eq!(
        headers.get("x-upstream-mark").and_then(|v| v.to_str().ok()),
        Some("passthrough")
    );
    assert_eq!(
        body.as_ref(),
        br#"{"upstream":"alive"}"#,
        "上游响应体应透传"
    );
    assert!(
        headers.get("x-trace-id").is_some(),
        "响应侧应补齐 X-Trace-Id"
    );
}

// cors

#[tokio::test]
async fn cors_preflight_allowed_origin_200() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    let req = RequestBuilder::new(Method::OPTIONS, "/api/gl/vouchers")
        .header("origin", "https://fms.example.com")
        .header("access-control-request-method", "POST")
        .header(
            "access-control-request-headers",
            "authorization, content-type",
        )
        .build();
    let resp = oneshot(&router, req).await;
    let (status, headers, _) = collect(resp).await;
    assert_eq!(status, StatusCode::OK, "白名单预检应 200");
    assert_eq!(
        headers
            .get("access-control-allow-origin")
            .and_then(|v| v.to_str().ok()),
        Some("https://fms.example.com"),
        "预检应回显白名单 origin"
    );
    assert_eq!(
        headers
            .get("access-control-allow-credentials")
            .and_then(|v| v.to_str().ok()),
        Some("true"),
        "allow_credentials 应为 true"
    );
}

#[tokio::test]
async fn cors_preflight_disallowed_origin_no_authorization() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    // tower-http CorsLayer对非白名单origin不拦截、直接放行，无403短路，
    // 但绝不回显cors授权头，浏览器据此阻止跨域读取，安全语义成立
    let req = RequestBuilder::new(Method::OPTIONS, "/api/gl/vouchers")
        .header("origin", "https://evil.example.com")
        .header("access-control-request-method", "POST")
        .build();
    let resp = oneshot(&router, req).await;
    let (status, headers, _) = collect(resp).await;
    assert_eq!(status, StatusCode::OK, "非白名单预检按普通请求放行");
    // 授权核心是allow-origin，无它浏览器即阻断跨域，allow-credentials单独出现无害
    assert!(
        !headers.contains_key("access-control-allow-origin"),
        "拒绝时不得回显 access-control-allow-origin"
    );
    // 普通跨域GET同样不得回显授权头
    let req = RequestBuilder::new(Method::GET, "/api/gl/vouchers")
        .header("origin", "https://evil.example.com")
        .build();
    let resp = oneshot(&router, req).await;
    let (_, headers, _) = collect(resp).await;
    assert!(
        !headers.contains_key("access-control-allow-origin"),
        "非白名单 GET 不得回显授权头"
    );
}

// static

#[tokio::test]
async fn static_index_and_cache_control() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    // 根路径，ServeDir直接服务index.html(append_index_html_on_directories)
    let resp = oneshot(&router, request("GET", "/")).await;
    let (status, headers, body) = collect(resp).await;
    assert_eq!(status, StatusCode::OK, "根路径应返回 index.html");
    assert!(
        body.starts_with(b"<!doctype html>"),
        "应输出 index.html 内容"
    );
    assert!(
        headers.contains_key("cache-control"),
        "静态响应应带 Cache-Control"
    );
    // 显式 /index.html，必须no-store，spa入口不缓存，更新必达
    let resp = oneshot(&router, request("GET", "/index.html")).await;
    let (status, headers, body) = collect(resp).await;
    assert_eq!(status, StatusCode::OK, "/index.html 应 200");
    assert!(
        body.windows(INDEX_MARKER.len()).any(|w| w == INDEX_MARKER),
        "/index.html 内容应正确"
    );
    assert_eq!(
        headers.get("cache-control").and_then(|v| v.to_str().ok()),
        Some("no-store"),
        "index.html 应 no-store"
    );
    assert!(
        headers.get("etag").is_some(),
        "index.html 应带 ETag(304 协商)"
    );
}

#[tokio::test]
async fn static_hashed_asset_immutable() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    let resp = oneshot(&router, request("GET", "/assets/app.3f9a2c1d.js")).await;
    let (status, headers, body) = collect(resp).await;
    assert_eq!(status, StatusCode::OK, "hash 资源应 200");
    assert_eq!(body.as_ref(), b"console.log('hashed')");
    assert_eq!(
        headers.get("cache-control").and_then(|v| v.to_str().ok()),
        Some("public, max-age=31536000, immutable"),
        "hash 资源应 immutable 1y"
    );
}

#[tokio::test]
async fn static_spa_fallback_serves_index() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    // 前端路由路径，文件不存在则 spa history fallback 返回 index.html
    let resp = oneshot(&router, request("GET", "/reports/vouchers/2026")).await;
    let (status, headers, body) = collect(resp).await;
    assert_eq!(status, StatusCode::OK, "SPA fallback 应 200");
    assert!(
        body.windows(INDEX_MARKER.len()).any(|w| w == INDEX_MARKER),
        "fallback 应返回 index.html 内容"
    );
    assert_eq!(
        headers.get("cache-control").and_then(|v| v.to_str().ok()),
        Some("no-store"),
        "fallback 的 index.html 应 no-store"
    );
}

#[tokio::test]
async fn static_dotfile_and_sensitive_404() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    for uri in ["/.env", "/.git/config", "/assets/.secret.txt"] {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::NOT_FOUND, "dotfile 应 404：{uri}");
        assert_eq!(body["code"], 40400, "404 错误码应为 40400：{uri}");
    }
}

/// 小型请求构造器，含头
struct RequestBuilder {
    req: axum::http::Request<axum::body::Body>,
}

impl RequestBuilder {
    fn new(method: Method, uri: &str) -> Self {
        Self {
            req: axum::http::Request::builder()
                .method(method)
                .uri(uri)
                .body(axum::body::Body::empty())
                .expect("测试请求构造失败"),
        }
    }

    fn header(mut self, name: &str, value: &str) -> Self {
        let hn: axum::http::header::HeaderName = name.parse().expect("测试头名构造失败");
        self.req
            .headers_mut()
            .insert(hn, value.parse().expect("测试头值构造失败"));
        self
    }

    fn build(self) -> axum::http::Request<axum::body::Body> {
        self.req
    }
}
