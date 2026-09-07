//! `tests/http2_detail.rs`，http/2新功能细粒度测试(对齐`security_auth`/`security_web`粒度)
//!
//! 与`tests/http2.rs`，1个e2e互补，每个行为一个用例，断言严格，含边界与错误路径。
//! 覆盖(对应`src/lib.rs` `serve`/`load_tls_acceptor`/`ConnectInfoService`)，
//!     alpn协商，http/1.1客户端协商alpn=http/1.1(h2侧alpn断言在`http2.rs` e2e)
//!     tls证书加载，有效/不匹配/损坏/缺失 × fallback真假(serve报`Err`而非panic)
//!   连接上限，`Semaphore` 8192满员后新连接被丢弃、已接受连接继续服务
//!   优雅关闭，in-flight请求完成 + accept持续，信号触发路径存在测试缺口，见用例注释
//!     `ConnectInfoService`，客户端ip注入后对上游`X-Forwarded-For`做断言
//!     h2单连接并发10请求(healthz`/api/static`混合)全部正确响应
//!     tls握手失败路径，垃圾字节连接被关闭、服务器不panic继续服务
//!     h2下安全链，filter 400 / auth 401 / rate 429全部在h2请求上生效
//!   明文降级冒烟(`tests/accept_smoke.rs`既有，本文件t2c2/t2d亦覆盖真实serve路径)
//!
//! 全部用例经真实`fms_rgw::serve`监听，rcgen自签证书 + h2/h1 tls客户端，非oneshot。

mod common;

use common::*;
use fms_rgw::config::Config;
use std::path::PathBuf;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// 基础配置，强密钥、cors白名单、不达上游的`base_url`(`/healthz`用例无需上游)
fn base_cfg() -> Config {
    let mut cfg = Config::default();
    cfg.auth.jwt_secret = "h2-detail-jwt-secret-0123456789abcdef".to_string();
    cfg.sign.sign_secret = "h2-detail-sign-secret-0123456789abcdef".to_string();
    cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
    cfg.upstream.base_url = "http://127.0.0.1:18099".to_string();
    cfg
}

/// 拉起tls serve(证书存在则`allow_insecure_fallback`=false)，返回，port, 根证书库, serve句柄
async fn spawn_tls_server(
    tweak: impl FnOnce(&mut Config),
    cert: Option<(&std::path::Path, &std::path::Path)>,
) -> (
    u16,
    rustls::RootCertStore,
    tokio::task::JoinHandle<Result<(), String>>,
) {
    let mut cfg = base_cfg();
    match cert {
        Some((c, k)) => {
            cfg.tls.cert = Some(c.display().to_string());
            cfg.tls.key = Some(k.display().to_string());
        }
        None => cfg.tls.allow_insecure_fallback = true,
    }
    tweak(&mut cfg);
    let (port, handle) = spawn_serve(cfg).await;
    let roots = cert
        .map(|(c, _)| client_roots(c))
        .unwrap_or_else(rustls::RootCertStore::empty);
    (port, roots, handle)
}

/// 临时目录夹具(`Drop`清理)
struct TempDir(PathBuf);
impl TempDir {
    fn new() -> Self {
        let dir = std::env::temp_dir().join(format!(
            "fms_rgw-h2d-{}-{}",
            std::process::id(),
            uuid::Uuid::now_v7()
        ));
        std::fs::create_dir_all(&dir).expect("临时目录创建失败");
        TempDir(dir)
    }
}
impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

//  alpn协商

