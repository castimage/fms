//! tests/common/mod.rs，集成/端到端测试共用工具
//!
//! 职责，
//! `MockUpstream`，tokio::net::TcpListener + hyper 1 http1 手写上游。
//!   记录每个收到的请求(method/path/query/headers/body)到共享状态(Arc<Mutex<Vec>>)，
//!   返回可配置响应(状态码/头/体，Arc<Mutex<MockResponse>>)。
//! `test_config`，构造 Config 结构直接注入 AppState(fms_rgw::build_state)，
//!   随机端口由 MockUpstream 绑定 127.0.0.1:0 取得。
//! 请求/断言助手，oneshot 直调路由、body 收集、jwt 签发、ConnectInfo 注入。
//!
//! 注，本模块被 integration/e2e 两个测试二进制分别编译，各自只用子集，
//! 统一 allow，dead_code 避免按二进制分别告警。
#![allow(dead_code)]

use axum::Router;
use axum::body::Body;
use axum::extract::connect_info::ConnectInfo;
use axum::http::header::HeaderMap;
use axum::http::{Request, Response, StatusCode};
use bytes::Bytes;
use http_body_util::{BodyExt, Full};
use hyper::body::Incoming;
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper_util::rt::TokioIo;
use fms_rgw::config::Config;
use std::convert::Infallible;
use std::io::BufReader;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tower::ServiceExt;
use uuid::Uuid;

/// 上游收到的请求记录
#[derive(Debug, Clone)]
pub struct RecordedRequest {
    pub method: String,
    pub path: String,
    pub query: Option<String>,
    /// 头名小写, 头值 有序列表
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl RecordedRequest {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k == &name.to_ascii_lowercase())
            .map(|(_, v)| v.as_str())
    }

    pub fn body_str(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }
}

/// 上游可配置响应
#[derive(Debug, Clone)]
pub struct MockResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
    /// 响应前延迟，模拟慢上游，优雅关闭测试用
    pub delay: Option<std::time::Duration>,
}

impl MockResponse {
    /// 便捷构造，状态 + 延迟(默认 json 体)
    pub fn delayed(status: u16, delay: std::time::Duration) -> Self {
        Self {
            status,
            delay: Some(delay),
            ..Self::default()
        }
    }
}

impl Default for MockResponse {
    fn default() -> Self {
        Self {
            status: 200,
            headers: vec![("content-type".to_string(), "application/json".to_string())],
            body: br#"{"ok":true,"from":"mock"}"#.to_vec(),
            delay: None,
        }
    }
}

/// 手写 mock 上游，绑定 127.0.0.1:0 随机端口，记录全部请求到共享状态
pub struct MockUpstream {
    pub base_url: String,
    pub requests: Arc<Mutex<Vec<RecordedRequest>>>,
    pub response: Arc<Mutex<MockResponse>>,
}

impl MockUpstream {
    pub async fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("mock 上游绑定随机端口失败");
        let addr = listener.local_addr().expect("mock 上游获取监听地址失败");
        let requests: Arc<Mutex<Vec<RecordedRequest>>> = Arc::new(Mutex::new(Vec::new()));
        let response: Arc<Mutex<MockResponse>> = Arc::new(Mutex::new(MockResponse::default()));
        // 循环捕获移到独立 async fn，避免 async-move 循环内嵌套 move 闭包的
        // 生成器捕获限制(rustc E0382 use-of-moved-value)
        let reqs_loop = requests.clone();
        let resp_loop = response.clone();
        tokio::spawn(async move {
            loop {
                let (stream, _) = match listener.accept().await {
                    Ok(v) => v,
                    Err(_) => break, // 监听器关闭，结束
                };
                tokio::spawn(serve_connection(
                    stream,
                    reqs_loop.clone(),
                    resp_loop.clone(),
                ));
            }
        });
        Self {
            base_url: format!("http://{addr}"),
            requests,
            response,
        }
    }

    /// 取出记录(并按 path 过滤，健康探测等噪声可排除)
    pub fn take_filtered(&self, path_prefix: &str) -> Vec<RecordedRequest> {
        let mut guard = self.requests.lock().expect("mock 请求记录锁");
        let hits: Vec<RecordedRequest> = guard
            .iter()
            .filter(|r| r.path.starts_with(path_prefix))
            .cloned()
            .collect();
        guard.retain(|r| !r.path.starts_with(path_prefix));
        hits
    }
}

