//! `tests/accept_smoke.rs`，最终验收V3，临时YAML配置冒烟
//!
//! 从验收专用临时YAML(`target-verify/smoke/smoke.yaml`，`allow_insecure_fallback`=true、
//! 强密钥、cors白名单)经`config::load_from`(`serde_yml`解析路径)加载，
//! 真实tcp端口启动`fms_rgw::serve`，随后就绪轮询，再做断言，
//!   1) GET `/healthz` 应回 200
//!   2) 伪造jwt，垃圾串 + 错误密钥签发打`/api/gl/vouchers` 应 401 且不达上游
//!
//! 与仓库既有redteam/e2e测试同模式，真实监听 + raw tcp。

mod common;

use common::*;

#[tokio::test]
async fn smoke_healthz_and_fake_jwt() {
    // 1) 临时YAML配置(serde_yml解析，tls降级开、强密钥、cors白名单)
    //    路径以CARGO_MANIFEST_DIR为锚(跨机器可移植，本地Windows与服务器Linux
    //    均解析到仓库内target-verify/smoke/smoke.yaml，不再硬编码机器绝对路径)
    let mut cfg = fms_rgw::config::load_from(std::path::Path::new(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/target-verify/smoke/smoke.yaml"
    )))
    .expect("临时 YAML 配置解析失败");
    assert!(
        cfg.tls.allow_insecure_fallback,
        "YAML 必须解析出 allow_insecure_fallback=true"
    );
    assert!(
        cfg.auth.jwt_secret.len() >= 32,
        "YAML 密钥必须满足启动强度校验"
    );
    assert!(
        cfg.sign.sign_secret.len() >= 32,
        "YAML 签名密钥必须满足启动强度校验"
    );
    assert!(
        !cfg.cors.allowed_origins.is_empty(),
        "YAML CORS 白名单必须非空"
    );
    // upstream指向无监听端口(冒烟不需要真后端，401/healthz均不达上游)
    cfg.upstream.base_url = "http://127.0.0.1:18099".to_string();

    // 2) 启动网关(tls证书缺失 + allow_insecure_fallback=true 则 WARN 降级明文)
    let state = fms_rgw::build_state(cfg).expect("AppState 构建失败");
    tokio::spawn(async move {
        let _ = fms_rgw::serve(state).await;
    });

    // 3) 就绪轮询(raw tcp打/healthz，连接被拒=尚未绑定，非致命探测重试)
    let port: u16 = 18080;
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
    assert!(ready, "冒烟：网关 10s 内未就绪(/healthz 非 200)");
    let (status, _body) = raw_http(
        port,
        b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
    )
    .await;
    println!("[SMOKE] GET /healthz -> status={status}");
    assert_eq!(status, 200, "冒烟：/healthz 必须 200，实际 {status}");

    // 4) 伪造jwt，垃圾串则 401
    let raw = b"GET /api/gl/vouchers HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer not.a.valid.jwt\r\nConnection: close\r\n\r\n";
    let (status, body) = raw_http(port, raw).await;
    println!("[SMOKE] fake JWT (garbage) -> status={status}");
    assert_eq!(status, 401, "冒烟：伪造 JWT 必须 401，实际 {status}");
    assert!(
        String::from_utf8_lossy(&body).contains("401"),
        "401 响应体应含错误码"
    );

    // 5) 伪造jwt，错误密钥HS256签发，结构完全合法则 401
    let forged = make_jwt(
        "3f9a2c1d-0000-4000-8000-000000000002",
        "admin",
        3600,
        b"attacker-wrong-secret-0000000000000000",
    );
    let raw = format!(
        "GET /api/gl/vouchers HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {forged}\r\nConnection: close\r\n\r\n"
    );
    let (status, _body) = raw_http(port, raw.as_bytes()).await;
    println!("[SMOKE] fake JWT (wrong secret HS256) -> status={status}");
    assert_eq!(
        status, 401,
        "冒烟：错误密钥签发的 JWT 必须 401，实际 {status}"
    );

    println!("=== V3 SMOKE PASSED (healthz 200 / fake JWT 401) ===");
}