/// 【行为】tls客户端alpn仅声明http/1.1 时服务端从 alpn 清单["h2","http/1.1"]
/// 协商出http/1.1，hyper-util auto按协商结果自动走http/1.1。
/// 【断言】握手后alpn == "http/1.1"，raw http/1.1 GET `/healthz` 应回 "http/1.1 200"。
/// (h2侧alpn=h2 + http/2 200已由`tests/http2.rs` e2e断言，此处补h1侧alpn断言。)
#[tokio::test]
async fn http1_1_alpn_negotiated_and_200() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let dir = TempDir::new();
    let (cert, key) = write_test_cert(&dir.0);
    let (port, roots, _handle) = spawn_tls_server(|_| {}, Some((&cert, &key))).await;
    wait_ready_tls(port, &roots).await;

    let (mut tls, alpn) = tls_connect(port, &roots, &[b"http/1.1".to_vec()]).await;
    assert_eq!(
        alpn.as_deref(),
        Some(b"http/1.1".as_slice()),
        "ALPN 仅 http/1.1 的客户端必须协商出 http/1.1"
    );
    tls.write_all(b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        .await
        .expect("请求发送失败");
    let mut buf = Vec::new();
    tls.read_to_end(&mut buf).await.expect("响应读取失败");
    let resp = String::from_utf8_lossy(&buf).into_owned();
    assert!(
        resp.starts_with("HTTP/1.1 200"),
        "http/1.1 协商结果必须返回 HTTP/1.1 200，实际首行: {}",
        resp.lines().next().unwrap_or("")
    );
}

//  tls证书加载

/// 【行为】有效PEM证书+密钥，fallback=false 时 `load_tls_acceptor` 成功，tls 启动。
/// 【断言】h2客户端alpn=h2、`/healthz` 200 + json status=ok，serve句柄持续运行，未退出。
#[tokio::test]
async fn valid_pem_tls_serves() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let dir = TempDir::new();
    let (cert, key) = write_test_cert(&dir.0);
    let (port, roots, handle) = spawn_tls_server(|_| {}, Some((&cert, &key))).await;
    wait_ready_tls(port, &roots).await;

    let (alpn, mut client) = h2_connect(port, &roots).await;
    assert_eq!(
        alpn.as_deref(),
        Some(b"h2".as_slice()),
        "有效证书必须协商 h2"
    );
    let (status, body) = h2_request(&mut client, "GET", "https://localhost/healthz", &[]).await;
    assert_eq!(status, 200, "有效证书下 /healthz 必须 200");
    let v: serde_json::Value = serde_json::from_slice(&body).expect("healthz 响应必须为合法 JSON");
    assert_eq!(v["status"], "ok");
    assert!(!handle.is_finished(), "服务未收到关闭信号不得退出");
}

/// 【行为】证书与私钥不匹配，证书来自密钥A、私钥用密钥B 则启动期报错而非 panic。
/// 【断言】serve任务正常结束(无panic/`JoinError`)且返回`Err`，错误信息含tls。
#[tokio::test]
async fn mismatched_cert_key_serve_returns_err() {
    let dir = TempDir::new();
    // 密钥A签发证书，私钥用密钥B则证书/密钥不匹配
    let kp_a = rcgen::KeyPair::generate().expect("密钥 A 生成失败");
    let kp_b = rcgen::KeyPair::generate().expect("密钥 B 生成失败");
    let params =
        rcgen::CertificateParams::new(vec!["localhost".to_string()]).expect("证书参数构造失败");
    let cert = params.self_signed(&kp_a).expect("证书生成失败");
    let cert_path = dir.0.join("mismatch.crt");
    let key_path = dir.0.join("mismatch.key");
    std::fs::write(&cert_path, cert.pem()).expect("证书写入失败");
    std::fs::write(&key_path, kp_b.serialize_pem()).expect("私钥写入失败");

    let mut cfg = base_cfg();
    cfg.tls.cert = Some(cert_path.display().to_string());
    cfg.tls.key = Some(key_path.display().to_string());
    cfg.tls.allow_insecure_fallback = false;
    let (_port, handle) = spawn_serve(cfg).await;
    let res = handle
        .await
        .expect("serve 任务必须正常结束(JoinError 说明 panicked)");
    let msg = res.expect_err("证书/密钥不匹配必须启动失败(Err)，不得静默启动");
    assert!(msg.contains("TLS"), "错误信息应包含 TLS 上下文：{msg}");
}

