//! `tests/redteam_retest.rs`，安全复审，红队视角，真实tcp请求
//!
//! 独立于`security_web.rs`的复测用例，自建真实网关(真实tcp监听 + mock上游 +
//! 自控静态目录含`a.sql/package.json`)，用`raw_http`发送原始http请求，
//! 逐项复测拦截结果，打印实际状态码与证据。
//!
//! 运行，cargo test --test `redteam_retest` -- --nocapture --test-threads=1

mod common;

use common::*;
use fms_rgw::config::Config;
use std::sync::Arc;

const JWT_SECRET: &[u8] = b"retest-jwt-secret";
const SUB: &str = "3f9a2c1d-0000-4000-8000-000000000002";
const INDEX_MARKER: &str = "INDEX-MARKER";

/// 自建真实网关，随机端口 + mock上游 + 自控静态目录(含`a.sql` / `package.json`)
struct RetestGw {
    port: u16,
    mock: MockUpstream,
    _fixture: StaticFixture,
}

async fn start_gw() -> RetestGw {
    let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("端口探测失败");
    let port = probe.local_addr().expect("端口探测失败").port();
    drop(probe);
    let mock = MockUpstream::start().await;
    let fixture = StaticFixture::new();
    // 按复测项精确放置a.sql(StaticFixture已有secret.sql/db/backup.sql/package.json)
    std::fs::write(
        fixture.dir.join("a.sql"),
        "SELECT secret FROM app; -- retest-a-sql",
    )
    .expect("写 a.sql 失败");
    let mut cfg = Config::default();
    cfg.server.listen = format!("127.0.0.1:{port}");
    cfg.tls.allow_insecure_fallback = true;
    cfg.upstream.base_url = mock.base_url.clone();
    cfg.auth.jwt_secret = String::from_utf8_lossy(JWT_SECRET).into_owned();
    cfg.sign.sign_secret = "retest-sign-secret".to_string();
    cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
    cfg.limits.ip_per_window = 1_000_000;
    cfg.limits.user_per_window = 1_000_000;
    cfg.limits.login_per_window = 1_000_000;
    cfg.static_cfg.dir = fixture.dir.display().to_string();
    cfg.log.access_file = None;
    let state = fms_rgw::build_state(cfg).expect("AppState 构建失败");
    tokio::spawn(async move {
        let _ = fms_rgw::serve(state).await;
    });
    for _ in 0..100 {
        let (status, _) = raw_http_probe(
            port,
            b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
        )
        .await;
        if status == 200 {
            return RetestGw {
                port,
                mock,
                _fixture: fixture,
            };
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    panic!("网关 10s 未就绪");
}

/// 原始http请求，返回，状态码, 响应体全文
async fn raw_req(port: u16, raw: &[u8]) -> (u16, String) {
    let (status, bytes) = raw_http(port, raw).await;
    (status, String::from_utf8_lossy(&bytes).into_owned())
}

fn bearer(token: &str) -> Vec<u8> {
    format!("Authorization: Bearer {token}\r\n").into_bytes()
}

#[tokio::test]
async fn redteam_retest_all_replayed_attacks() {
    let gw = start_gw().await;
    let port = gw.port;
    let token = make_jwt(SUB, "operator", 3600, JWT_SECRET);

    // 双编码sql注入打 /api/x 则必须 400，不透传
    {
        let mut req =
            b"GET /api/x?q=%2527%2520or%25201%253D1 HTTP/1.1\r\nHost: 127.0.0.1\r\n".to_vec();
        req.extend_from_slice(&bearer(&token));
        req.extend_from_slice(b"Connection: close\r\n\r\n");
        let (status, body) = raw_req(port, &req).await;
        let hits = gw.mock.take_filtered("/api/x").len();
        println!(
            "[R1] GET /api/x?q=%2527%2520or%25201%253D1 -> status={status} upstream_hits={hits}"
        );
        println!("[R1] body={}", truncate(&body));
        assert_eq!(status, 400, "R1 双编码 SQLi 必须 400，实际 {status}");
        assert!(
            body.contains("\"code\":40001") || body.contains("40001"),
            "R1 错误码应 40001"
        );
        assert_eq!(hits, 0, "R1 双编码载荷不得透传上游");
    }

    // 双编码穿越%252e%252e%252f 打静态则 400/404
    {
        // 静态fallback路径，须400/404，防落spa fallback 200
        let req = b"GET /assets/%252e%252e%252findex.html HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
        let (status, body) = raw_req(port, req).await;
        println!("[R2-static] GET /assets/%252e%252e%252findex.html -> status={status}");
        assert!(
            status == 400 || status == 404,
            "R2 静态双编码穿越应 400/404，实际 {status}"
        );
        assert!(!body.contains(INDEX_MARKER), "R2 不得泄露 index.html 内容");
        // /api变体，须400/404，防透传上游
        let mut req =
            b"GET /api/gl/..%252f..%252fetc%252fpasswd HTTP/1.1\r\nHost: 127.0.0.1\r\n".to_vec();
        req.extend_from_slice(&bearer(&token));
        req.extend_from_slice(b"Connection: close\r\n\r\n");
        let (status, _body) = raw_req(port, &req).await;
        let hits = gw.mock.take_filtered("/api/gl").len();
        println!(
            "[R2-api] GET /api/gl/..%252f..%252fetc%252fpasswd -> status={status} upstream_hits={hits}"
        );
        assert_eq!(status, 400, "R2 /api 双编码穿越应 400，实际 {status}");
        assert_eq!(hits, 0, "R2 双编码穿越不得到达上游");
    }

    // 敏感文件a.sql / package.json 则 404
    {
        for (path, marker) in [("/a.sql", "retest-a-sql"), ("/package.json", "fms_rgw-app")] {
            let raw =
                format!("GET {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
            let (status, body) = raw_req(port, raw.as_bytes()).await;
            println!("[R3] GET {path} -> status={status}");
            assert_eq!(status, 404, "R3 {path} 应 404，实际 {status}");
            assert!(!body.contains(marker), "R3 {path} 不得泄露文件内容");
        }
    }

    // ws Origin，有效jwt + 恶意Origin 则 403，无Origin 则放行
    {
        let base = |origin: Option<&str>| {
            let mut s = format!(
                "GET /api/nt/ws?token={token} HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
            );
            if let Some(o) = origin {
                s.push_str(&format!("Origin: {o}\r\n"));
            }
            s.push_str("\r\n");
            s
        };
        // 恶意Origin + 有效jwt则 403 且不达上游
        let req = base(Some("https://evil.example.com"));
        let (status, body) = raw_req(port, req.as_bytes()).await;
        let hits = gw.mock.take_filtered("/api/nt/ws").len();
        println!(
            "[R4-evil] WS upgrade Origin=evil + valid JWT -> status={status} upstream_hits={hits}"
        );
        println!("[R4-evil] body={}", truncate(&body));
        assert_eq!(status, 403, "R4 恶意 Origin 应 403，实际 {status}");
        assert_eq!(hits, 0, "R4 恶意 Origin 握手不得到达上游");
        // 白名单Origin + 有效jwt则放行，上游mock 200
        let req = base(Some("https://fms.example.com"));
        let (status, _) = raw_req(port, req.as_bytes()).await;
        println!("[R4-ok] WS upgrade Origin=whitelist + valid JWT -> status={status}");
        assert_eq!(status, 200, "R4 白名单 Origin 应放行，实际 {status}");
        // 无Origin + 有效jwt则放行
        let req = base(None);
        let (status, _) = raw_req(port, req.as_bytes()).await;
        println!("[R4-noorigin] WS upgrade no Origin + valid JWT -> status={status}");
        assert_eq!(status, 200, "R4 无 Origin 应放行，实际 {status}");
    }

    // Host投毒，Host=evil.example.com 时上游收到的 Host 为配置 host
    {
        let mut req = b"GET /api/gl/vouchers HTTP/1.1\r\nHost: evil.example.com\r\n".to_vec();
        req.extend_from_slice(&bearer(&token));
        req.extend_from_slice(b"Connection: close\r\n\r\n");
        let (status, _) = raw_req(port, &req).await;
        let hits = gw.mock.take_filtered("/api/gl/vouchers");
        println!(
            "[R5] GET /api/gl/vouchers Host=evil.example.com -> status={status} upstream_hits={}",
            hits.len()
        );
        assert_eq!(status, 200, "R5 伪造 Host 不应影响路由");
        assert_eq!(hits.len(), 1, "R5 请求必须到达配置上游");
        let up_host = hits[0].header("host").unwrap_or("").to_string();
        let expect_host = gw.mock.base_url.trim_start_matches("http://").to_string();
        println!(
            "[R5] upstream_host={up_host} expect_host={expect_host} x_forwarded_host={:?}",
            hits[0].header("x-forwarded-host")
        );
        assert_eq!(
            up_host, expect_host,
            "R5 上游 Host 必须为配置 host，不得透传 evil.example.com"
        );
        assert_eq!(hits[0].header("x-forwarded-host"), Some("evil.example.com"));
    }

    // /api/vouchers与/api%2Fvouchers则 404，不落静态
    {
        for path in ["/API/vouchers", "/api%2Fvouchers", "/API%2Fvouchers"] {
            let raw =
                format!("GET {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
            let (status, body) = raw_req(port, raw.as_bytes()).await;
            println!("[R6] GET {path} -> status={status}");
            assert_eq!(status, 404, "R6 {path} 应 404，实际 {status}");
            assert!(
                !body.contains(INDEX_MARKER),
                "R6 {path} 不得返回 index.html"
            );
        }
        assert!(
            gw.mock.take_filtered("/vouchers").is_empty(),
            "R6 变体不得到达上游"
        );
    }

    println!("=== ALL RETEST ITEMS PASSED ===");
}

fn truncate(s: &str) -> String {
    let t = s.replace('\r', "\\r").replace('\n', "\\n");
    if t.len() > 160 {
        format!("{}...", &t[..160])
    } else {
        t
    }
}

/// 复现`sign.rs`单元测试`verify_rejects_tamper_and_out_of_window`的间歇性失败，
/// 测试用`bad_sig` = sig[..63] + "a" 模拟"篡改"，但当真实签名末位十六进制字符恰为 'a'
/// 时，拼接结果 == 原签名则 verify 返回 Ok，断言 `Err` 失败，约1/16概率，随now漂移。
/// 本用例确定性证明该失败模式，遍历t找出末位为 'a' 的签名并实测。
#[test]
fn prove_sign_unit_test_flake() {
    let secret = b"sign-secret";
    let body_hash = fms_rgw::sign::compute_body_hash(b"payload");
    let nonce = "8f3a4b5c-0000-0000-0000-000000000001";
    for t in 0..256i64 {
        let sig =
            fms_rgw::sign::compute_signature(secret, "POST", "/api/x", &body_hash, t, nonce, "", "");
        if sig.ends_with('a') {
            let tampered = format!("{}a", &sig[..63]); // 与测试同一"篡改"手法
            let header = format!("t={t}&nonce={nonce}&sig={tampered}");
            let res = fms_rgw::sign::verify_signature_header(
                secret, "POST", "/api/x", &body_hash, &header, t, "", "",
            );
            println!(
                "FLAKE-PROOF: t={t} sig_last_char='a' tampered==sig:{} verify_result={res:?}",
                tampered == sig
            );
            assert_eq!(tampered, sig, "篡改串与原签名逐字节相同");
            assert!(
                res.is_ok(),
                "verify 对逐字节相同的签名返回 Ok —— 测试断言 Err 必失败"
            );
            return;
        }
    }
    panic!("未找到末位为 a 的签名(256 次尝试，概率 < 1e-7)");
}

#[allow(dead_code)]
fn _keep(_: Arc<()>) {}
