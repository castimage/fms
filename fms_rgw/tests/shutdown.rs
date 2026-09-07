//! 优雅关闭完整路径测试(对应`src/lib.rs` `serve_with_shutdown`可注入关闭信号)
//!
//! 覆盖(`src/lib.rs` `serve`/`serve_with_shutdown`/`serve_connection`)，
//! -注入关闭信号 + in-flight慢请求，触发后请求正常完成、serve 返回 Ok
//! -触发后accept停止，新连接不再被接受/处理
//! -无in-flight时触发则 serve 快速返回
//!
//! 生产路径(`serve`无注入、os信号触发)行为与此完全一致，仅触发源不同。
//! 时序约定，关闭信号均在"请求已到达上游，已进入handler"之后触发，
//! 与生产SIGTERM场景，请求早已在途语义一致。

mod common;

use common::{MockResponse, router_with_mock, spawn_serve_with_shutdown, test_config};
use std::time::Duration;

/// 慢响应兼容的原始http客户端，5s总限、每轮1s读超时(超时继续等待，
/// 而非像`common::raw_http`那样300ms超时即放弃，慢上游响应 >300ms时
/// `common::raw_http`会读不到任何字节返回status=0)。
async fn raw_http_slow(port: u16, raw: &[u8]) -> (u16, Vec<u8>) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .expect("raw TCP 连接失败");
    let _ = stream.set_nodelay(true);
    stream.write_all(raw).await.expect("raw 请求发送失败");
    let mut buf = Vec::new();
    let mut tmp = [0u8; 16384];
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    loop {
        let remain = deadline.saturating_duration_since(std::time::Instant::now());
        if remain.is_zero() {
            break;
        }
        match tokio::time::timeout(Duration::from_secs(1), stream.read(&mut tmp)).await {
            Ok(Ok(n)) if n > 0 => buf.extend_from_slice(&tmp[..n]),
            Ok(Ok(_)) => break, // EOF，连接关闭
            Ok(Err(_)) => break,
            Err(_) => continue, // 读超时，继续等待，慢响应场景
        }
    }
    let status_line = String::from_utf8_lossy(&buf)
        .lines()
        .next()
        .unwrap_or("")
        .to_string();
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<u16>().ok())
        .unwrap_or(0);
    (status, buf)
}

const JWT_SECRET: &[u8] = b"test-jwt-secret";

fn jwt_for(role: &str) -> String {
    common::make_jwt(
        "00000000-0000-7000-0000-000000000001",
        role,
        3600,
        JWT_SECRET,
    )
}

fn proxied_req() -> String {
    format!(
        "GET /api/gl/vouchers HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {}\r\nConnection: close\r\n\r\n",
        jwt_for("operator")
    )
}

/// 轮询mock上游直到记录到**业务请求**(`/api`/** 前缀，排除网关健康探测
/// `/internal/health`噪声)= 请求已进入网关handler并被代理到上游，
/// 上限3s，超时panic，避免在"请求未在途"时触发关闭造成时序误判
async fn wait_request_arrived(mock: &common::MockUpstream) {
    for _ in 0..30 {
        let arrived = mock
            .requests
            .lock()
            .expect("mock 请求锁")
            .iter()
            .any(|r| r.path.starts_with("/api/"));
        if arrived {
            return;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    panic!("mock 上游 3s 内未收到业务请求(网关代理链路异常)");
}

/// in-flight慢请求在关闭信号触发后仍完成，serve返回Ok
#[tokio::test]
async fn inflight_request_completes_during_shutdown() {
    let mut cfg = test_config();
    cfg.tls.allow_insecure_fallback = true;
    // mock上游慢响应500ms，慢于关闭触发时机，验证graceful等待而非强杀
    let (_router, mock) = router_with_mock(|_| {}, None).await;
    *mock.response.lock().expect("mock 响应锁") =
        MockResponse::delayed(200, Duration::from_millis(500));
    cfg.upstream.base_url = mock.base_url.clone();

    let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(());
    let (port, handle) = spawn_serve_with_shutdown(cfg, Some(shutdown_rx)).await;
    common::wait_ready_plain(port).await;

    // 发起慢请求，等其到达上游，进入handler后在途再触发关闭
    let req = proxied_req();
    let client = tokio::spawn(async move { raw_http_slow(port, req.as_bytes()).await });
    wait_request_arrived(&mock).await;
    shutdown_tx.send(()).expect("发送关闭信号");

    // 慢请求仍应完成，500ms后200透传，serve返回Ok
    let (status, _body) = client.await.expect("慢请求任务不应 panic");
    assert_eq!(status, 200, "in-flight 请求应在关闭期间完成并透传 200");

    tokio::time::timeout(Duration::from_secs(5), handle)
        .await
        .expect("serve 应在超时内返回")
        .expect("serve 任务不应 panic")
        .expect("serve 应返回 Ok");
}

/// 无in-flight时触发关闭则 serve 快速返回，<2s，不等10s宽限
#[tokio::test]
async fn idle_shutdown_returns_quickly() {
    let mut cfg = test_config();
    cfg.tls.allow_insecure_fallback = true;
    let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(());
    let (port, handle) = spawn_serve_with_shutdown(cfg, Some(shutdown_rx)).await;
    common::wait_ready_plain(port).await;

    shutdown_tx.send(()).expect("发送关闭信号");
    let elapsed = std::time::Instant::now();
    tokio::time::timeout(Duration::from_secs(3), handle)
        .await
        .expect("serve 应在 3s 内返回")
        .expect("serve 任务不应 panic")
        .expect("serve 应返回 Ok");
    assert!(
        elapsed.elapsed() < Duration::from_secs(2),
        "空闲关闭应快速返回，实际 {:?}",
        elapsed.elapsed()
    );
}

/// 触发关闭后accept停止，新连接不再被接受，存量连接正常完成
#[tokio::test]
async fn accept_stops_after_shutdown_and_existing_conn_finishes() {
    let mut cfg = test_config();
    cfg.tls.allow_insecure_fallback = true;
    // mock上游延迟300ms，存量连接在关闭后仍应完成
    let (_router, mock) = router_with_mock(|_| {}, None).await;
    *mock.response.lock().expect("mock 响应锁") =
        MockResponse::delayed(200, Duration::from_millis(300));
    cfg.upstream.base_url = mock.base_url.clone();

    let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(());
    let (port, handle) = spawn_serve_with_shutdown(cfg, Some(shutdown_rx)).await;
    common::wait_ready_plain(port).await;

    let req = proxied_req();
    let client = tokio::spawn(async move { raw_http_slow(port, req.as_bytes()).await });
    wait_request_arrived(&mock).await;
    shutdown_tx.send(()).expect("发送关闭信号");

    // 存量连接完成，300ms后200
    let (status, _) = client.await.expect("存量请求任务不应 panic");
    assert_eq!(status, 200, "存量连接应在关闭后完成");

    // serve返回
    tokio::time::timeout(Duration::from_secs(5), handle)
        .await
        .expect("serve 超时")
        .expect("serve panic")
        .expect("serve Ok");

    // accept已停止，对已释放端口的新连接应失败，connection refused
    let probe = tokio::net::TcpStream::connect(("127.0.0.1", port)).await;
    assert!(
        probe.is_err(),
        "关闭后新连接应被拒绝(端口已释放或 accept 停止)，实际 {probe:?}"
    );
}