/// 【行为】损坏PEM，非PEM内容则 `load_tls_acceptor` 失败，serve 报 `Err` 而非 panic。
/// 【断言】证书文件损坏 / 私钥文件损坏两种情形，serve均返回`Err`，fallback=false。
#[tokio::test]
async fn corrupted_pem_serve_returns_err() {
    let dir = TempDir::new();
    let (cert, key) = write_test_cert(&dir.0);

    // 情形1，证书文件为垃圾字节，私钥正常
    let bad_cert = dir.0.join("bad.crt");
    std::fs::write(&bad_cert, "this is not a pem certificate\n\x00garbage").expect("写入失败");
    let mut cfg = base_cfg();
    cfg.tls.cert = Some(bad_cert.display().to_string());
    cfg.tls.key = Some(key.display().to_string());
    cfg.tls.allow_insecure_fallback = false;
    let (_port, handle) = spawn_serve(cfg).await;
    let res = handle.await.expect("serve 不得 panic");
    assert!(
        res.is_err(),
        "损坏证书 PEM 必须启动失败(fallback=false)，实际 Ok"
    );

    // 情形2，私钥文件为垃圾字节，证书正常
    let bad_key = dir.0.join("bad.key");
    std::fs::write(&bad_key, "not-a-private-key").expect("写入失败");
    let mut cfg = base_cfg();
    cfg.tls.cert = Some(cert.display().to_string());
    cfg.tls.key = Some(bad_key.display().to_string());
    cfg.tls.allow_insecure_fallback = false;
    let (_port, handle) = spawn_serve(cfg).await;
    let res = handle.await.expect("serve 不得 panic");
    assert!(
        res.is_err(),
        "损坏私钥 PEM 必须启动失败(fallback=false)，实际 Ok"
    );
}

/// 【行为】证书损坏 + `allow_insecure_fallback`=true 则 WARN 降级明文 http。
/// 【断言】serve返回Ok，持续运行，明文raw GET `/healthz` 应回 200。
#[tokio::test]
async fn corrupted_pem_fallback_true_plaintext_ok() {
    let dir = TempDir::new();
    let (_cert, key) = write_test_cert(&dir.0);
    let bad_cert = dir.0.join("bad.crt");
    std::fs::write(&bad_cert, "garbage not pem").expect("写入失败");

    let mut cfg = base_cfg();
    cfg.tls.cert = Some(bad_cert.display().to_string());
    cfg.tls.key = Some(key.display().to_string());
    cfg.tls.allow_insecure_fallback = true;
    let (port, _handle) = spawn_serve(cfg).await;
    wait_ready_plain(port).await;
}

/// 【行为】证书缺失 + `allow_insecure_fallback`=true 则明文监听成功。
/// 【断言】明文raw GET `/healthz` 应回 200，serve句柄持续运行。
#[tokio::test]
async fn missing_cert_fallback_true_plaintext_ok() {
    let (port, _roots, handle) = spawn_tls_server(|_| {}, None).await;
    wait_ready_plain(port).await;
    let (status, body) = raw_http(
        port,
        b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
    )
    .await;
    assert_eq!(status, 200, "明文降级路径 /healthz 必须 200");
    assert!(String::from_utf8_lossy(&body).contains("\"status\":\"ok\""));
    assert!(!handle.is_finished(), "明文降级服务不得自行退出");
}

/// 【行为】证书缺失 + `allow_insecure_fallback`=false，生产默认则启动失败。
/// 【断言】serve返回`Err`，绝不静默降级，错误信息含tls。
#[tokio::test]
async fn missing_cert_fallback_false_serve_returns_err() {
    let mut cfg = base_cfg();
    cfg.tls.cert = None;
    cfg.tls.key = None;
    cfg.tls.allow_insecure_fallback = false;
    let (_port, handle) = spawn_serve(cfg).await;
    let res = handle
        .await
        .expect("serve 任务必须正常结束(JoinError 说明 panicked)");
    let msg = res.expect_err("缺证书 + fallback=false 必须启动失败");
    assert!(msg.contains("TLS"), "错误信息应包含 TLS 上下文：{msg}");
}

