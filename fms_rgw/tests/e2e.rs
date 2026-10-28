//! `tests/e2e.rs`，端到端总体测试，真实端口启动网关 + mock上游
//!
//! 环境复用tests/common的`real_gateway_with`(`OnceLock`单例 + 专用多线程
//! 运行时 + mock上游 + 静态夹具 + 就绪轮询)，本文件仅保留e2e特有配置
//! tweak，login限流收紧做限流用例、密钥覆盖为e2e常量与hyper-util客户端助手。
//!
//! 用例并发安全，限流专项走login维度，其他用例不触碰该key，身份sub每用例唯一。

mod common;

use common::*;
use http_body_util::BodyExt;
use hyper::header::HeaderMap;
use hyper_util::client::legacy::Client;
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::rt::TokioExecutor;
use serde_json::Value;
use uuid::Uuid;

const JWT_SECRET: &[u8] = b"e2e-jwt-secret";
const SIGN_SECRET: &[u8] = b"e2e-sign-secret";
const INDEX_MARKER: &str = "INDEX-MARKER";
const RATE_LOGIN_LIMIT: u32 = 5;

/// 进程级单例真实网关，e2e专属tweak，login维度限流收紧做限流用例，
/// 密钥覆盖为e2e常量，供测试侧对称签发jwt / 验签`X-FMS-Signature`
fn env() -> &'static RealGw {
    real_gateway_with(|cfg| {
        cfg.limits.login_per_window = RATE_LOGIN_LIMIT;
        cfg.auth.jwt_secret = String::from_utf8_lossy(JWT_SECRET).into_owned();
        cfg.sign.sign_secret = String::from_utf8_lossy(SIGN_SECRET).into_owned();
    })
}

/// hyper-util遗留客户端，与网关上游同款连接池
async fn http_client() -> Client<HttpConnector, axum::body::Body> {
    Client::builder(TokioExecutor::new()).build(HttpConnector::new())
}

/// 发送请求并返回，status, headers, body
async fn call(
    client: &Client<HttpConnector, axum::body::Body>,
    method: &str,
    url: &str,
    headers: &[(&str, &str)],
    body: &[u8],
) -> (u16, HeaderMap, Vec<u8>) {
    let mut builder = axum::http::Request::builder().method(method).uri(url);
    for (k, v) in headers {
        builder = builder.header(*k, *v);
    }
    let req = builder
        .body(axum::body::Body::from(body.to_vec()))
        .expect("e2e 客户端请求构造失败");
    let resp = client.request(req).await.expect("e2e 请求发送失败");
    let (parts, body) = resp.into_parts();
    let bytes = body.collect().await.expect("e2e 响应体收集失败").to_bytes();
    (parts.status.as_u16(), parts.headers, bytes.to_vec())
}

/// 读取访问日志文件为jsonl行
fn read_log(env: &RealGw) -> Vec<Value> {
    let content = std::fs::read_to_string(&env.log_file).expect("e2e 日志文件读取失败");
    content
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| serde_json::from_str(l).expect("访问日志行应为合法 JSON"))
        .collect()
}

/// 轮询等待访问日志中出现指定`req_id`的条目(访问日志现为异步批量flush，条目最迟
/// `log.batch_interval_ms`，默认500ms落盘，需轮询，上限3s兜底慢环境)
async fn find_log_entry(env: &RealGw, req_id: &str) -> Value {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
    loop {
        let entries = read_log(env);
        if let Some(e) = entries.iter().find(|e| e["req_id"] == req_id) {
            return e.clone();
        }
        if std::time::Instant::now() >= deadline {
            let ids: Vec<&str> = entries
                .iter()
                .map(|e| e["req_id"].as_str().unwrap_or("?"))
                .collect();
            panic!("访问日志中找不到 req_id={req_id} 的条目；现有条目: {ids:?}")
        }
        tokio::time::sleep(std::time::Duration::from_millis(25)).await;
    }
}

// 用例