/// 单连接处理，hyper http1 serve_connection，请求记录 + 可配置响应
async fn serve_connection(
    stream: tokio::net::TcpStream,
    requests: Arc<Mutex<Vec<RecordedRequest>>>,
    response: Arc<Mutex<MockResponse>>,
) {
    let io = TokioIo::new(stream);
    let svc = service_fn(move |req: hyper::Request<Incoming>| {
        let requests = requests.clone();
        let response = response.clone();
        async move {
            let (parts, body) = req.into_parts();
            let bytes = body
                .collect()
                .await
                .expect("mock 上游收集请求体失败")
                .to_bytes();
            let headers = parts
                .headers
                .iter()
                .map(|(k, v)| {
                    (
                        k.as_str().to_ascii_lowercase(),
                        v.to_str().unwrap_or("").to_string(),
                    )
                })
                .collect();
            requests
                .lock()
                .expect("mock 请求记录锁")
                .push(RecordedRequest {
                    method: parts.method.as_str().to_string(),
                    path: parts.uri.path().to_string(),
                    query: parts.uri.query().map(|s| s.to_string()),
                    headers,
                    body: bytes.to_vec(),
                });
            let r = response.lock().expect("mock 响应锁").clone();
            // 慢上游模拟，响应前延迟(优雅关闭 in-flight 测试用)
            if let Some(d) = r.delay {
                tokio::time::sleep(d).await;
            }
            let mut builder = hyper::Response::builder().status(r.status);
            for (k, v) in &r.headers {
                builder = builder.header(k.as_str(), v.as_str());
            }
            let resp = builder
                .body(Full::new(Bytes::from(r.body)))
                .expect("mock 响应构造失败");
            Ok::<_, Infallible>(resp)
        }
    });
    let _ = http1::Builder::new().serve_connection(io, svc).await;
}

/// 默认测试配置，密钥、cors 白名单、高限流额度，限流专项用例自行覆盖、
/// 明文降级开、静态目录指向调用方注入
pub fn test_config() -> Config {
    let mut cfg = Config::default();
    cfg.auth.jwt_secret = "test-jwt-secret".to_string();
    cfg.sign.sign_secret = "test-sign-secret".to_string();
    cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
    cfg.limits.ip_per_window = 10_000;
    cfg.limits.user_per_window = 10_000;
    cfg.limits.login_per_window = 10_000;
    cfg.tls.allow_insecure_fallback = true;
    cfg.server.listen = "127.0.0.1:0".to_string();
    // 全量采样，链级用例断言 trace 透传/响应头/日志 req_id，采样开关语义由 types/log 单测覆盖
    cfg.trace.sample_rate = 100;
    cfg
}

/// 启动 mock 上游并把 Config 指过去，构造 AppState + Router(oneshot 直调)
pub async fn router_with_mock(
    tweak: impl FnOnce(&mut Config),
    static_dir: Option<&str>,
) -> (Router, MockUpstream) {
    let mock = MockUpstream::start().await;
    let mut cfg = test_config();
    cfg.upstream.base_url = mock.base_url.clone();
    if let Some(dir) = static_dir {
        cfg.static_cfg.dir = dir.to_string();
    }
    tweak(&mut cfg);
    let state = fms_rgw::build_state(cfg).expect("测试 AppState 构建失败");
    (fms_rgw::build_router(state), mock)
}

/// 构造请求，默认无头无体
pub fn request(method: &str, uri: &str) -> Request<Body> {
    Request::builder()
        .method(method)
        .uri(uri)
        .body(Body::empty())
        .expect("测试请求构造失败")
}

/// 注入 ConnectInfo(模拟真实对端 ip，限流用例必用，防并发用例互相污染)
pub fn with_ip(req: Request<Body>, ip: &str) -> Request<Body> {
    let addr: SocketAddr = format!("{ip}:54321").parse().expect("测试 IP 解析失败");
    let mut req = req;
    req.extensions_mut().insert(ConnectInfo(addr));
    req
}

/// 带 Authorization: Bearer 头的请求(jwt 由 make_jwt 签发)
pub fn with_auth(req: Request<Body>, token: &str) -> Request<Body> {
    let mut req = req;
    req.headers_mut().insert(
        "authorization",
        format!("Bearer {token}").parse().expect("认证头构造失败"),
    );
    req
}

