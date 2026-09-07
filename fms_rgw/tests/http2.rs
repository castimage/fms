//! `tests/http2.rs`，http/1.1 + http/2双协议e2e验证，hyper-util手写tls服务
//!
//! 场景，rcgen在测试内生成自签证书，SAN localhost 写入临时 PEM 文件，随后
//! `Config`指向证书(`allow_insecure_fallback`=false也必须能启动，有证书走tls)，随后
//! `fms_rgw::serve`真实监听，再做断言，
//!   1) h2客户端，h2 crate over tokio-rustls，alpn仅h2 时 GET `/healthz` 应回 http/2 200，
//!      且tls握手alpn协商结果为h2，服务端alpn清单["h2", "http/1.1"]
//!   2) http/1.1客户端，alpn仅http/1.1 时 raw GET `/healthz` 应回 200，自动降级http1
//!
//! 明文降级路径，无证书由`accept_smoke` / e2e / security_* 既有用例覆盖，此处不重复。

use fms_rgw::config::Config;
use std::io::BufReader;
use std::path::Path;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use uuid::Uuid;

/// 生成自签证书，rcgen，SAN localhost并写临时PEM，返回 (`cert_path`, `key_path`)
fn write_test_cert(dir: &Path) -> (std::path::PathBuf, std::path::PathBuf) {
    let key_pair = rcgen::KeyPair::generate().expect("rcgen 密钥生成失败");
    let params =
        rcgen::CertificateParams::new(vec!["localhost".to_string()]).expect("证书参数构造失败");
    let cert = params.self_signed(&key_pair).expect("自签证书生成失败");
    let cert_path = dir.join("server.crt");
    let key_path = dir.join("server.key");
    std::fs::write(&cert_path, cert.pem()).expect("证书 PEM 写入失败");
    std::fs::write(&key_path, key_pair.serialize_pem()).expect("私钥 PEM 写入失败");
    (cert_path, key_path)
}

/// 客户端根证书库，把服务端自签证书作为受信任根
fn client_roots(cert_path: &Path) -> rustls::RootCertStore {
    let mut roots = rustls::RootCertStore::empty();
    for c in rustls_pemfile::certs(&mut BufReader::new(
        std::fs::File::open(cert_path).expect("读服务端证书"),
    )) {
        roots.add(c.expect("证书解析")).expect("根证书加入失败");
    }
    roots
}

fn client_tls_config(roots: &rustls::RootCertStore, alpn: &[Vec<u8>]) -> rustls::ClientConfig {
    let mut cfg = rustls::ClientConfig::builder()
        .with_root_certificates(roots.clone())
        .with_no_client_auth();
    cfg.alpn_protocols = alpn.to_vec();
    cfg
}

/// 就绪探测变体，非致命，tcp连接被拒/tls握手失败均返回`None`，网关未就绪，
/// 供就绪轮询循环重试，就绪后正式断言仍走`tls_raw_h1`，连接失败须立即暴露
async fn tls_raw_h1_probe(port: u16, roots: &rustls::RootCertStore, raw: &[u8]) -> Option<String> {
    let tcp = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.ok()?;
    let cfg = client_tls_config(roots, &[b"http/1.1".to_vec()]);
    let connector = tokio_rustls::TlsConnector::from(Arc::new(cfg));
    let server_name = rustls::pki_types::ServerName::try_from("localhost")
        .ok()?
        .to_owned();
    let mut tls = connector.connect(server_name, tcp).await.ok()?;
    tls.write_all(raw).await.ok()?;
    let mut buf = Vec::new();
    tls.read_to_end(&mut buf).await.ok()?;
    Some(String::from_utf8_lossy(&buf).into_owned())
}

/// tls连接，指定alpn后发原始 http/1.1 请求，返回全部响应文本
async fn tls_raw_h1(port: u16, roots: &rustls::RootCertStore, raw: &[u8]) -> String {
    let tcp = tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .expect("TCP 连接失败");
    let cfg = client_tls_config(roots, &[b"http/1.1".to_vec()]);
    let connector = tokio_rustls::TlsConnector::from(Arc::new(cfg));
    let server_name = rustls::pki_types::ServerName::try_from("localhost")
        .expect("server name")
        .to_owned();
    let mut tls = connector
        .connect(server_name, tcp)
        .await
        .expect("TLS 握手失败(http/1.1 ALPN)");
    tls.write_all(raw).await.expect("raw 请求发送失败");
    let mut buf = Vec::new();
    tls.read_to_end(&mut buf).await.expect("响应读取失败");
    String::from_utf8_lossy(&buf).into_owned()
}