#[tokio::test]
async fn healthz_reports_ok() {
    let env = env();
    let client = http_client().await;
    let (status, headers, body) =
        call(&client, "GET", &format!("{}/healthz", env.base), &[], &[]).await;
    assert_eq!(status, 200, "healthz 应 200");
    let v: Value = serde_json::from_slice(&body).expect("healthz 响应应为 JSON");
    assert_eq!(v["status"], "ok");
    assert_eq!(v["circuit"], "CLOSED", "mock 上游健康，熔断器应 CLOSED");
    assert!(
        headers.get("x-trace-id").is_some(),
        "healthz 响应应带 X-Trace-Id"
    );
}

#[tokio::test]
async fn static_index_and_spa_fallback_200() {
    let env = env();
    let client = http_client().await;
    // 根路径，ServeDir直接服务index.html
    for path in ["/", "/client/route/deep"] {
        let (status, headers, body) =
            call(&client, "GET", &format!("{}{path}", env.base), &[], &[]).await;
        assert_eq!(status, 200, "{path} 应 200");
        assert!(
            body.windows(INDEX_MARKER.len())
                .any(|w| w == INDEX_MARKER.as_bytes()),
            "{path} 应返回 index.html 内容"
        );
        assert!(
            headers.contains_key("cache-control"),
            "{path} 应带 Cache-Control"
        );
    }
    // 显式 /index.html，no-store + ETag，spa入口不缓存，更新必达
    let (status, headers, _body) = call(
        &client,
        "GET",
        &format!("{}/index.html", env.base),
        &[],
        &[],
    )
    .await;
    assert_eq!(status, 200, "/index.html 应 200");
    assert_eq!(
        headers.get("cache-control").and_then(|v| v.to_str().ok()),
        Some("no-store"),
        "/index.html 应 no-store"
    );
    assert!(headers.get("etag").is_some(), "/index.html 应带 ETag");
    // hash资源，immutable 1y
    let (status, headers, _body) = call(
        &client,
        "GET",
        &format!("{}/assets/app.3f9a2c1d.js", env.base),
        &[],
        &[],
    )
    .await;
    assert_eq!(status, 200, "hash 资源应 200");
    assert_eq!(
        headers.get("cache-control").and_then(|v| v.to_str().ok()),
        Some("public, max-age=31536000, immutable"),
        "hash 资源应 immutable 1y"
    );
}

#[tokio::test]
async fn dotfile_and_sensitive_404() {
    let env = env();
    let client = http_client().await;
    for path in ["/.env", "/.git/config", "/assets/.hidden"] {
        let (status, _, body) =
            call(&client, "GET", &format!("{}{path}", env.base), &[], &[]).await;
        assert_eq!(status, 404, "{path} 应 404");
        let v: Value = serde_json::from_slice(&body).expect("404 响应应为 JSON");
        assert_eq!(v["code"], 40400, "{path} 错误码应 40400");
    }
}