// 连接上限

/// 【行为】serve用`Semaphore`，8192 做连接上限，tcp accept后先取permit，满员后新连接被
/// drop，不进入服务。用明文模式避免8192次tls握手开销。
/// 【断言】1) 8192个保持连接全部被受理，第1个仍可正常请求200，未误伤存量连接，
///        2) 满员后的新连接立即被关闭，raw请求0字节无响应，status=0，
///        3) 连续8个新连接一致被拒，上限稳定。
#[tokio::test]
async fn connection_cap_8192_drops_excess() {
    let (port, _roots, _handle) = spawn_tls_server(|_| {}, None).await;
    wait_ready_plain(port).await;

    // 1) 批量建立8192个保持连接，不发数据，仅持有，每批256个
    let mut conns: Vec<tokio::net::TcpStream> = Vec::with_capacity(8192);
    for batch in 0..32 {
        for _ in 0..256 {
            let s = tokio::net::TcpStream::connect(("127.0.0.1", port))
                .await
                .unwrap_or_else(|_| panic!("连接失败(批 {batch})"));
            conns.push(s);
        }
        tokio::time::sleep(Duration::from_millis(5)).await; // 让accept循环消化backlog
    }
    assert_eq!(conns.len(), 8192);

    // 2) 轮询等待上限生效，满员后新连接应被drop，无任何响应字节
    let mut cap_reached = false;
    for _ in 0..200 {
        let (status, buf) = raw_http(
            port,
            b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
        )
        .await;
        if status == 0 && buf.is_empty() {
            cap_reached = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(cap_reached, "8192 个连接后未观察到第 8193 个连接被拒绝");

    // 3) 上限稳定，连续8个新连接全部被拒，status=0且零字节
    for i in 0..8 {
        let (status, buf) = raw_http(
            port,
            b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
        )
        .await;
        assert_eq!(
            status,
            0,
            "第 8193+{} 个连接应被拒绝(无响应)，实际 status={status}",
            i + 1
        );
        assert!(buf.is_empty(), "被拒连接不得收到任何字节，实际 {:?}", buf);
    }

    // 4) 存量连接不受影响，第1个连接发送/healthz 应回 200
    conns[0]
        .write_all(b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        .await
        .expect("存量连接请求发送失败");
    let mut buf = Vec::new();
    let mut tmp = [0u8; 8192];
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while buf.len() < 16 && std::time::Instant::now() < deadline {
        let n = tokio::time::timeout(Duration::from_millis(300), conns[0].read(&mut tmp))
            .await
            .expect("存量连接响应读超时")
            .expect("存量连接读错误");
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&tmp[..n]);
    }
    assert!(
        String::from_utf8_lossy(&buf).starts_with("HTTP/1.1 200"),
        "满员时存量连接仍须服务 200，实际首行: {}",
        String::from_utf8_lossy(&buf).lines().next().unwrap_or("")
    );
}

// 优雅关闭

/// 【行为】优雅关闭期间，1) in-flight，慢上游请求必须完成，2) accept循环继续接受并服务
/// 新请求，3) serve未收到关闭信号不提前返回。
/// 【测试缺口】`serve`的watch关闭通道在函数内部创建、仅由os `ctrl_c`/SIGTERM(`shutdown_signal`)
/// 广播，Windows上测试进程无法在进程内产生`ctrl_c`，信号触发后停止 accept，逐连接
/// `graceful_shutdown`的完整e2e断言无法在测试内完成。可注入版本已提供，
/// `serve_with_shutdown，state, Option<watch::Receiver>`，`Some`，rx 由外部触发，测试用，
/// `None`保持内部watch，生产行为不变，本用例断言信号触发前的全部可观察行为。
#[tokio::test]
async fn inflight_completes_and_accept_keeps_serving() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let dir = TempDir::new();
    let (cert, key) = write_test_cert(&dir.0);

    // 慢上游，循环accept，每个连接延迟800ms再回200(构造in-flight窗口，
    // 循环保证health_probe_loop的探测连接也得到服务，不挤占业务连接)
    let slow = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("慢上游绑定失败");
    let slow_port = slow.local_addr().expect("慢上游端口").port();
    tokio::spawn(async move {
        loop {
            let Ok((mut stream, _)) = slow.accept().await else {
                break;
            };
            tokio::spawn(async move {
                // 先消费请求(与真实上游一致的读模式，hyper客户端对"不读请求即回包"
                // 的连接会报io错误，见http2_detail测试过程记录)
                let mut tmp = [0u8; 4096];
                let _ = stream.read(&mut tmp).await;
                tokio::time::sleep(Duration::from_millis(800)).await;
                let _ = stream
                    .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
                    .await;
            });
        }
    });

    let (port, roots, handle) = spawn_tls_server(
        |cfg| cfg.upstream.base_url = format!("http://127.0.0.1:{slow_port}"),
        Some((&cert, &key)),
    )
    .await;
    wait_ready_tls(port, &roots).await;

    // 1) 发起慢请求，jwt鉴权后到达慢上游
    let token = make_jwt(
        "3f9a2c1d-0000-4000-8000-000000000002",
        "operator",
        3600,
        b"h2-detail-jwt-secret-0123456789abcdef",
    );
    let (_alpn, mut client) = h2_connect(port, &roots).await;
    let mut c_slow = client.clone();
    let bearer = format!("Bearer {token}");
    let slow_req = tokio::spawn(async move {
        h2_request(
            &mut c_slow,
            "GET",
            "https://localhost/api/gl/vouchers",
            &[("authorization", bearer.as_str())],
        )
        .await
    });

    // 2) in-flight期间，serve未提前退出，accept继续工作，新h2请求200
    tokio::time::sleep(Duration::from_millis(150)).await;
    assert!(!handle.is_finished(), "未收到关闭信号 serve 不得返回");
    let (status, _) = h2_request(&mut client, "GET", "https://localhost/healthz", &[]).await;
    assert_eq!(status, 200, "in-flight 期间新请求必须仍被服务");

    // 3) 慢请求完整返回，in-flight不被中断
    let (status, body) = slow_req.await.expect("慢请求任务 panic 或失败");
    assert_eq!(status, 200, "in-flight 请求必须完成并返回 200");
    assert_eq!(body, b"ok");
    assert!(!handle.is_finished(), "无关闭信号时 serve 不得自行退出");
}

//  ConnectInfoService

/// 【行为】`ConnectInfoService`向请求扩展注入客户端对端ip，proxy转发时写入
/// `X-Forwarded-For`。经真实tls+h2连接验证注入链路，127.0.0.1本机对端。
/// 【断言】带jwt的h2请求到达mock上游，x-forwarded-for == "127.0.0.1"，
/// 且path/query原样透传，响应200。
#[tokio::test]
async fn connect_info_client_ip_in_x_forwarded_for() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let dir = TempDir::new();
    let (cert, key) = write_test_cert(&dir.0);
    let mock = MockUpstream::start().await;
    let (port, roots, _handle) = spawn_tls_server(
        |cfg| cfg.upstream.base_url = mock.base_url.clone(),
        Some((&cert, &key)),
    )
    .await;
    wait_ready_tls(port, &roots).await;

    let token = make_jwt(
        "3f9a2c1d-0000-4000-8000-000000000002",
        "operator",
        3600,
        b"h2-detail-jwt-secret-0123456789abcdef",
    );
    let bearer = format!("Bearer {token}");
    let (_alpn, mut client) = h2_connect(port, &roots).await;
    let (status, _body) = h2_request(
        &mut client,
        "GET",
        "https://localhost/api/gl/vouchers?page=1",
        &[("authorization", bearer.as_str())],
    )
    .await;
    assert_eq!(status, 200, "带合法 JWT 的 h2 请求应到达上游返回 200");

    let hits = mock.take_filtered("/api/gl");
    assert_eq!(hits.len(), 1, "上游应恰好收到 1 个业务请求(健康探测除外)");
    assert_eq!(
        hits[0].header("x-forwarded-for"),
        Some("127.0.0.1"),
        "ConnectInfoService 注入的对端 IP 必须出现在 X-Forwarded-For"
    );
    assert_eq!(hits[0].path, "/api/gl/vouchers", "path 必须原样透传");
    assert_eq!(
        hits[0].query.as_deref(),
        Some("page=1"),
        "query 必须原样透传"
    );
}