/// 带 json body + Content-Type 的 POST 请求
pub fn with_json_body(uri: &str, body: &str) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("content-type", "application/json")
        .body(Body::from(body.to_string()))
        .expect("测试请求构造失败")
}

/// oneshot 直调路由
pub async fn oneshot(router: &Router, req: Request<Body>) -> Response<Body> {
    router
        .clone()
        .oneshot(req)
        .await
        .expect("路由 oneshot 调用失败")
}

/// 收集响应为，status, headers, body
pub async fn collect(resp: Response<Body>) -> (StatusCode, HeaderMap, Bytes) {
    let (parts, body) = resp.into_parts();
    let bytes = body.collect().await.expect("响应体收集失败").to_bytes();
    (parts.status, parts.headers, bytes)
}

/// 解析响应体为 json(断言 code 等字段用)
pub async fn collect_json(resp: Response<Body>) -> (StatusCode, HeaderMap, serde_json::Value) {
    let (status, headers, bytes) = collect(resp).await;
    let v: serde_json::Value = serde_json::from_slice(&bytes).expect("响应体应为合法 JSON");
    (status, headers, v)
}

/// 签发测试 jwt(HS256，与 fms_rgw::auth::Claims 结构一致)
pub fn make_jwt(sub: &str, role: &str, exp_offset_secs: i64, secret: &[u8]) -> String {
    let claims = fms_rgw::auth::Claims {
        sub: Uuid::parse_str(sub).expect("测试 sub 必须为 uuid 文本"),
        role: role.to_string(),
        exp: now_secs() + exp_offset_secs,
        iss: None,
        aud: None,
    };
    jsonwebtoken::encode(
        &jsonwebtoken::Header::new(jsonwebtoken::Algorithm::HS256),
        &claims,
        &jsonwebtoken::EncodingKey::from_secret(secret),
    )
    .expect("测试 JWT 签发失败")
}

/// 签发带自定义 iss/aud 与绝对 exp 的测试 jwt(HS256，Claims 全字段可控，
/// 供安全测试构造 pgjwt 兼容 / iss-aud 严格校验场景)
pub fn make_jwt_full(
    sub: &str,
    role: &str,
    exp: i64,
    iss: Option<&str>,
    aud: Option<&str>,
    secret: &[u8],
) -> String {
    let claims = fms_rgw::auth::Claims {
        sub: Uuid::parse_str(sub).expect("测试 sub 必须为 uuid 文本"),
        role: role.to_string(),
        exp,
        iss: iss.map(|s| s.to_string()),
        aud: aud.map(|s| s.to_string()),
    };
    jsonwebtoken::encode(
        &jsonwebtoken::Header::new(jsonwebtoken::Algorithm::HS256),
        &claims,
        &jsonwebtoken::EncodingKey::from_secret(secret),
    )
    .expect("测试 JWT 签发失败")
}

/// 当前 unix 秒
pub fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .expect("系统时钟异常")
        .as_secs() as i64
}

/// 临时静态目录夹具，建 index.html / hash 资源 / .env，Drop 时清理
pub struct StaticFixture {
    pub dir: std::path::PathBuf,
}