#[tokio::test]
async fn valid_jwt_full_chain_200() {
    let env = env();
    let client = http_client().await;
    let sub = Uuid::now_v7().to_string();
    let token = make_jwt(&sub, "operator", 3600, JWT_SECRET);
    let trace_id = Uuid::now_v7().to_string();
    let body = r#"{"action":"ping","n":1}"#;
    let path = format!("/api/e2e/chain-{}", Uuid::now_v7());
    let (status, headers, resp_body) = call(
        &client,
        "POST",
        &format!("{}{path}", env.base),
        &[
            ("authorization", &format!("Bearer {token}")),
            ("content-type", "application/json"),
            ("x-trace-id", &trace_id),
        ],
        body.as_bytes(),
    )
    .await;
    assert_eq!(status, 200, "有效 JWT 全链路应 200");
    assert_eq!(
        resp_body, br#"{"ok":true,"from":"mock"}"#,
        "上游响应体应透传"
    );
    assert_eq!(
        headers.get("x-trace-id").and_then(|v| v.to_str().ok()),
        Some(trace_id.as_str()),
        "响应侧 X-Trace-Id 应与请求侧一致"
    );
    // mock上游收到身份/签名/trace，且body一致
    let hits = env.mock.take_filtered(&path);
    assert_eq!(hits.len(), 1, "应恰有一次上游转发");
    let up = &hits[0];
    assert_eq!(
        up.header("x-fms-userid"),
        Some(sub.as_str()),
        "上游应收到 X-FMS-UserId"
    );
    assert_eq!(
        up.header("x-fms-role"),
        Some("operator"),
        "上游应收到 X-FMS-Role"
    );
    assert_eq!(
        up.header("x-trace-id"),
        Some(trace_id.as_str()),
        "上游应收到 X-Trace-Id"
    );
    let sig = up
        .header("x-fms-signature")
        .expect("上游应收到 X-FMS-Signature");
    let body_hash = fms_rgw::sign::compute_body_hash(body.as_bytes());
    fms_rgw::sign::verify_signature_header(
        SIGN_SECRET,
        "POST",
        &path,
        &body_hash,
        sig,
        now_secs(),
        &sub,
        "operator",
    )
    .expect("上游收到的 X-FMS-Signature 应可对称校验(身份段 = 注入的 Claims 身份)");
    assert_eq!(up.body_str(), body, "请求体应原样转发");
    // 访问日志，req_id == trace_id
    let entry = find_log_entry(env, &trace_id).await;
    assert_eq!(entry["method"], "POST");
    assert_eq!(entry["status"], 200);
    assert_eq!(entry["user"], sub, "日志应记录 claims.sub");
}

#[tokio::test]
async fn forged_jwt_401() {
    let env = env();
    let client = http_client().await;
    let token = make_jwt(&Uuid::now_v7().to_string(), "operator", 3600, b"wrong-key");
    let (status, _, body) = call(
        &client,
        "GET",
        &format!("{}/api/gl/vouchers", env.base),
        &[("authorization", &format!("Bearer {token}"))],
        &[],
    )
    .await;
    assert_eq!(status, 401, "伪造密钥应 401");
    let v: Value = serde_json::from_slice(&body).expect("401 响应应为 JSON");
    assert_eq!(v["code"], 40100);
}

#[tokio::test]
async fn expired_jwt_40101() {
    let env = env();
    let client = http_client().await;
    let token = make_jwt(&Uuid::now_v7().to_string(), "operator", -3600, JWT_SECRET);
    let (status, _, body) = call(
        &client,
        "GET",
        &format!("{}/api/gl/vouchers", env.base),
        &[("authorization", &format!("Bearer {token}"))],
        &[],
    )
    .await;
    assert_eq!(status, 401, "过期 token 应 401");
    let v: Value = serde_json::from_slice(&body).expect("401 响应应为 JSON");
    assert_eq!(v["code"], 40101, "过期应返回 40101");
}

#[tokio::test]
async fn rate_limit_burst_429_with_retry_after() {
    let env = env();
    let client = http_client().await;
    // login维度收紧为每窗RATE_LOGIN_LIMIT次，其他用例不触碰login key，互不干扰
    let mut first_429: Option<(u16, HeaderMap)> = None;
    let mut all_ok = 0;
    let mut all_429 = 0;
    for i in 0..(RATE_LOGIN_LIMIT as usize + 3) {
        let (status, headers, _body) = call(
            &client,
            "GET",
            &format!("{}/api/auth/login", env.base),
            &[],
            &[],
        )
        .await;
        if status == 200 {
            all_ok += 1;
        } else if status == 429 {
            all_429 += 1;
            if first_429.is_none() {
                first_429 = Some((status, headers));
            }
        } else {
            panic!("第 {} 个请求意外状态 {status}(预期 200 或 429)", i + 1);
        }
    }
    assert_eq!(all_ok, RATE_LOGIN_LIMIT as usize, "限流额度内应全部 200");
    assert!(all_429 >= 1, "连打应触发 429");
    let (_status, headers) = first_429.expect("应存在 429 响应");
    let retry: u64 = headers
        .get("retry-after")
        .expect("429 必带 Retry-After")
        .to_str()
        .expect("Retry-After 应为 ASCII")
        .parse()
        .expect("Retry-After 应为数字秒");
    assert!(retry >= 1, "Retry-After 最少 1s，实际 {retry}");
    // 日志断言，429有对应jsonl条目，status=429，异步批量flush，轮询等待
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
    let mut seen_429 = false;
    while std::time::Instant::now() < deadline {
        if read_log(env).iter().any(|e| e["status"] == 429) {
            seen_429 = true;
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(25)).await;
    }
    assert!(seen_429, "访问日志应含 429 条目");
}