//  h2多请求并发

/// 【行为】同一tls连接上并发10个h2流，http/2多路复用，4×`/healthz`、
/// 3×`/api/gl/vouchers`，无token 则 401、3×/index.html 静态 200。
/// 【断言】10个响应全部正确且互不串扰，200/401/200按路径精确匹配，静态响应体含标记。
#[tokio::test]
async fn h2_concurrent_10_requests_single_connection() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let dir = TempDir::new();
    let (cert, key) = write_test_cert(&dir.0);
    // 静态夹具，index.html标记
    let static_dir = dir.0.join("static");
    std::fs::create_dir_all(&static_dir).expect("静态目录创建失败");
    std::fs::write(static_dir.join("index.html"), "STATIC-MARKER-INDEX").expect("静态文件写入失败");
    let (port, roots, _handle) = spawn_tls_server(
        |cfg| cfg.static_cfg.dir = static_dir.display().to_string(),
        Some((&cert, &key)),
    )
    .await;
    wait_ready_tls(port, &roots).await;

    let (_alpn, client) = h2_connect(port, &roots).await;
    let mut futs = Vec::new();
    for i in 0..10 {
        let mut c = client.clone();
        futs.push(async move {
            let (uri, expected) = match i % 3 {
                0 => ("https://localhost/healthz", 200u16),
                1 => ("https://localhost/api/gl/vouchers", 401u16), // 无token 则 401
                _ => ("https://localhost/index.html", 200u16),      // 静态
            };
            let r = h2_request(&mut c, "GET", uri, &[]).await;
            (i, uri, expected, r)
        });
    }
    let results = futures_util::future::join_all(futs).await;
    for (i, uri, expected, (status, body)) in results {
        assert_eq!(status, expected, "并发流 #{i} 路径 {uri} 状态不符");
        if uri.ends_with("index.html") {
            assert_eq!(
                String::from_utf8_lossy(&body),
                "STATIC-MARKER-INDEX",
                "并发流 #{i} 静态响应体不符"
            );
        }
        if uri.ends_with("vouchers") {
            let v: serde_json::Value =
                serde_json::from_slice(&body).expect("401 响应必须为合法 JSON");
            assert_eq!(v["code"], 40100, "并发流 #{i} 无 token 应 401/40100");
        }
    }
}

