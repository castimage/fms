//! 红队验证2，`/api/nt/ws`非升级http请求的认证绕过 + json body sql载荷透传
mod common;

use common::*;
use fms_rgw::config::Config;

const JWT_SECRET: &[u8] = b"retest-jwt-secret";
const SUB: &str = "3f9a2c1d-0000-4000-8000-000000000002";

#[tokio::test]
async fn ws_http_unauth_forward_and_json_sqli() {
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
    //非升级http请求打/api/nt/ws，无任何jwt 则 401，不匿名转发上游

    {
        let raw = b"GET /api/nt/ws HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
        let (status, _) = raw_http(port, raw).await;
        let hits = mock.take_filtered("/api/nt/ws");
        println!(
            "[NTWS-UNAUTH] GET /api/nt/ws 无 token -> status={status} upstream_hits={}",
            hits.len()
        );
        assert_eq!(
            status, 401,
            "F4：非升级 /api/nt/ws 无 token 必须 401，不得匿名转发"
        );
        assert_eq!(hits.len(), 0, "F4：未认证 HTTP 请求不得到达上游");
    }
    //json body携带sql注入载荷则 400(，json body同样扫描，Content-Type切换不可绕过)

    {
        let token = make_jwt(SUB, "operator", 3600, JWT_SECRET);
        let payload = r#"{"query":"admin'; drop table users;--"}"#;
        let body_hex = payload.len();
        let raw = format!(
            "POST /api/gl/vouchers HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {token}\r\nContent-Type: application/json\r\nContent-Length: {body_hex}\r\nConnection: close\r\n\r\n{payload}"
        );
        let (status, _) = raw_http(port, raw.as_bytes()).await;
        let hits = mock.take_filtered("/api/gl/vouchers");
        println!(
            "[JSON-SQLI] POST JSON body 含 ';drop table' -> status={status} upstream_hits={}",
            hits.len()
        );
        assert_eq!(
            status, 400,
            "F6：JSON body 中的 SQL 注入特征必须拦截(400)"
        );
        assert_eq!(hits.len(), 0, "F6：JSON 注入载荷不得到达上游");
    }
    //4层编码穿越则静态路径 400，深层编码解码后仍构成 .. 段

    {
        let raw = b"GET /assets/%2525252e%2525252e%2525252findex.html HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
        let (status, body) = raw_http(port, raw).await;
        let leaked = String::from_utf8_lossy(&body).contains("INDEX-MARKER");
        println!("[DEEP-ENC] 4x 编码穿越 -> status={status} leaks_index={leaked}");
        assert_eq!(
            status, 400,
            "F7：4 层编码穿越应 400(深层解码后命中 .. 段检查)"
        );
        assert!(!leaked, "F7：不得泄露 index.html 内容");
    }
}