impl StaticFixture {
    pub fn new() -> Self {
        let dir = std::env::temp_dir().join(format!(
            "fms_rgw-static-{}-{}",
            std::process::id(),
            Uuid::now_v7()
        ));
        std::fs::create_dir_all(dir.join("assets")).expect("静态夹具建目录失败");
        std::fs::write(
            dir.join("index.html"),
            "<!doctype html><title>fms_rgw</title>INDEX-MARKER",
        )
        .expect("静态夹具写 index.html 失败");
        std::fs::write(dir.join("assets/app.3f9a2c1d.js"), "console.log('hashed')")
            .expect("静态夹具写 hash 资源失败");
        std::fs::write(dir.join(".env"), "SECRET=leak-me").expect("静态夹具写 .env 失败");
        //  Web 攻击测试用敏感文件( 枚举/ 穿越目标)，
        std::fs::create_dir_all(dir.join("db")).expect("静态夹具建 db 目录失败");
        std::fs::create_dir_all(dir.join(".git")).expect("静态夹具建 .git 目录失败");
        std::fs::write(
            dir.join("db/backup.sql"),
            "SELECT * FROM users; -- db-secret",
        )
        .expect("静态夹具写 backup.sql 失败");
        std::fs::write(
            dir.join("secret.sql"),
            "SELECT secret FROM app; -- sql-secret",
        )
        .expect("静态夹具写 secret.sql 失败");
        std::fs::write(dir.join("backup.bak"), "backup-secret")
            .expect("静态夹具写 backup.bak 失败");
        std::fs::write(dir.join("note.swp"), "swap-secret").expect("静态夹具写 note.swp 失败");
        std::fs::write(dir.join(".DS_Store"), "dsstore-secret").expect("静态夹具写 .DS_Store 失败");
        std::fs::write(dir.join("private.pem"), "pem-secret").expect("静态夹具写 private.pem 失败");
        std::fs::write(dir.join("server.key"), "key-secret").expect("静态夹具写 server.key 失败");
        std::fs::write(
            dir.join("package.json"),
            r#"{"name":"fms_rgw-app","private":true}"#,
        )
        .expect("静态夹具写 package.json 失败");
        std::fs::write(dir.join(".gitignore"), "# gitignore-secret")
            .expect("静态夹具写 .gitignore 失败");
        std::fs::write(dir.join(".git/config"), "git-config-secret")
            .expect("静态夹具写 .git/config 失败");
        Self { dir }
    }
}

impl Drop for StaticFixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

// — 真实网关(原始 tcp 攻击测试用，CRLF/CL 谎报/chunked/TRACE/CONNECT/超长头)，—

/// 真实网关环境，随机端口 + mock 上游 + 静态夹具 + 访问日志文件
pub struct RealGw {
    pub base: String,
    pub mock: MockUpstream,
    pub log_file: PathBuf,
    /// 保持静态目录存活(OnceLock 静态值进程退出不 Drop，目录留 temp 无妨)
    _fixture: StaticFixture,
    /// 持有专用运行时，一旦 Drop 则服务器任务全部终止(OnceLock 静态值
    /// 进程退出不 Drop 则网关存活至测试进程结束，与 e2e.rs 同模式)
    _rt: tokio::runtime::Runtime,
}

static REAL_GW: OnceLock<RealGw> = OnceLock::new();

/// 进程级单例真实网关(专用多线程运行时，线程内 block_on 初始化，
/// 就绪后以 url 暴露，全部 raw tcp 用例共用，限流额度放大防互扰)
pub fn real_gateway() -> &'static RealGw {
    real_gateway_with(|_| {})
}

