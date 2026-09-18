//! 红队验证3，ws 101升级流程，`pump_bidirectional`先返回101再await升级防死锁，
//! hyper 1.x服务端需先写出101才完成升级并触发upgrade future，同步await会死锁
//! 客户端永远收不到响应、任务+连接永久占用，高危。
//! 实现为立即返回101，透传上游头，后台任务await升级，10s超时并双向泵。
//! 【断言】客户端必须在5s读超时内收到101 Switching Protocols。
mod common;

use common::*;
use fms_rgw::config::Config;

const JWT_SECRET: &[u8] = b"retest-jwt-secret";
const SUB: &str = "3f9a2c1d-0000-4000-8000-000000000002";

#[tokio::test]
async fn ws_101_upgrade_deadlock_check() {
    let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
    let port = probe.local_addr().expect("addr").port();
    drop(probe);
    let mock = MockUpstream::start().await;
    // 让mock上游返回101 Switching Protocols，模拟真实ws后端
    {
        let mut r = mock.response.lock().unwrap();
        r.status = 101;
        r.headers = vec![
            ("upgrade".to_string(), "websocket".to_string()),
            ("connection".to_string(), "Upgrade".to_string()),
            (
                "sec-websocket-accept".to_string(),
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".to_string(),
            ),
        ];
        r.body = vec![];
    }
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
    let raw = format!(
        "GET /api/nt/ws?token={token} HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
    );
    let start = std::time::Instant::now();
    let (status, body) = raw_http(port, raw.as_bytes()).await;
    let elapsed = start.elapsed().as_millis();
    let status_line = String::from_utf8_lossy(&body)
        .lines()
        .next()
        .unwrap_or("")
        .to_string();
    println!(
        "[WS-101] status={status} elapsed_ms={elapsed} first_line={:?}",
        status_line
    );
    // 断言，客户端必须在读超时内收到101，死锁形态，status=0、5s无响应
    assert_eq!(
        status, 101,
        "F1：上游返回 101 时客户端必须在超时内收到 101 Switching Protocols(死锁形态)，实际 status={status} line={status_line:?}"
    );
    assert!(
        String::from_utf8_lossy(&body).contains("sec-websocket-accept"),
        "101 响应必须透传上游 Sec-WebSocket-Accept"
    );
    // 升级后台任务在10s超时内完成(copy_bidirectional或超时清理)，不残留死连接，
    // mock上游不真正升级，任务应在UPGRADE_TIMEOUT，10s 内结束
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    let hits = mock.take_filtered("/api/nt/ws").len();
    println!("[WS-101] upstream_handshake_hits={hits}");
    assert_eq!(hits, 1, "握手请求应恰好到达上游一次");
}