/// h2客户端，GET `/healthz`，alpn仅h2，返回，协商alpn, 状态码, 响应体
async fn h2_get_healthz(
    port: u16,
    roots: &rustls::RootCertStore,
) -> (Option<Vec<u8>>, u16, Vec<u8>) {
    let tcp = tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .expect("TCP 连接失败");
    let cfg = client_tls_config(roots, &[b"h2".to_vec()]);
    let connector = tokio_rustls::TlsConnector::from(Arc::new(cfg));
    let server_name = rustls::pki_types::ServerName::try_from("localhost")
        .expect("server name")
        .to_owned();
    let tls = connector
        .connect(server_name, tcp)
        .await
        .expect("TLS 握手失败(h2 ALPN)");
    let alpn = tls.get_ref().1.alpn_protocol().map(|a| a.to_vec());
    let (mut client, h2conn) = h2::client::handshake(tls).await.expect("h2 握手失败");
    tokio::spawn(async move {
        let _ = h2conn.await;
    });
    let req = hyper::Request::builder()
        .uri("https://localhost/healthz")
        .body(())
        .expect("h2 请求构造失败");
    let (resp_fut, _stream) = client
        .send_request(req, true)
        .expect("h2 send_request 失败");
    let resp = resp_fut.await.expect("h2 响应等待失败");
    let status = resp.status().as_u16();
    let (_, mut body) = resp.into_parts();
    let mut buf = Vec::new();
    while let Some(frame) = body.data().await {
        buf.extend_from_slice(&frame.expect("h2 数据帧"));
    }
    (alpn, status, buf)
}

#[tokio::test]
async fn h2_and_http1_over_tls_alpn() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    // 1) 临时目录 + 自签证书
    let dir =
        std::env::temp_dir().join(format!("fms_rgw-h2-{}-{}", std::process::id(), Uuid::now_v7()));
    std::fs::create_dir_all(&dir).expect("临时目录创建失败");
    let (cert_path, key_path) = write_test_cert(&dir);
    let roots = client_roots(&cert_path);

    // 2) 配置，证书就位(allow_insecure_fallback=false也须启动，有证书必须走tls)
    let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("端口探测绑定失败");
    let port = probe.local_addr().expect("端口探测失败").port();
    drop(probe);
    let mut cfg = Config::default();
    cfg.server.listen = format!("127.0.0.1:{port}");
    cfg.tls.cert = Some(cert_path.display().to_string());
    cfg.tls.key = Some(key_path.display().to_string());
    cfg.tls.allow_insecure_fallback = false;
    cfg.auth.jwt_secret = "h2-e2e-jwt-secret".to_string();
    cfg.sign.sign_secret = "h2-e2e-sign-secret".to_string();
    cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
    cfg.upstream.base_url = "http://127.0.0.1:18099".to_string(); // healthz不达上游
    let state = fms_rgw::build_state(cfg).expect("AppState 构建失败");
    tokio::spawn(async move {
        let _ = fms_rgw::serve(state).await;
    });

    // 3) 就绪轮询，tls + http/1.1 raw请求(10s上限，连接被拒=尚未绑定，
    //    用非致命探测重试，spawn后首次探测与TcpListener::bind存在竞态)
    let ready_req = b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    let mut ready = false;
    for _ in 0..100 {
        match tls_raw_h1_probe(port, &roots, ready_req).await {
            Some(resp) if resp.starts_with("HTTP/1.1 200") => {
                ready = true;
                break;
            }
            _ => {}
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    assert!(ready, "TLS 网关 10s 内未就绪(/healthz 非 200)");

    // 4) http/2验证，h2客户端，alpn必须协商h2，GET /healthz 应回 200 + json
    let (alpn, status, body) = h2_get_healthz(port, &roots).await;
    println!(
        "[H2E2E] ALPN={:?} HTTP/2 GET /healthz -> status={} body={}",
        alpn.as_deref().map(String::from_utf8_lossy),
        status,
        String::from_utf8_lossy(&body)
    );
    assert_eq!(
        alpn.as_deref(),
        Some(b"h2".as_slice()),
        "TLS 握手必须协商 ALPN h2"
    );
    assert_eq!(status, 200, "HTTP/2 /healthz 必须 200，实际 {status}");
    let v: serde_json::Value = serde_json::from_slice(&body).expect("healthz 响应必须为合法 JSON");
    assert_eq!(v["status"], "ok", "HTTP/2 响应体 status 必须为 ok");

    // 5) http/1.1验证，alpn仅http/1.1的客户端仍可用，自动降级http1
    let resp = tls_raw_h1(port, &roots, ready_req).await;
    println!(
        "[H2E2E] ALPN=http/1.1 GET /healthz -> {}",
        resp.lines().next().unwrap_or("")
    );
    assert!(
        resp.starts_with("HTTP/1.1 200"),
        "HTTP/1.1 over TLS 必须 200，实际首行: {}",
        resp.lines().next().unwrap_or("")
    );

    let _ = std::fs::remove_dir_all(&dir);
    println!("=== H2 E2E PASSED (ALPN h2 -> HTTP/2 200; ALPN http/1.1 -> HTTP/1.1 200) ===");
}
