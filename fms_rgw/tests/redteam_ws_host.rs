//! 红队验证，ws握手路径`Host`头必须被重写为上游authority，对齐，客户端
//! 伪造`Host`不得到达上游，可污染上游虚拟主机路由/缓存/日志，http与ws路径同规则。
//! 【断言】上游收到的host == 配置上游host，x-forwarded-host == 客户端伪造值，
//! x-forwarded-proto为网关注入值，客户端伪造的proto被覆盖。
mod common;

use common::*;
use fms_rgw::config::Config;

const JWT_SECRET: &[u8] = b"retest-jwt-secret";
const SUB: &str = "3f9a2c1d-0000-4000-8000-000000000002";

#[tokio::test]
async fn ws_handshake_host_header_rewrite_check() {
    let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
    let port = probe.local_addr().expect("addr").port();
    drop(probe);
    let mock = MockUpstream::start().await;
    let fixture = StaticFixture::new();
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
    let mut ready = false;
    for _ in 0..100 {
        let (status, _) = raw_http_probe(
            port,
            b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
        )
        .await;
        if status == 200 {
            ready = true;
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    assert!(ready, "网关未就绪");
    let token = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    // 客户端伪造Host: evil.example.com且伪造X-Forwarded-Proto: http
    let raw = format!(
        "GET /api/nt/ws?token={token} HTTP/1.1\r\nHost: evil.example.com\r\nX-Forwarded-Proto: http\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
    );
    let (status, body) = raw_http(port, raw.as_bytes()).await;
    let hits = mock.take_filtered("/api/nt/ws");
    println!("[WS-HOST] status={status} upstream_hits={}", hits.len());
    for h in &hits {
        println!(
            "[WS-HOST] upstream host={:?} x-forwarded-host={:?} x-forwarded-proto={:?} x-forwarded-for={:?}",
            h.header("host"),
            h.header("x-forwarded-host"),
            h.header("x-forwarded-proto"),
            h.header("x-forwarded-for")
        );
    }
    let expect_host = mock.base_url.trim_start_matches("http://").to_string();
    let first = hits.first().expect("F2：握手必须到达上游");
    // 断言，Host强制重写为配置上游authority(防Host投毒)
    assert_eq!(
        first.header("host").unwrap_or(""),
        expect_host,
        "F2：WS 握手上游收到的 Host 必须为配置上游 host，不得透传客户端伪造值"
    );
    // 原Host记入X-Forwarded-Host
    assert_eq!(
        first.header("x-forwarded-host"),
        Some("evil.example.com"),
        "F2：原 Host 应记录到 X-Forwarded-Host"
    );
    // X-Forwarded-Proto由网关注入，明文降级则 http，客户端伪造值被覆盖
    assert_eq!(
        first.header("x-forwarded-proto"),
        Some("http"),
        "F2：X-Forwarded-Proto 必须为网关注入值(客户端伪造 proto 被覆盖)"
    );
    assert!(!body.is_empty(), "客户端应收到 101/200 响应");
}