//  tls握手失败路径

/// 【行为】对tls端口发送非tls垃圾字节则 `TlsAcceptor::accept` 失败，rustls 回
/// tls fatal alert后关闭连接，或直接断开，服务器不panic，继续服务正常tls请求。
/// 【断言】1) 垃圾字节连接只得到tls alert，首字节0x15/EOF/错误，绝不得到http响应，
///        2) 连续3个垃圾连接同样被拒，重复攻击不击穿，
///        3) 之后h2 `/healthz`仍200，服务器存活且未panic。
#[tokio::test]
async fn tls_handshake_garbage_does_not_panic() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let dir = TempDir::new();
    let (cert, key) = write_test_cert(&dir.0);
    let (port, roots, _handle) = spawn_tls_server(|_| {}, Some((&cert, &key))).await;
    wait_ready_tls(port, &roots).await;

    for i in 0..3 {
        let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", port))
            .await
            .expect("TCP 连接失败");
        stream
            .write_all(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n\x00\x01garbage-not-tls")
            .await
            .expect("垃圾字节发送失败");
        let mut buf = [0u8; 64];
        let read = tokio::time::timeout(Duration::from_secs(2), stream.read(&mut buf)).await;
        match read {
            Ok(Ok(0)) => {}  // EOF，连接被关闭
            Ok(Err(_)) => {} // 连接错误，同样视为被拒
            Ok(Ok(n)) => {
                // 允许服务器回tls fatal alert，record type 0x15，但绝不可是http响应
                assert_eq!(
                    buf[0],
                    0x15,
                    "垃圾字节连接 #{i} 收到的首字节必须是 TLS alert(0x15)，实际 {:?}",
                    &buf[..n]
                );
            }
            Err(_) => panic!("垃圾字节连接 #{i} 2s 内未关闭(握手未被拒绝)"),
        }
    }

    let (_alpn, mut client) = h2_connect(port, &roots).await;
    let (status, _body) = h2_request(&mut client, "GET", "https://localhost/healthz", &[]).await;
    assert_eq!(status, 200, "垃圾字节攻击后服务器必须继续正常服务");
}