#[tokio::test]
async fn sql_injection_400() {
    let env = env();
    let client = http_client().await;
    let (status, _, body) = call(
        &client,
        "GET",
        &format!("{}/api/gl/vouchers?q=%27%20or%201=1%20--", env.base),
        &[],
        &[],
    )
    .await;
    assert_eq!(status, 400, "SQL 注入特征应 400");
    let v: Value = serde_json::from_slice(&body).expect("400 响应应为 JSON");
    assert_eq!(v["code"], 40001);
}

#[tokio::test]
async fn ws_query_token_redacted_in_access_log() {
    let env = env();
    let client = http_client().await;
    let token = make_jwt(&Uuid::now_v7().to_string(), "operator", 3600, JWT_SECRET);
    let trace_id = Uuid::now_v7().to_string();
    // 语义，非升级http请求访问/api/nt/ws必须携带jwt(Authorization)，
    // 本用例聚焦ws query token脱敏，用真实ws升级请求(Upgrade+Connection双头，
    // query携带token，mw_auth匿名放行、ws_handler验签后转发mock上游透传200)
    let (status, _h, _b) = call(
        &client,
        "GET",
        &format!("{}/api/nt/ws?token={token}", env.base),
        &[
            ("x-trace-id", &trace_id),
            ("upgrade", "websocket"),
            ("connection", "Upgrade"),
        ],
        &[],
    )
    .await;
    assert_eq!(status, 200, "WS 升级请求(有效 query token)应转发");
    let entry = find_log_entry(env, &trace_id).await;
    assert_eq!(
        entry["path"], "/api/nt/ws?token=***",
        "query token 必须脱敏"
    );
    // 整个日志文件不得出现原始token
    let raw = std::fs::read_to_string(&env.log_file).expect("日志文件读取失败");
    assert!(
        !raw.contains(&token),
        "有效 JWT 不得以明文形式出现在访问日志"
    );
}

#[tokio::test]
async fn log_jsonl_fields_and_security_headers() {
    let env = env();
    let client = http_client().await;
    let trace_id = Uuid::now_v7().to_string();
    let (status, headers, _b) = call(
        &client,
        "GET",
        &format!("{}/healthz", env.base),
        &[("x-trace-id", &trace_id), ("user-agent", "e2e-agent/1.0")],
        &[],
    )
    .await;
    assert_eq!(status, 200);
    // 安全响应头
    assert_eq!(
        headers.get("x-frame-options").and_then(|v| v.to_str().ok()),
        Some("SAMEORIGIN")
    );
    assert_eq!(
        headers
            .get("x-content-type-options")
            .and_then(|v| v.to_str().ok()),
        Some("nosniff")
    );
    assert!(
        headers.get("content-security-policy").is_some(),
        "应带 CSP 头"
    );
    // 访问日志jsonl，req_id/method/path/status/ua/ip字段齐全
    let entry = find_log_entry(env, &trace_id).await;
    assert_eq!(entry["method"], "GET");
    assert_eq!(entry["path"], "/healthz");
    assert_eq!(entry["status"], 200);
    assert_eq!(entry["ua"], "e2e-agent/1.0");
    assert_eq!(entry["ip"], "127.0.0.1", "对端 IP 应记录真实 IP");
    assert!(entry["req_id"].is_string());
    assert!(entry["ts"].is_string());
    assert!(entry["latency_ms"].is_number(), "应记录耗时");
}
