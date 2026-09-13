//! 红队验证4，ws token日志脱敏，`log_path`对任意路径query中token=长值，>8字符
//! 统一掩码，路径变体(`//api/nt/ws`、/api/NT/ws等)不得绕过脱敏使有效jwt明文落盘
//! 中危。
//! 【断言】规范路径 + 双斜杠变体 + 大小写变体三者的日志均不含原始token指纹。
mod common;

use common::*;
use fms_rgw::config::Config;
use std::path::PathBuf;

const JWT_SECRET: &[u8] = b"retest-jwt-secret";
const SUB: &str = "3f9a2c1d-0000-4000-8000-000000000002";

#[tokio::test]
async fn ws_token_log_masking_variant_check() {
    let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
    let port = probe.local_addr().expect("addr").port();
    drop(probe);
    let mock = MockUpstream::start().await;
    let fixture = StaticFixture::new();
    let log_file = std::env::temp_dir().join(format!(
        "fms_rgw-wslog-{}-{}.jsonl",
        std::process::id(),
        uuid::Uuid::now_v7()
    ));
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
    cfg.log.access_file = Some(log_file.display().to_string());
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
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    let token = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    let token_frag = &token[..24]; // 用前缀做日志泄漏指纹
    //规范路径应被脱敏

    {
        let raw = format!(
            "GET /api/nt/ws?token={token} HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        );
        let _ = raw_http(port, raw.as_bytes()).await;
        let _ = mock.take_filtered("/api/nt/ws");
    }
    //双斜杠变体，规范化后仍为合法ws路径，则观察日志

    {
        let raw = format!(
            "GET //api/nt/ws?token={token} HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        );
        let _ = raw_http(port, raw.as_bytes()).await;
        let _ = mock.take_filtered("/api/nt/ws");
    }
    //大小写变体，落静态404，但query仍入日志

    {
        let raw = format!(
            "GET /API/NT/WS?token={token} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        );
        let _ = raw_http(port, raw.as_bytes()).await;
    }
    // 访问日志为异步批量flush(log.batch_interval_ms默认500ms)，轮询至三条
    // 变体日志全部落盘，上限3s兜底慢环境，再执行断言
    let mut content;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
    loop {
        content = std::fs::read_to_string(&log_file).unwrap_or_default();
        let variants = content
            .lines()
            .filter(|l| l.contains("/api/nt/ws") || l.contains("API/NT/WS"))
            .count();
        if variants >= 3 || std::time::Instant::now() >= deadline {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    let lines: Vec<&str> = content.lines().collect();
    let mut found_variants = 0usize;
    for (i, l) in lines.iter().enumerate() {
        if l.contains("/api/nt/ws") || l.contains("API/NT/WS") {
            let masked = l.contains("token=***");
            let leaked = l.contains(token_frag);
            println!(
                "[WSLOG] line{} path_leak={} masked={}  line={}",
                i, leaked, masked, l
            );
            found_variants += 1;
            // 断言，三条变体路径的日志均不得含原始token指纹
            assert!(
                !leaked,
                "F3：日志不得出现原始 token 明文(变体行 {})，line={l}",
                i + 1
            );
            assert!(masked, "F3：token 参数必须脱敏为 token=***，line={l}");
        }
    }
    assert!(
        found_variants >= 3,
        "应至少观察到 3 条变体路径日志，实际 {found_variants}"
    );
    // 整个日志文件不得出现原始token
    assert!(
        !content.contains(token_frag),
        "F3：整个日志文件不得出现有效 JWT 明文"
    );
    // 清理
    let _ = std::fs::remove_file(&log_file);
    let _ = PathBuf::new();
}