/// [0] 的可定制配置版本，初始化时对 Config 施加 tweak
/// (各测试二进制按需收紧/调整额度，OnceLock 单例每二进制仅生效一次)。
/// e2e.rs 复用此入口(login 限流收紧等)，消除重复的环境装配。
pub fn real_gateway_with(tweak: impl FnOnce(&mut Config) + Send + 'static) -> &'static RealGw {
    REAL_GW.get_or_init(|| {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("真实网关运行时创建失败");
        let (tx, rx) = std::sync::mpsc::channel::<(
            tokio::runtime::Runtime,
            (String, MockUpstream, PathBuf, StaticFixture),
        )>();
        std::thread::spawn(move || {
            let inner = rt.block_on(init_real_gateway(tweak));
            let _ = tx.send((rt, inner));
        });
        let (rt, (base, mock, log_file, fixture)) = rx.recv().expect("真实网关初始化线程异常退出");
        RealGw {
            base,
            mock,
            log_file,
            _fixture: fixture,
            _rt: rt,
        }
    })
}

/// 初始化，探测随机端口，建 mock 上游与静态夹具，随后 build_state、spawn，经 fms_rgw::serve 再做就绪轮询
/// 返回，base_url, mock, log_file, fixture，运行时由 real_gateway_with 闭包补入 RealGw
async fn init_real_gateway(
    tweak: impl FnOnce(&mut Config) + Send + 'static,
) -> (String, MockUpstream, PathBuf, StaticFixture) {
    let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("端口探测绑定失败");
    let port = probe.local_addr().expect("端口探测失败").port();
    drop(probe);
    let mock = MockUpstream::start().await;
    let fixture = StaticFixture::new();
    let log_file = std::env::temp_dir().join(format!(
        "fms_rgw-sec-log-{}-{}.jsonl",
        std::process::id(),
        Uuid::now_v7()
    ));
    let mut cfg = Config::default();
    cfg.server.listen = format!("127.0.0.1:{port}");
    cfg.tls.allow_insecure_fallback = true;
    cfg.upstream.base_url = mock.base_url.clone();
    cfg.auth.jwt_secret = "sec-web-jwt-secret".to_string();
    cfg.sign.sign_secret = "sec-web-sign-secret".to_string();
    cfg.cors.allowed_origins = vec!["https://fms.example.com".to_string()];
    cfg.limits.ip_per_window = 100_000;
    cfg.limits.user_per_window = 100_000;
    cfg.limits.login_per_window = 100_000;
    cfg.static_cfg.dir = fixture.dir.display().to_string();
    cfg.log.access_file = Some(log_file.display().to_string());
    // trace 全量采样，e2e/安全用例断言 X-Trace-Id 透传与日志 req_id 精确回填，须确定性全量
    cfg.trace.sample_rate = 100;
    // 调用方 tweak 覆盖默认配置(e2e 的 login 限流收紧等)
    tweak(&mut cfg);
    let state = fms_rgw::build_state(cfg).expect("真实网关 AppState 构建失败");
    tokio::spawn(async move {
        let _ = fms_rgw::serve(state).await;
    });
    // 就绪轮询(raw tcp 打 /healthz，最多 10s，连接被拒=未就绪，用非致命探测重试)
    for _ in 0..100 {
        let (status, _) = raw_http_probe(
            port,
            b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
        )
        .await;
        if status == 200 {
            return (format!("http://127.0.0.1:{port}"), mock, log_file, fixture);
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    panic!("真实网关 10s 内未就绪");
}

/// 真实网关端口(从 base url 解析)
pub fn real_port(gw: &RealGw) -> u16 {
    gw.base
        .rsplit(':')
        .next()
        .expect("真实网关 base URL 缺端口")
        .parse()
        .expect("真实网关端口解析失败")
}

/// 原始 tcp 发送任意字节(请求行/头/体完全可控，可构造 CL 谎报、chunked、CRLF、
/// TRACE/CONNECT 等 oneshot 无法表达的协议级攻击)，返回，状态码, 全部响应字节。
/// 读取直到连接关闭或 300ms 读超时(上限 5s)。
pub async fn raw_http(port: u16, raw: &[u8]) -> (u16, Vec<u8>) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .expect("raw TCP 连接失败");
    let _ = stream.set_nodelay(true);
    stream.write_all(raw).await.expect("raw 请求发送失败");
    let mut buf = Vec::new();
    let mut tmp = [0u8; 16384];
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    loop {
        let remain = deadline.saturating_duration_since(std::time::Instant::now());
        if remain.is_zero() {
            break;
        }
        match tokio::time::timeout(std::time::Duration::from_millis(300), stream.read(&mut tmp))
            .await
        {
            Ok(Ok(n)) if n > 0 => buf.extend_from_slice(&tmp[..n]),
            _ => break, // EOF / 错误 / 读超时，结束采集
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

/// 就绪探测专用 raw http，非致命，连接被拒，服务未监听/未就绪返回，0, 空，
/// 供就绪轮询循环使用，raw_http 对 ConnectionRefused 直接 panic，网关 spawn 后
/// 首次探测与 TcpListener::bind 存在竞态(低端容器/慢 io 下必现，本地快机偶发)，
/// 轮询阶段必须把"未就绪"当普通重试而非失败。就绪后正式断言仍走 raw_http。
pub async fn raw_http_probe(port: u16, raw: &[u8]) -> (u16, Vec<u8>) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut stream = match tokio::net::TcpStream::connect(("127.0.0.1", port)).await {
        Ok(s) => s,
        Err(_) => return (0, Vec::new()),
    };
    let _ = stream.set_nodelay(true);
    if stream.write_all(raw).await.is_err() {
        return (0, Vec::new());
    }
    let mut buf = Vec::new();
    let mut tmp = [0u8; 16384];
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    loop {
        let remain = deadline.saturating_duration_since(std::time::Instant::now());
        if remain.is_zero() {
            break;
        }
        match tokio::time::timeout(std::time::Duration::from_millis(300), stream.read(&mut tmp))
            .await
        {
            Ok(Ok(n)) if n > 0 => buf.extend_from_slice(&tmp[..n]),
            _ => break, // EOF / 错误 / 读超时，结束采集
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

// — http/2 详情测试工具(tests/http2_detail.rs 使用，tests/http2.rs 保持自带实现)，—
// 全部 helper 服务于，tls serve 拉起、rcgen 自签证书、tls/h1/h2 客户端、就绪轮询。

/// 探测一个空闲端口(bind :0 取端口后释放，serve 真正绑定前有竞态窗口，测试内可接受)
pub fn probe_port() -> u16 {
    let probe = std::net::TcpListener::bind("127.0.0.1:0").expect("端口探测绑定失败");
    let port = probe.local_addr().expect("端口探测失败").port();
    drop(probe);
    port
}

/// rcgen 自签证书(SAN localhost)写入临时 PEM，返回，cert_path, key_path
pub fn write_test_cert(dir: &std::path::Path) -> (std::path::PathBuf, std::path::PathBuf) {
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
pub fn client_roots(cert_path: &std::path::Path) -> rustls::RootCertStore {
    let mut roots = rustls::RootCertStore::empty();
    for c in rustls_pemfile::certs(&mut BufReader::new(
        std::fs::File::open(cert_path).expect("读服务端证书"),
    )) {
        roots.add(c.expect("证书解析")).expect("根证书加入失败");
    }
    roots
}

pub fn client_tls_config(roots: &rustls::RootCertStore, alpn: &[Vec<u8>]) -> rustls::ClientConfig {
    let mut cfg = rustls::ClientConfig::builder()
        .with_root_certificates(roots.clone())
        .with_no_client_auth();
    cfg.alpn_protocols = alpn.to_vec();
    cfg
}

/// tls 连接单次尝试，连接 + 握手，失败返回 io/rustls 错误(不 panic)
async fn tls_connect_once(
    port: u16,
    roots: &rustls::RootCertStore,
    alpn: &[Vec<u8>],
) -> std::io::Result<(
    tokio_rustls::client::TlsStream<tokio::net::TcpStream>,
    Option<Vec<u8>>,
)> {
    let tcp = tokio::net::TcpStream::connect(("127.0.0.1", port)).await?;
    let cfg = client_tls_config(roots, alpn);
    let connector = tokio_rustls::TlsConnector::from(Arc::new(cfg));
    let server_name = rustls::pki_types::ServerName::try_from("localhost")
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidInput, "invalid server name"))?
        .to_owned();
    let tls = connector.connect(server_name, tcp).await?;
    let negotiated = tls.get_ref().1.alpn_protocol().map(|a| a.to_vec());
    Ok((tls, negotiated))
}

/// tls 连接(指定 alpn 清单)，返回 tls 流与握手协商出的 alpn
///
/// 对"连接/握手阶段"的瞬时错误做有限重试，`cargo test` 全量并行(18 个测试
/// 二进制同时起多个 tls 监听服务)时，Windows 下高负载偶发握手被对端 RST
/// (ConnectionReset，os error 10054)，重试仅覆盖握手前的瞬时失败，
/// 握手成功即原样返回，全部尝试耗尽后以最后一次错误 panic。
pub async fn tls_connect(
    port: u16,
    roots: &rustls::RootCertStore,
    alpn: &[Vec<u8>],
) -> (
    tokio_rustls::client::TlsStream<tokio::net::TcpStream>,
    Option<Vec<u8>>,
) {
    // 全量并行时多套 tls 监听服务瞬时争用(连接被拒/被 RST)，重试窗口放宽至
    // 5 次 × 500ms ≈ 2.5s，覆盖 spawn 后 bind 竞态与高负载瞬时拒绝
    const MAX_ATTEMPTS: usize = 5;
    let mut last_err: Option<std::io::Error> = None;
    for attempt in 0..MAX_ATTEMPTS {
        match tls_connect_once(port, roots, alpn).await {
            Ok(res) => return res,
            Err(e) => {
                last_err = Some(e);
                if attempt + 1 < MAX_ATTEMPTS {
                    tokio::time::sleep(std::time::Duration::from_millis(500)).await;
                }
            }
        }
    }
    panic!("TLS 握手失败: {:?}", last_err.unwrap());
}

/// tls raw http/1.1 非致命探测，连接被拒/握手失败返回 None，服务未就绪，
/// 供就绪轮询循环重试，就绪后正式断言仍走 tls_raw_h1，失败须立即暴露
pub async fn tls_raw_h1_probe(
    port: u16,
    roots: &rustls::RootCertStore,
    raw: &[u8],
) -> Option<String> {
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

/// tls 连接(alpn http/1.1)，发原始 http/1.1 请求，返回全部响应文本
pub async fn tls_raw_h1(port: u16, roots: &rustls::RootCertStore, raw: &[u8]) -> String {
    let (mut tls, _) = tls_connect(port, roots, &[b"http/1.1".to_vec()]).await;
    tls.write_all(raw).await.expect("raw 请求发送失败");
    let mut buf = Vec::new();
    tls.read_to_end(&mut buf).await.expect("响应读取失败");
    String::from_utf8_lossy(&buf).into_owned()
}

/// h2 客户端(alpn 仅 h2)，返回，协商 alpn, SendRequest
pub async fn h2_connect(
    port: u16,
    roots: &rustls::RootCertStore,
) -> (Option<Vec<u8>>, h2::client::SendRequest<Bytes>) {
    let (tls, alpn) = tls_connect(port, roots, &[b"h2".to_vec()]).await;
    let (client, conn) = h2::client::handshake(tls).await.expect("h2 握手失败");
    tokio::spawn(async move {
        let _ = conn.await;
    });
    (alpn, client)
}

/// 单个 h2 请求(无 body)，返回状态码与响应体
pub async fn h2_request(
    client: &mut h2::client::SendRequest<Bytes>,
    method: &str,
    uri: &str,
    headers: &[(&str, &str)],
) -> (u16, Vec<u8>) {
    let mut req = axum::http::Request::builder()
        .method(method)
        .uri(uri)
        .body(())
        .expect("h2 请求构造失败");
    for (k, v) in headers {
        req.headers_mut().insert(
            axum::http::HeaderName::from_bytes(k.as_bytes()).expect("h2 头名构造失败"),
            axum::http::HeaderValue::from_str(v).expect("h2 头构造失败"),
        );
    }
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
    (status, buf)
}

/// spawn fms_rgw::serve(cfg.listen 覆盖为探测端口)，返回端口与 serve 任务句柄。
/// serve 返回 Box<dyn Error> 非 Send，不能直接作 tokio::spawn 输出，统一转 String。
pub async fn spawn_serve(cfg: Config) -> (u16, tokio::task::JoinHandle<Result<(), String>>) {
    spawn_serve_with_shutdown(cfg, None).await
}

/// 可注入关闭信号的 serve 启动，external_shutdown=Some，rx 时进程内触发优雅关闭
///，优雅关闭完整路径测试用，None 时与 spawn_serve 等价(os 信号触发)。
pub async fn spawn_serve_with_shutdown(
    cfg: Config,
    external_shutdown: Option<tokio::sync::watch::Receiver<()>>,
) -> (u16, tokio::task::JoinHandle<Result<(), String>>) {
    let port = probe_port();
    let mut cfg = cfg;
    cfg.server.listen = format!("127.0.0.1:{port}");
    let state = fms_rgw::build_state(cfg).expect("测试 AppState 构建失败");
    let handle = tokio::spawn(async move {
        fms_rgw::serve_with_shutdown(state, external_shutdown)
            .await
            .map_err(|e| e.to_string())
    });
    (port, handle)
}

/// 就绪轮询，tls 模式 raw http/1.1 GET /healthz 应回 200(10s 上限，未就绪则 panic)
pub async fn wait_ready_tls(port: u16, roots: &rustls::RootCertStore) {
    let req = b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    for _ in 0..100 {
        if tls_raw_h1_probe(port, roots, req)
            .await
            .is_some_and(|s| s.starts_with("HTTP/1.1 200"))
        {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    panic!("TLS 网关 10s 内未就绪(/healthz 非 200)");
}

/// 就绪轮询，明文(allow_insecure_fallback)模式 raw GET /healthz 应回 200(10s 上限)
pub async fn wait_ready_plain(port: u16) {
    let req = b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    for _ in 0..100 {
        let (s, _) = raw_http_probe(port, req).await;
        if s == 200 {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    panic!("明文网关 10s 内未就绪(/healthz 非 200)");
}