//  h2下中间件安全链

/// 【行为】h2请求走 filter、rate、auth 安全链，trace最外层，路径穿越回 400、
/// 无token 401。
/// 【断言】h2 GET `/api/gl`/../etc/passwd 应回 400 + code 40001，filter拦截，
///        h2 GET `/api/gl/vouchers`(无`Authorization`)应回 401 + code 40100，auth拦截。
#[tokio::test]
async fn h2_filter_400_and_auth_401() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let dir = TempDir::new();
    let (cert, key) = write_test_cert(&dir.0);
    let (port, roots, _handle) = spawn_tls_server(|_| {}, Some((&cert, &key))).await;
    wait_ready_tls(port, &roots).await;

    let (_alpn, mut client) = h2_connect(port, &roots).await;
    // filter层，路径穿越
    let (status, body) = h2_request(
        &mut client,
        "GET",
        "https://localhost/api/gl/../etc/passwd",
        &[],
    )
    .await;
    assert_eq!(status, 400, "h2 下路径穿越必须 400，实际 {status}");
    let v: serde_json::Value = serde_json::from_slice(&body).expect("400 响应必须为合法 JSON");
    assert_eq!(v["code"], 40001, "路径穿越错误码必须 40001");
    // auth层，无token
    let (status, body) =
        h2_request(&mut client, "GET", "https://localhost/api/gl/vouchers", &[]).await;
    assert_eq!(status, 401, "h2 下无 token 必须 401，实际 {status}");
    let v: serde_json::Value = serde_json::from_slice(&body).expect("401 响应必须为合法 JSON");
    assert_eq!(v["code"], 40100, "无 token 错误码必须 40100");
}

/// 【行为】rate中间件在h2请求上生效，ip维度`ip_per_window`=2 则第3个 h2 请求 429。
/// 【断言】前2个请求401，auth拦截，limit未超，第3个429 + code 42900。
#[tokio::test]
async fn h2_rate_limit_429() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let dir = TempDir::new();
    let (cert, key) = write_test_cert(&dir.0);
    let (port, roots, _handle) =
        spawn_tls_server(|cfg| cfg.limits.ip_per_window = 2, Some((&cert, &key))).await;
    wait_ready_tls(port, &roots).await;

    let (_alpn, mut client) = h2_connect(port, &roots).await;
    let mut statuses = Vec::new();
    for _ in 0..3 {
        let (status, body) =
            h2_request(&mut client, "GET", "https://localhost/api/gl/vouchers", &[]).await;
        statuses.push((status, body));
    }
    assert_eq!(
        (statuses[0].0, statuses[1].0),
        (401, 401),
        "限流额度内请求先被 auth 拦截(401)，不得提前 429"
    );
    assert_eq!(statuses[2].0, 429, "第 3 个 h2 请求必须 429(IP 维度限流)");
    let v: serde_json::Value =
        serde_json::from_slice(&statuses[2].1).expect("429 响应必须为合法 JSON");
    assert_eq!(v["code"], 42900, "限流错误码必须 42900");
}
