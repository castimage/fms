//! `tests/security_web.rs`，Web攻击面测试(注入/协议/信息泄露/ws/cors/`Host`头)
//!
//! 范围， sql注入矩阵、路径穿越矩阵、 CRLF、命令注入、请求体超限、
//!  http方法滥用、超长url/头、敏感文件枚举、错误信息泄露、
//!  ws攻击、 cors、 `Host`头、 `/api`前缀绕过、静态穿越。
//!
//! 方法，oneshot直调`build_router`，mock上游断言"攻击是否到达上游"，防绕过核心
//! + 真实网关raw tcp，协议层攻击，CL谎报/chunked/TRACE/CONNECT/超长头/CRLF。
//!
//! 安全纪律，攻击面用例断言"攻击不得到达上游"，mock上游零命中，任何行为回退
//! 都应以拦截结果为准。

mod common;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use common::*;
use serde_json::Value;
use uuid::Uuid;

/// 与`common::test_config`一致的测试密钥
const JWT_SECRET: &[u8] = b"test-jwt-secret";
const SUB: &str = "3f9a2c1d-0000-4000-8000-000000000002";
/// 与`StaticFixture`写入的`index.html`标记一致
const INDEX_MARKER: &str = "INDEX-MARKER";
/// 与`StaticFixture`写入的 .env内容标记一致
const ENV_SECRET: &str = "SECRET=leak-me";

fn admin_token() -> String {
    make_jwt(SUB, "operator", 3600, JWT_SECRET)
}

//  sql注入

/// 【攻击】sql注入全变体矩阵，经典or 1=1、大小写混写union select、sleep/benchmark
/// 时间盲注、注释-- /*、分号堆叠、编码%27、制表符/换行变体、全角引号+恒等式。
/// 【防护层】filter层`mw_input_filter` 调用 `check_sql_injection_path`(percent-decode后
/// 大小写不敏感浅特征)，任一命中则 400/40001。
/// 【预期】全部400 + code 40001 + mock上游零命中，拦截在filter层，绝不透传。
#[tokio::test]
async fn sql_injection_matrix_400_and_no_upstream() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let payloads = [
        "/api/gl/vouchers?q=%27%20or%201%3D1%20--", // 经典 ' or 1=1 --
        "/api/gl/vouchers?q=1%27%20OR%20%271%27%3D%271", // 大小写混写 ' OR '1'='1
        "/api/gl/vouchers?q=UnIoN%20SeLeCt%20name%2Cpwd", // 大小写混写union select
        "/api/gl/vouchers?q=%73leep(5)",            // 编码sleep(
        "/api/gl/vouchers?q=benchmark(1000%2Cmd5(1))", // benchmark时间盲注
        "/api/gl/vouchers?q=x%27--%09comment",      // -- + 制表符注释
        "/api/gl/vouchers?q=x%27%2F*comment*%2F",   // /* */ 注释
        "/api/gl/vouchers?q=1%27%3Bdrop%20table%20users", // 分号堆叠 ;drop
        "/api/gl/vouchers?q=1%27%0Aor%0A1%3D1",     // 换行变体
        "/api/gl/vouchers?q=1%27%09or%091%3D1",     // 制表符变体
        "/api/gl/vouchers?q=%EF%BC%87%20or%201%3D1", // 全角引号 ＇+ ASCII恒等式
        "/api/gl/vouchers/1%27%20or%201%3D1--",     // path载体，非query
    ];
    for uri in payloads {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "SQL 注入应 400：{uri}");
        assert_eq!(body["code"], 40001, "错误码应 40001：{uri}");
    }
    assert!(
        mock.take_filtered("/api/gl/vouchers").is_empty(),
        "SQL 注入不得到达上游(filter 层拦截)"
    );
}

/// 【攻击】sql注入负例，合法查询参数不得误伤，filter放行后走到 auth 层 401。
/// 【防护层】filter层浅特征不命中则到 auth 层 401，证明未被filter误拦，且未落上游。
/// 【预期】401 + code 40100，非400，mock上游零命中。
#[tokio::test]
async fn sql_injection_benign_params_not_blocked() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    for uri in [
        "/api/gl/vouchers?page=1&size=20",
        "/api/gl/vouchers?status=active&city=shanghai",
        "/api/gl/vouchers?q=order%20by%20name",
        "/api/gl/vouchers?name=alice&sort=desc",
    ] {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(
            status,
            StatusCode::UNAUTHORIZED,
            "合法参数不应被 filter 误伤：{uri}"
        );
        assert_eq!(body["code"], 40100, "应走到 auth 层：{uri}");
    }
    assert!(
        mock.take_filtered("/api/gl/vouchers").is_empty(),
        "未认证请求不得到达上游"
    );
}

/// 【测试】sql注入双编码%2527%2520or%25201%253D1，
/// `decode_for_check`循环解码，≤3轮后再做特征检查则拦截 400。
/// 【预期】400/40001 + mock上游零命中，拦截在filter层，绝不透传。
#[tokio::test]
async fn sql_injection_double_encoded_reaches_upstream() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let uri = "/api/gl/vouchers?q=%2527%2520or%25201%253D1";
    let resp = oneshot(&router, with_auth(request("GET", uri), &admin_token())).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::BAD_REQUEST,
        "双编码 SQLi 应被 filter 拦截(D1 修复)"
    );
    assert_eq!(body["code"], 40001, "错误码应 40001");
    assert!(
        mock.take_filtered("/api/gl/vouchers").is_empty(),
        "双编码载荷不得到达上游"
    );
}

/// 【攻击】Unicode全角数字恒等式，１＝１，sql引擎不解释全角字符为语法，属惰性载荷，
/// filter不拦截，无误伤，透传上游，无任何副作用。
/// 【防护层】无需拦截，全角非sql语法，断言不误伤 + 载荷完整性。
/// 【预期】200 + mock收到原始全角query。
#[tokio::test]
async fn sql_injection_unicode_fullwidth_inert() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    // /api/nt/ws非升级http请求需jwt，用有效token便于断言载荷完整性
    let uri = "/api/nt/ws?q=%EF%BC%91%EF%BC%9D%EF%BC%91%20or%20x"; // １＝１ or x
    let resp = oneshot(&router, with_auth(request("GET", uri), &admin_token())).await;
    assert_eq!(resp.status(), StatusCode::OK, "全角载荷不应被误伤为 400");
    let hits = mock.take_filtered("/api/nt/ws");
    assert_eq!(hits.len(), 1);
    assert!(
        hits[0]
            .query
            .as_deref()
            .unwrap_or_default()
            .contains("%EF%BC%91"),
        "全角载荷应原样透传(不产生业务副作用)"
    );
}

// 路径穿越

/// 【攻击】路径穿越全变体，../、..%2f、%2e%2e%2f，大小写十六进制混写、混合编码。
/// 【防护层】filter层`normalize_path`，单遍percent-decode后拒绝 ".." 路径段则 400。
/// 【预期】全部400 + code 40001 + mock上游零命中。
#[tokio::test]
async fn path_traversal_variants_400() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let uris = [
        "/api/gl/../etc/passwd",            // 明文 ..
        "/api/gl/%2e%2e/%2e%2e/etc/passwd", // %2e%2e编码
        "/..%2f..%2fetc%2fpasswd",          // ..%2f混写
        "/api/%2E%2E%2Fetc/passwd",         // 大写十六进制
        "/api/gl/%2e%2E%2f..%2Fx",          // 大小写混写
        "/api/gl/../../../../etc/shadow",   // 深层穿越
    ];
    for uri in uris {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "路径穿越应 400：{uri}");
        assert_eq!(body["code"], 40001, "错误码应 40001：{uri}");
    }
    assert!(
        mock.take_filtered("/api/gl").is_empty(),
        "穿越请求不得到达上游"
    );
}

/// 【攻击】反斜杠穿越 \..\，Windows风格，`http::Uri`接受反斜杠时由filter拒绝，
/// `Uri`解析层拒绝时，http crate防护走真实网关raw tcp复验服务器层行为。
/// 【防护层】filter `normalize_path`，\ 直接拒绝或http crate `Uri`解析。
/// 【预期】400或401，反斜杠被解析层拒绝，不得200 / 不得到达上游。
#[tokio::test]
async fn path_traversal_backslash_400() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let built = Request::builder()
        .method("GET")
        .uri("/api/gl\\..\\..\\etc\\passwd")
        .body(Body::empty());
    match built {
        Ok(req) => {
            let resp = oneshot(&router, req).await;
            assert_eq!(
                resp.status(),
                StatusCode::BAD_REQUEST,
                "反斜杠路径应被 filter 拒绝(400)"
            );
        }
        Err(_) => {
            // http crate拒绝含反斜杠的Uri，应用层构造即失败，raw tcp复验
            let gw = real_gateway();
            let (status, _) = raw_http(
                real_port(gw),
                b"GET /api/gl\\..\\..\\etc\\passwd HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            )
            .await;
            assert_eq!(status, 400, "反斜杠路径应被服务器拒绝");
        }
    }
    assert!(
        mock.take_filtered("/api/gl").is_empty(),
        "反斜杠穿越不得到达上游"
    );
}

/// 【攻击】Windows盘符绝对路径，C:/...直读静态目录外文件(`win.ini`在Windows恒存在)。
/// 【防护层】static层canonicalize纵深防御，解析目标越出`static_dir` 则 404。
/// 【预期】404(绝不输出`win.ini`内容)，Windows平台限定。
#[cfg(windows)]
#[tokio::test]
async fn drive_letter_static_404() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    let resp = oneshot(&router, request("GET", "/C:/windows/win.ini")).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "盘符绝对路径越出静态根 -> 404"
    );
    assert_eq!(body["code"], 40400);
    let text = body.to_string();
    assert!(
        !text.contains("; for 16-bit app support"),
        "不得泄露 win.ini 内容(canonicalize 拦截)"
    );
}

/// 【攻击】UNC风格 //server/share与超长utf-8编码点变体(不构成实际穿越，
/// 但须确认无泄露，前者仅spa fallback，后者即使lossy解码也不得触碰dotfile)。
/// 【防护层】`normalize_path`折叠连续斜杠 + static层敏感扩展名黑名单，*.txt拦截
/// + dotfile段拒绝，原始路径段检查。
/// 【预期】UNC变体404，.txt敏感扩展名，编码点变体404，dotfile检查。
#[tokio::test]
async fn unc_and_unicode_variants_no_leak() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    // UNC风格，.txt属敏感扩展名黑名单则 404，不落spa fallback
    let resp = oneshot(&router, request("GET", "//server/share/passwords.txt")).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "UNC 变体 *.txt 应 404(D2 敏感扩展名)"
    );
    assert_eq!(body["code"], 40400);
    // 超长utf-8编码点，lossy后不构成 .. 段，dotfile段检查按原始路径仍拦 .env
    let resp = oneshot(&router, request("GET", "/assets/..%c0%af..%c0%af/.env")).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "编码点变体不得触碰 .env(dotfile 检查)"
    );
    assert_eq!(body["code"], 40400);
}

/// 【测试】双编码穿越%252e%252e%252f，
/// filter单遍解码时解码后为%2e%2e%2f不构成 ".." 段而透传上游。
/// `normalize_path`循环解码，≤3轮后拒绝 .. 段则 400。
/// 【预期】400/40001 + mock上游零命中。
#[tokio::test]
async fn double_encoded_traversal_reaches_upstream() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let uri = "/api/gl/..%252f..%252fetc%252fpasswd";
    let resp = oneshot(&router, with_auth(request("GET", uri), &admin_token())).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::BAD_REQUEST,
        "双编码穿越应被 filter 拦截(D1 修复)"
    );
    assert_eq!(body["code"], 40001, "错误码应 40001");
    assert!(
        mock.take_filtered("/api/gl").is_empty(),
        "双编码穿越不得到达上游"
    );
}

//  CRLF注入

/// 【攻击】%0d%0a编码CRLF注入path/query/头值，网关不透传为响应头(hyper头值
/// 在协议层不可含原始CR/LF，响应头仅由网关+上游构造)。
/// 【防护层】hyper/http crate头值校验 + 网关不反射客户端头到响应。
/// 【预期】请求正常转发，载荷原样给上游，响应头绝无set-cookie等注入头。
#[tokio::test]
async fn crlf_encoded_payload_no_response_header_injection() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    // query注入尝试
    let resp = oneshot(
        &router,
        with_auth(
            request("GET", "/api/gl/vouchers?q=x%0d%0aSet-Cookie:%20injected=1"),
            &admin_token(),
        ),
    )
    .await;
    let (status, headers, _) = collect(resp).await;
    assert_eq!(status, StatusCode::OK);
    assert!(
        !headers.contains_key("set-cookie"),
        "query CRLF 不得注入响应头"
    );
    // 头值注入尝试，%0d%0a为普通文本，无原始CR/LF无法逃逸超文本解析
    let mut req = with_auth(request("GET", "/api/gl/vouchers"), &admin_token());
    req.headers_mut()
        .insert("x-evil", "a%0d%0aSet-Cookie: injected=1".parse().unwrap());
    let resp = oneshot(&router, req).await;
    let (_, headers, _) = collect(resp).await;
    assert!(
        !headers.contains_key("set-cookie"),
        "头值 CRLF 不得注入响应头"
    );
    // 上游收到的是编码文本，原样透传，未发生解码-重编码逃逸
    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(hits.len(), 2, "两个请求均应转发");
    assert!(
        hits[0]
            .query
            .as_deref()
            .unwrap_or_default()
            .contains("%0d%0a"),
        "上游应收到原样编码 query"
    );
}

/// 【攻击】真实服务器层CRLF，客户端可拼出的最大逃逸 = 多个头，超文本解析，
/// 网关不得把任何客户端头回显到响应，响应头集合严格由网关/上游控制。
/// 【防护层】hyper解析 + 网关不反射，断言响应无注入头。
/// 【预期】200(匿名`/api/nt/ws`转发)且响应头无set-cookie / 无x-evil回显。
#[tokio::test]
async fn crlf_raw_tcp_header_split_no_injection() {
    let gw = real_gateway();
    let port = real_port(gw);
    //，/api/nt/ws非升级请求需jwt(Authorization)，此处携带有效token保证
    // 请求被正常处理转发，仍断言响应无注入头
    let token = make_jwt(SUB, "operator", 3600, b"sec-web-jwt-secret");
    let raw = format!(
        "GET /api/nt/ws?x=1 HTTP/1.1\r\n\
Host: 127.0.0.1\r\n\
Authorization: Bearer {token}\r\n\
X-Evil: a\r\n\
Set-Cookie: injected=1\r\n\
X-Crlf: b%0d%0aSet-Cookie: injected=2\r\n\
Connection: close\r\n\r\n"
    );
    let (status, resp_bytes) = raw_http(port, raw.as_bytes()).await;
    assert_eq!(status, 200, "CRLF 变体请求应正常处理(无协议错误)");
    let resp_text = String::from_utf8_lossy(&resp_bytes).to_lowercase();
    assert!(
        !resp_text.contains("set-cookie"),
        "客户端注入的 set-cookie 不得出现在响应中"
    );
    assert!(!resp_text.contains("x-evil"), "客户端头不得被回显到响应");
}

/// 【攻击】访问日志CRLF/日志注入，path携带%0d%0a 时日志 jsonl 必须转义
/// (`serde_json`转义 \n)，不得出现换行逃逸伪造日志行。
/// 【防护层】`log.rs` `serde_json::to_string`转义 + 长度截断。
/// 【预期】日志每行均为合法json，含标记uuid的行path字段完整记录编码序列。
#[tokio::test]
async fn crlf_access_log_json_integrity() {
    let gw = real_gateway();
    let port = real_port(gw);
    let marker = Uuid::now_v7().to_string();
    let req = format!(
        "GET /api/auth/login?m={marker}%0d%0aSet-Cookie:%20pwn HTTP/1.1\r\n\
Host: 127.0.0.1\r\nUser-Agent: evil%0d%0aX: 1\r\nConnection: close\r\n\r\n"
    );
    let (status, _) = raw_http(port, req.as_bytes()).await;
    assert_eq!(status, 200, "匿名登录路径应转发成功");
    // 稍候日志刷盘(BufWriter flush在write内，但双写与请求并发，轮询至出现标记行)
    let mut found = false;
    for _ in 0..50 {
        let content = std::fs::read_to_string(&gw.log_file).unwrap_or_default();
        for line in content.lines().filter(|l| !l.trim().is_empty()) {
            let v: Value = serde_json::from_str(line)
                .expect("访问日志每行必须为合法 JSON(CRLF 注入应被转义)");
            if v["path"].as_str().is_some_and(|p| p.contains(&marker)) {
                assert!(
                    v["path"].as_str().unwrap().contains("%0d%0a"),
                    "path 应记录编码序列(JSON 转义后无换行逃逸)"
                );
                found = true;
            }
        }
        if found {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    assert!(found, "访问日志应出现带标记 uuid 的条目");
}

// 命令注入

/// 【攻击】命令注入载荷(;id / |cat / $，id / `id` / && / rm -rf)置于json body与
/// query，网关是纯Rust代理，无shell执行面，filter无命令注入特征，不误伤，
/// 载荷须原样透传上游，防篡改断言，由后端做命令注入防御。
/// 【防护层】网关不执行/不解析，无执行面，断言，不400误伤 + 字节级原样透传 + 200。
/// 【预期】200 + mock收到与发送完全一致的载荷，无解码/截断副作用。
#[tokio::test]
async fn command_injection_payloads_passthrough_no_false_positive() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let payload = r#"{"name":"test","cmd":"; id; cat /etc/passwd | bash; $(id); `id`; whoami && echo pwned; rm -rf /tmp/x"}"#;
    let mut req = with_auth(request("POST", "/api/gl/vouchers"), &admin_token());
    req.headers_mut()
        .insert("content-type", "application/json".parse().unwrap());
    *req.body_mut() = Body::from(payload.to_string());
    let resp = oneshot(&router, req).await;
    let (status, _, _) = collect(resp).await;
    assert_eq!(status, StatusCode::OK, "命令注入载荷不应被网关误伤为 400");
    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(hits.len(), 1);
    assert_eq!(
        hits[0].body_str(),
        payload,
        "载荷必须字节级原样到达上游(网关不解析不执行)"
    );
    // query载体，断言原始编码序列原样透传
    let uri = "/api/gl/vouchers?q=%3Bid%7Ccat%20%2Fetc%2Fpasswd%24%28id%29%26%26echo%20pwned";
    let resp = oneshot(&router, with_auth(request("GET", uri), &admin_token())).await;
    assert_eq!(resp.status(), StatusCode::OK, "query 命令载荷不应误伤");
    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(hits.len(), 1);
    let q = hits[0].query.as_deref().unwrap_or_default();
    assert!(
        q.contains("%3Bid") && q.contains("%26%26") && q.contains("%24%28id%29"),
        "query 载荷原样透传，实际 {q}"
    );
    // 对照，sql型命令载荷，;drop仍须被filter拦截
    let resp = oneshot(
        &router,
        with_auth(
            request("GET", "/api/gl/vouchers?q=1%3Bdrop%20table%20users"),
            &admin_token(),
        ),
    )
    .await;
    assert_eq!(resp.status(), StatusCode::BAD_REQUEST, "SQL 型载荷仍应被拦");
}

// 请求体超限

/// 【攻击】`Content-Length`谎报小值，100但实际body 11MB，CL不可信。
/// 【防护层】sign层`mw_sign`，实际缓冲长度`check_body_size`，10MB上限则 413/40001。
/// 【预期】413 + mock零命中，对照100KB正常体则 200 转发。
#[tokio::test]
async fn content_length_lied_small_body_large_413() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let big = "a".repeat(11 * 1024 * 1024);
    let mut req = with_auth(request("POST", "/api/gl/vouchers"), &admin_token());
    req.headers_mut()
        .insert("content-type", "application/json".parse().unwrap());
    req.headers_mut()
        .insert("content-length", "100".parse().unwrap()); // 谎报小值
    *req.body_mut() = Body::from(big);
    let resp = oneshot(&router, req).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE, "实际超限应 413");
    assert_eq!(body["code"], 40001);
    assert!(
        mock.take_filtered("/api/gl/vouchers").is_empty(),
        "超限体不得到达上游"
    );
    // 对照，正常体不误伤
    let ok_body = r#"{"a":1}"#;
    let mut req = with_auth(request("POST", "/api/gl/vouchers"), &admin_token());
    req.headers_mut()
        .insert("content-type", "application/json".parse().unwrap());
    *req.body_mut() = Body::from(ok_body.to_string());
    let resp = oneshot(&router, req).await;
    assert_eq!(resp.status(), StatusCode::OK);
}

/// 【攻击】chunked传输编码 + 累计12MB超限(绕过`Content-Length`检查路径)。
/// 【防护层】hyper解析chunked 后由 sign层`Limited` 收集，10MB+1 超限则 413。
/// 【预期】413 + mock零命中。
#[tokio::test]
async fn chunked_oversize_413() {
    let gw = real_gateway();
    let port = real_port(gw);
    let token = make_jwt(SUB, "operator", 3600, b"sec-web-jwt-secret");
    // 12 × 1MB chunk，每chunk 8KB拼接，避免单字符串过大内存峰值
    let chunk = "c".repeat(1024 * 1024);
    let mut body = String::with_capacity(13 * 1024 * 1024);
    for _ in 0..12 {
        body.push_str(&format!("{:x}\r\n", chunk.len()));
        body.push_str(&chunk);
        body.push_str("\r\n");
    }
    body.push_str("0\r\n\r\n");
    let raw = format!(
        "POST /api/gl/vouchers HTTP/1.1\r\n\
Host: 127.0.0.1\r\n\
Content-Type: application/json\r\n\
Authorization: Bearer {token}\r\n\
Transfer-Encoding: chunked\r\n\
Connection: close\r\n\r\n{body}"
    );
    let (status, _) = raw_http(port, raw.as_bytes()).await;
    assert_eq!(status, 413, "chunked 超限应 413");
    // 真实网关共享mock，健康探测打/internal/health，过滤噪声后断言无/api命中
    assert!(
        gw.mock.take_filtered("/api/gl/vouchers").is_empty(),
        "超限体不得到达上游"
    );
}

/// 【攻击】multipart/form-data大文件上传，声明小CL + 实际11MB。
/// 【防护层】sign层实际缓冲长度超限则 413。
/// 【预期】413 + mock零命中。
#[tokio::test]
async fn multipart_big_file_413() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let file = "x".repeat(11 * 1024 * 1024);
    let body = format!(
        "-----b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.bin\"\r\n\r\n{file}\r\n-----b--\r\n"
    );
    let mut req = with_auth(request("POST", "/api/gl/vouchers"), &admin_token());
    req.headers_mut().insert(
        "content-type",
        "multipart/form-data; boundary=---b".parse().unwrap(),
    );
    req.headers_mut()
        .insert("content-length", "200".parse().unwrap()); // 谎报
    *req.body_mut() = Body::from(body);
    let resp = oneshot(&router, req).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::PAYLOAD_TOO_LARGE,
        "multipart 超限应 413"
    );
    assert_eq!(body["code"], 40001);
    assert!(
        mock.take_filtered("/api/gl/vouchers").is_empty(),
        "超限 multipart 不得到达上游"
    );
}

//  http方法滥用

/// 【攻击】TRACE / CONNECT，代理/中间件典型滥用向量，xst反射、隧道。
/// 【防护层】TRACE被filter层显式禁用则 405/40500(，认证与否均拒绝，
/// 反射回显面不转移上游)，CONNECT无对应路由则 405，不落上游不落静态。
/// 【预期】TRACE 则 405，CONNECT 则 405，攻击路径mock零命中。
#[tokio::test]
async fn trace_and_connect_rejected_4xx() {
    let gw = real_gateway();
    let port = real_port(gw);
    // TRACE，filter层显式禁用，未认证也先于auth返回405
    let (status, _) = raw_http(
        port,
        b"TRACE /api/gl/vouchers HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
    )
    .await;
    assert_eq!(
        status, 405,
        "TRACE 应 405(filter 层显式禁用，F10)，实际 {status}"
    );
    // CONNECT，authority-form目标，隧道建立尝试
    let (status, resp_bytes) = raw_http(
        port,
        b"CONNECT evil.example.com:443 HTTP/1.1\r\nHost: evil.example.com:443\r\nConnection: close\r\n\r\n",
    )
    .await;
    assert_eq!(
        status, 405,
        "CONNECT 应 405(无路由、不建立隧道)，实际 {status}"
    );
    let resp_text = String::from_utf8_lossy(&resp_bytes);
    assert!(
        !resp_text.contains("200 Connection established"),
        "CONNECT 不得建立隧道"
    );
    // 注意，健康探测打/internal/health，按攻击路径精确过滤
    assert!(
        gw.mock.take_filtered("/api/gl/vouchers").is_empty(),
        "TRACE/CONNECT 不得到达上游"
    );
}

/// 【攻击】OPTIONS任意路径(无`Origin`非预检)，不得落静态/上游。
/// 【防护层】`CorsLayer`最外层短路全部OPTIONS(设计响应，无`Origin` 则 200 空体、
/// 无allow-origin回显则浏览器跨域不可读)，不进入api链、不落上游。
/// 【预期】200空体 + 无access-control-allow-origin + mock零命中。
#[tokio::test]
async fn options_api_path_without_origin_denied() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let resp = oneshot(&router, request("OPTIONS", "/api/gl/vouchers")).await;
    let (status, headers, body) = collect(resp).await;
    assert_eq!(
        status,
        StatusCode::OK,
        "无 Origin 的 OPTIONS 由 CorsLayer 短路(设计响应)"
    );
    assert!(
        !headers.contains_key("access-control-allow-origin"),
        "无 Origin 的 OPTIONS 不得回显 allow-origin"
    );
    assert!(body.is_empty(), "OPTIONS 短路响应应为空体");
    assert!(
        mock.take_filtered("/api/gl/vouchers").is_empty(),
        "OPTIONS 不得到达上游"
    );
}

/// 【攻击】PUT/DELETE/TRACE打静态路径，不得写/删/回显静态文件。
/// 【防护层】tower-http `ServeDir`仅服务GET/HEAD，其余方法则 405。
/// 【预期】405 + 静态文件内容零泄露。
#[tokio::test]
async fn put_delete_on_static_405() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    for method in ["PUT", "DELETE", "PATCH"] {
        let resp = oneshot(&router, request(method, "/index.html")).await;
        assert_eq!(
            resp.status(),
            StatusCode::METHOD_NOT_ALLOWED,
            "{method} /index.html 应 405(静态只读)"
        );
    }
    // 对照，GET正常200
    let resp = oneshot(&router, request("GET", "/index.html")).await;
    assert_eq!(resp.status(), StatusCode::OK);
}

// 超长url / 头

/// 【攻击】20KB超长path，不得落静态、不得绕过安全链(无显式path长度限制时
/// 由auth层兜底401，hyper层`max_buf_size` 400KB为最终上限，见`w7_overlong_header`)。
/// 【防护层】auth层401，未认证+ filter不误伤，mock零命中。
/// 【预期】401 + 未到达上游。
#[tokio::test]
async fn overlong_path_20k_rejected_at_auth() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let long = format!("/api/gl/vouchers/{}", "a".repeat(20 * 1024));
    let resp = oneshot(&router, request("GET", &long)).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::UNAUTHORIZED,
        "超长 path 应 401(未认证)"
    );
    assert_eq!(body["code"], 40100);
    assert!(
        mock.take_filtered("/api/gl/vouchers").is_empty(),
        "超长 path 不得到达上游"
    );
}

/// 【攻击】600KB超大头值，hyper http1 `max_buf_size`，400KB 上限。
/// 【防护层】hyper协议层超限则 431 Request Header Fields Too Large。
/// 【预期】431，或4xx，连接不产生任何业务处理。
#[tokio::test]
async fn overlong_header_431() {
    let gw = real_gateway();
    let port = real_port(gw);
    let big = "b".repeat(600 * 1024);
    let raw = format!(
        "GET /api/gl/vouchers HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Big: {big}\r\nConnection: close\r\n\r\n"
    );
    let (status, _) = raw_http(port, raw.as_bytes()).await;
    assert!(
        status == 431 || (400..500).contains(&status),
        "超大头应 431(hyper 上限)，实际 {status}"
    );
    assert!(
        gw.mock.take_filtered("/api/gl/vouchers").is_empty(),
        "超长头请求不得到达上游"
    );
}

// 静态敏感文件枚举

/// 【攻击】敏感文件枚举，.env/.git/config/.DS_Store/*.pem/*.key/.gitignore。
/// 【防护层】static层dotfile段拒绝，.well-known豁免+ 敏感扩展名黑名单则 404。
/// 【预期】全部404 + code 40400，响应体无任何文件内容。
#[tokio::test]
async fn sensitive_file_enumeration_404() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    for uri in [
        "/.env",
        "/.git/config",
        "/.gitignore",
        "/.DS_Store",
        "/private.pem",
        "/server.key",
        "/assets/.hidden",
    ] {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{uri} 应 404");
        assert_eq!(body["code"], 40400, "{uri} 错误码应 40400");
    }
}

/// 【测试】敏感扩展名黑名单不完整，*.sql / *.bak / *.swp /
/// `package.json`原可200下载。黑名单扩充 .sql/.bak/.swp/.log/
/// .`txt/.json/.yaml/.yml/.sh/.bat/.ps1/.toml`/.conf/.cfg/.ini/.local/.example + 备份名。
/// 【预期】全部404/40400，响应体不含任何敏感内容标记。
#[tokio::test]
async fn sensitive_extensions_incomplete() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    let cases = [
        ("/db/backup.sql", "db-secret"),
        ("/secret.sql", "sql-secret"),
        ("/backup.bak", "backup-secret"),
        ("/note.swp", "swap-secret"),
        ("/package.json", "fms_rgw-app"),
    ];
    for (uri, marker) in cases {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(
            status,
            StatusCode::NOT_FOUND,
            "{uri} 应 404(D2 敏感扩展名黑名单)"
        );
        assert_eq!(body["code"], 40400, "{uri} 错误码应 40400");
        let text = body.to_string();
        assert!(
            !text.contains(marker),
            "{uri} 不得泄露敏感内容(标记 {marker} 不得出现)"
        );
    }
}

/// 【攻击】`index.html`存在性探针，公共入口必须可达，正常路径200，
/// 同时确认探针不泄露其他信息。
/// 【防护层】static层正常服务路径。
/// 【预期】/ 与 /index.html 200 + INDEX-MARKER。
#[tokio::test]
async fn index_html_probe_200() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    for uri in ["/", "/index.html"] {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect(resp).await;
        assert_eq!(status, StatusCode::OK, "{uri} 应 200");
        assert!(
            String::from_utf8_lossy(&body).contains(INDEX_MARKER),
            "{uri} 应返回 index.html"
        );
    }
}

// 错误信息泄露

/// 错误响应体不得泄露内部信息，无堆栈/无源码路径/无内部字段/无`Server`头，
/// 体必须恰为{code, message, data, traceId}四字段json，统一响应结构。
fn assert_no_internal_leak(status_expected: i64, body: &str, headers: &axum::http::HeaderMap) {
    let v: Value = serde_json::from_str(body).expect("错误响应应为合法 JSON");
    let obj = v.as_object().expect("错误响应应为 JSON 对象");
    assert_eq!(
        obj.len(),
        4,
        "错误响应体只能含 code/message/data/traceId，实际 {body}"
    );
    assert_eq!(v["code"], status_expected, "错误码不符");
    assert!(v["traceId"].is_string());
    let lower = body.to_lowercase();
    for needle in [
        "panic",
        "backtrace",
        "src/",
        "src\\",
        "lib.rs",
        "C:\\",
        "stack trace",
        ".rs\"",
        "version",
    ] {
        assert!(
            !lower.contains(needle),
            "错误响应泄露内部信息：{needle} -> {body}"
        );
    }
    assert!(
        !headers.contains_key("server"),
        "不得输出 Server 头(版本指纹)"
    );
}

/// 【攻击】触发400/401/404/429/503五类错误，逐类断言无信息泄露 + 无`Server`头。
/// 【防护层】`types::error_response`统一响应体，code/message/traceId三字段，
/// 响应头由`headers::apply_security_headers`统一施加(无`Server`头)。
/// 【预期】各状态码正确 + 无泄露 + 无`Server`头。
#[tokio::test]
async fn error_responses_no_internal_leak() {
    // 400，sql注入
    {
        let (router, _m) = router_with_mock(|_| {}, None).await;
        let resp = oneshot(&router, request("GET", "/api/gl/vouchers?q=%27%20or%201=1")).await;
        let (_, headers, bytes) = collect(resp).await;
        assert_no_internal_leak(40001, &String::from_utf8_lossy(&bytes), &headers);
    }
    // 401，未认证
    {
        let (router, _m) = router_with_mock(|_| {}, None).await;
        let resp = oneshot(&router, request("GET", "/api/gl/vouchers")).await;
        let (_, headers, bytes) = collect(resp).await;
        assert_no_internal_leak(40100, &String::from_utf8_lossy(&bytes), &headers);
    }
    // 404，网关侧，dotfile拒绝与上游侧，mock返回404透传
    {
        let fixture = StaticFixture::new();
        let (router, _m) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
        let resp = oneshot(&router, request("GET", "/.env")).await;
        let (_, headers, bytes) = collect(resp).await;
        assert_no_internal_leak(40400, &String::from_utf8_lossy(&bytes), &headers);
    }
    {
        let (router, mock) = router_with_mock(|_| {}, None).await;
        mock.response.lock().unwrap().status = 404;
        let resp = oneshot(
            &router,
            with_auth(request("GET", "/api/gl/vouchers"), &admin_token()),
        )
        .await;
        let (status, headers, bytes) = collect(resp).await;
        assert_eq!(status, StatusCode::NOT_FOUND, "上游 404 应透传");
        let text = String::from_utf8_lossy(&bytes);
        // 上游404透传体为mock json，非网关错误体，只做泄露面检查
        let lower = text.to_lowercase();
        for needle in ["panic", "backtrace", "src/", "src\\", "C:\\", "stack trace"] {
            assert!(
                !lower.contains(needle),
                "上游 404 透传体泄露内部信息：{needle}"
            );
        }
        assert!(
            !headers.contains_key("server"),
            "404 透传响应不得输出 Server 头"
        );
    }
    // 429，限流，ip维度收紧到每窗2次
    {
        let (router, _m) = router_with_mock(
            |cfg| {
                cfg.limits.ip_per_window = 2;
            },
            None,
        )
        .await;
        for _ in 0..2 {
            let resp = oneshot(
                &router,
                with_ip(request("GET", "/api/auth/login"), "127.0.0.9"),
            )
            .await;
            assert_eq!(resp.status(), StatusCode::OK);
        }
        let resp = oneshot(
            &router,
            with_ip(request("GET", "/api/auth/login"), "127.0.0.9"),
        )
        .await;
        let (_, headers, bytes) = collect(resp).await;
        assert_no_internal_leak(42900, &String::from_utf8_lossy(&bytes), &headers);
    }
    // 503，上游不可达，指向未监听端口
    {
        let (router, mock) = router_with_mock(
            |cfg| {
                cfg.upstream.base_url = "http://127.0.0.1:1".to_string();
            },
            None,
        )
        .await;
        let resp = oneshot(
            &router,
            with_auth(request("GET", "/api/gl/vouchers"), &admin_token()),
        )
        .await;
        let (status, headers, bytes) = collect(resp).await;
        assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE, "上游不可达应 503");
        assert_no_internal_leak(50000, &String::from_utf8_lossy(&bytes), &headers);
        assert!(
            mock.take_filtered("/api/gl/vouchers").is_empty(),
            "不可达上游不应有命中"
        );
    }
    // 成功响应同样无Server头
    {
        let (router, _m) = router_with_mock(|_| {}, None).await;
        let resp = oneshot(&router, request("GET", "/healthz")).await;
        let (_, headers, _) = collect(resp).await;
        assert!(
            !headers.contains_key("server"),
            "成功响应也不得输出 Server 头"
        );
    }
}

//  ws攻击

fn ws_upgrade_req(uri: &str) -> Request<Body> {
    let mut req = request("GET", uri);
    req.headers_mut()
        .insert("upgrade", "websocket".parse().unwrap());
    req.headers_mut()
        .insert("connection", "Upgrade".parse().unwrap());
    req.headers_mut().insert(
        "sec-websocket-key",
        "dGhlIHNhbXBsZSBub25jZQ==".parse().unwrap(),
    );
    req.headers_mut()
        .insert("sec-websocket-version", "13".parse().unwrap());
    req
}

/// 【攻击】无token的ws升级请求，认证必须发生在握手转发之前。
/// 【防护层】`ws_handler`第2步query/`Authorization`验签失败则 401/40100。
/// 【预期】401 + code 40100 + mock上游零命中，认证前置，防绕过核心。
#[tokio::test]
async fn ws_upgrade_without_token_401() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let resp = oneshot(&router, ws_upgrade_req("/api/nt/ws")).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED, "WS 无 token 应 401");
    assert_eq!(body["code"], 40100);
    assert!(
        mock.take_filtered("/api/nt/ws").is_empty(),
        "未认证 WS 不得到达上游"
    );
}

/// 【攻击】无效token query，garbage/过期/错误密钥签名的ws升级。
/// 【防护层】`ws_handler` `verify_jwt` 失败则 401，40100 无效 / 40101 过期。
/// 【预期】401 + mock零命中。
#[tokio::test]
async fn ws_invalid_token_query_401() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let bad_tokens = [
        "not.a.jwt",
        &make_jwt(SUB, "operator", 3600, b"wrong-key"),
        &make_jwt(SUB, "operator", -3600, JWT_SECRET), // 过期
    ];
    for t in bad_tokens {
        let resp = oneshot(&router, ws_upgrade_req(&format!("/api/nt/ws?token={t}"))).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "无效 token 应 401：{t}");
        assert!(
            body["code"]
                .as_i64()
                .is_some_and(|c| c == 40100 || c == 40101)
        );
    }
    assert!(
        mock.take_filtered("/api/nt/ws").is_empty(),
        "无效 token 不得到达上游"
    );
}

/// 【攻击】非`/api/nt/ws`路径携带`Upgrade`头，不得被当作ws处理，误升级/绕过，
/// 应走普通http安全链，无token 401，有token按普通代理转发且hop-by-hop头被剥离。
/// 【防护层】`ws.rs` `is_websocket_request`路径精确匹配 + proxy `strip_hop_by_hop`。
/// 【预期】无token 401，有token 200 + mock收到请求且无upgrade/connection头。
#[tokio::test]
async fn ws_upgrade_header_on_other_path_proxied() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    // 无token 则 401，普通http链认证
    let resp = oneshot(&router, ws_upgrade_req("/api/nt/other")).await;
    assert_eq!(
        resp.status(),
        StatusCode::UNAUTHORIZED,
        "非 WS 路径带 Upgrade 头应走普通链"
    );
    // 有token 则普通代理转发，Upgrade/Connection不得透传上游
    let resp = oneshot(
        &router,
        with_auth(ws_upgrade_req("/api/nt/other"), &admin_token()),
    )
    .await;
    assert_eq!(resp.status(), StatusCode::OK);
    let hits = mock.take_filtered("/api/nt/other");
    assert_eq!(hits.len(), 1);
    assert!(
        hits[0].header("upgrade").is_none(),
        "hop-by-hop Upgrade 头不得透传上游"
    );
    assert!(
        hits[0].header("connection").is_none(),
        "Connection 头不得透传上游"
    );
    // sec-websocket-* 非hop-by-hop，RFC 7230仅connection/upgrade等，保留无害
}

/// 【测试】ws升级`Origin`校验，cswsh防护，握手前校验`Origin` ∈
/// cors白名单，与cors一致，无`Origin`、Flutter/cli放行，非白名单则 403。
/// 【预期】403/40300 + mock上游零命中，拒绝发生在认证之前。
#[tokio::test]
async fn ws_upgrade_origin_not_validated() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let mut req = ws_upgrade_req(&format!(
        "/api/nt/ws?token={}",
        make_jwt(SUB, "operator", 3600, JWT_SECRET)
    ));
    req.headers_mut()
        .insert("origin", "https://evil.example.com".parse().unwrap());
    let resp = oneshot(&router, req).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::FORBIDDEN,
        "非白名单 Origin 的 WS 升级应 403(D3 修复)"
    );
    assert_eq!(body["code"], 40300, "错误码应 40300");
    assert!(
        mock.take_filtered("/api/nt/ws").is_empty(),
        "非白名单 Origin 的握手不得到达上游"
    );
}

//  cors

/// 【攻击】任意反射`Origin` / null `Origin` / 子域`Origin`，浏览器跨域读取的决定依据是
/// `Access-Control-Allow-Origin`回显，非白名单origin必须拿不到。
/// 【防护层】tower-http `CorsLayer`白名单精确匹配，无通配符则不注入 allow-origin。
/// 【预期】响应正常处理但绝无access-control-allow-origin头。
#[tokio::test]
async fn evil_null_subdomain_origin_no_allow_origin() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    for origin in [
        "https://evil.example.com",         // 任意反射尝试
        "null",                             // null Origin，file:// / sandbox场景
        "https://sub.fms.example.com",      // 子域
        "https://fms.example.com.evil.com", // 后缀欺骗
        "http://fms.example.com",           // 协议降级，白名单是https
    ] {
        // /api路径，401响应上也不得出现allow-origin
        let mut req = request("GET", "/api/gl/vouchers");
        req.headers_mut().insert("origin", origin.parse().unwrap());
        let resp = oneshot(&router, req).await;
        let headers = resp.headers().clone();
        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
        assert!(
            !headers.contains_key("access-control-allow-origin"),
            "非白名单 Origin {origin} 不得获得 allow-origin"
        );
        // /healthz，200响应上同样不得出现
        let mut req = request("GET", "/healthz");
        req.headers_mut().insert("origin", origin.parse().unwrap());
        let resp = oneshot(&router, req).await;
        assert_eq!(resp.status(), StatusCode::OK);
        assert!(
            !resp.headers().contains_key("access-control-allow-origin"),
            "非白名单 Origin {origin} 不得获得 allow-origin"
        );
    }
}

/// 【攻击】带凭据预检，allow-credentials=true场景，白名单`Origin`预检必须正确
/// 回显allow-origin + allow-credentials: true + 方法 + 头 + max-age。
/// 【防护层】`CorsLayer`白名单 + `allow_credentials`，true。
/// 【预期】200 + 各cors头精确存在。
#[tokio::test]
async fn preflight_whitelist_origin_echoes_credentials() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    let mut req = request("OPTIONS", "/api/gl/vouchers");
    req.headers_mut()
        .insert("origin", "https://fms.example.com".parse().unwrap());
    req.headers_mut()
        .insert("access-control-request-method", "POST".parse().unwrap());
    req.headers_mut().insert(
        "access-control-request-headers",
        "Authorization, X-Trace-Id".parse().unwrap(),
    );
    let resp = oneshot(&router, req).await;
    let (status, headers, _) = collect(resp).await;
    assert_eq!(status, StatusCode::OK, "白名单预检应 200");
    assert_eq!(
        headers
            .get("access-control-allow-origin")
            .and_then(|v| v.to_str().ok()),
        Some("https://fms.example.com"),
        "白名单 origin 预检必须回显 allow-origin"
    );
    assert_eq!(
        headers
            .get("access-control-allow-credentials")
            .and_then(|v| v.to_str().ok()),
        Some("true"),
        "allow_credentials 必须为 true"
    );
    let methods = headers
        .get_all("access-control-allow-methods")
        .iter()
        .filter_map(|v| v.to_str().ok())
        .collect::<Vec<_>>()
        .join(", ");
    assert!(
        methods.contains("POST") && methods.contains("GET"),
        "应允许 GET/POST，实际 {methods}"
    );
    assert!(
        headers.contains_key("access-control-max-age"),
        "预检应带 max-age"
    );
}

/// 【攻击】非白名单`Origin`预检，带凭据，不得获得allow-origin，浏览器强制失败。
/// 【防护层】`CorsLayer`白名单精确匹配。
/// 【预期】预检可返回，无allow-origin时浏览器拒绝跨域或4xx，但绝无allow-origin。
#[tokio::test]
async fn preflight_evil_origin_denied() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    for origin in ["https://evil.example.com", "null"] {
        let mut req = request("OPTIONS", "/api/gl/vouchers");
        req.headers_mut().insert("origin", origin.parse().unwrap());
        req.headers_mut()
            .insert("access-control-request-method", "POST".parse().unwrap());
        req.headers_mut().insert(
            "access-control-request-headers",
            "Authorization".parse().unwrap(),
        );
        let resp = oneshot(&router, req).await;
        let headers = resp.headers().clone();
        assert!(
            !headers.contains_key("access-control-allow-origin"),
            "非白名单 Origin {origin} 预检不得获得 allow-origin"
        );
    }
}

//  Host头注入/伪造

/// 【攻击】伪造`Host`头(`evil.com`)，代理路由必须仍指向配置上游(`base_url`拼url，
/// `Host`绝不参与路由)，且客户端`Host`不得原样透传上游。
/// 【防护层】`proxy::build_upstream_uri`仅用配置`base_url` + path/query拼 url，路由
/// 不可被`Host`劫持，forward强制重写`Host`为配置上游authority(防`Host`
/// 投毒影响上游虚拟主机路由/缓存/日志)，原`Host`记录到`X-Forwarded-Host`。
/// 【预期】请求到达配置的mock上游，200+ 上游收到配置上游host(非`evil.com`)
/// + `X-Forwarded-Host`记录原始伪造`Host`。
#[tokio::test]
async fn host_header_not_used_for_routing() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let mut req = with_auth(request("GET", "/api/gl/vouchers"), &admin_token());
    req.headers_mut()
        .insert("host", "evil.example.com".parse().unwrap());
    let resp = oneshot(&router, req).await;
    assert_eq!(
        resp.status(),
        StatusCode::OK,
        "伪造 Host 不影响路由(仍转发配置上游)"
    );
    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(
        hits.len(),
        1,
        "请求必须到达配置的 mock 上游(非 Host 指定目标)"
    );
    let up_host = hits[0].header("host").unwrap_or("").to_string();
    let expect_host = mock.base_url.trim_start_matches("http://");
    assert_eq!(
        up_host, expect_host,
        "D4：Host 头必须重写为配置上游 host({expect_host})，不得透传客户端伪造值"
    );
    assert_eq!(
        hits[0].header("x-forwarded-host"),
        Some("evil.example.com"),
        "原始 Host 应记录到 X-Forwarded-Host"
    );
    // 无认证 + 伪造Host 则 401(安全链不受Host影响)
    let mut req = request("GET", "/api/gl/vouchers");
    req.headers_mut()
        .insert("host", "evil.example.com".parse().unwrap());
    let resp = oneshot(&router, req).await;
    assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
}

//  /api前缀绕过

/// 【攻击】`/api`前缀变体，双斜杠 /`/api`//vouchers 不匹配 `/api`/{*rest} 路由则落入
/// fallback，被static层二次校验(normalize折叠后做 `/api` 前缀检查)拒绝为 404，
/// 不输出任何静态内容、不到达上游，`/api`/../vouchers被filter拒绝400。
/// 【防护层】`static_handler`内嵌`normalize_path` + `/api`/ 前缀检查，折叠后命中则 404，
/// filter层穿越检查则 400。
/// 【预期】/`/api`//vouchers 则 404/40400，拒绝，不落静态内容，`/api`/../ 则 400。
#[tokio::test]
async fn api_prefix_double_slash_and_dotdot() {
    let fixture = StaticFixture::new();
    let (router, mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    let resp = oneshot(&router, request("GET", "//api//vouchers")).await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "双斜杠变体应被拒绝(static 层二次校验折叠后 /api 前缀 -> 404)"
    );
    assert_eq!(body["code"], 40400);
    let resp = oneshot(&router, request("GET", "/api/../vouchers")).await;
    assert_eq!(
        resp.status(),
        StatusCode::BAD_REQUEST,
        "/api/../ 应被 filter 拒绝"
    );
    assert!(
        mock.take_filtered("/api/gl").is_empty() && mock.take_filtered("/vouchers").is_empty(),
        "变体请求不得到达上游"
    );
}

/// 【测试】大小写 /api/ 与编码`/api`%2F变体绕过
/// `/api`/{*rest}路由，axum精确匹配落到static fallback返回`index.html` 200，
/// 违反"api前缀不落静态"契约。`static_handler` `/api`/ 前缀
/// 检查先循环percent-decode + 大小写不敏感，命中则 404。
/// 【预期】全部404/40400，绝不返回`index.html`，不达上游。
#[tokio::test]
async fn case_and_encoded_prefix_reach_static() {
    let fixture = StaticFixture::new();
    let (router, mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    for uri in [
        "/API/vouchers",
        "/Api/vouchers",
        "/api%2Fvouchers",
        "/API%2Fvouchers",
    ] {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(
            status,
            StatusCode::NOT_FOUND,
            "{uri} 应 404(D5：/api 前缀变体拦截)"
        );
        assert_eq!(body["code"], 40400, "{uri} 错误码应 40400");
    }
    assert!(
        mock.take_filtered("/vouchers").is_empty(),
        "变体请求不得到达上游"
    );
}

// 目录穿越经静态fallback

/// 【攻击】经静态fallback的目录穿越，`/assets`/../../index.html与编码变体，
/// 必须被filter二次校验拒绝，不得经spa fallback逃逸出静态根。
/// 【防护层】`static_handler`内嵌`normalize_path`，fallback不经安全链，二次校验则 400。
/// 【预期】400 + code 40001。
#[tokio::test]
async fn static_traversal_400() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    for uri in [
        "/assets/../../index.html",
        "/assets/%2e%2e%2findex.html",
        "/assets/..%2f..%2f.env",
        "/a/../../b/../../etc/passwd",
    ] {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "静态穿越应 400：{uri}");
        assert_eq!(body["code"], 40001, "错误码应 40001：{uri}");
    }
}

/// 【攻击】双编码穿越经静态fallback(`/assets`/%252e%252e%252f...)，
/// `normalize_path`循环解码构成 ".." 段则 400 拒绝，静态层二次校验同样生效，
/// 绝不落到spa fallback、绝不输出任何文件内容。
/// 【防护层】`static_handler`内嵌`normalize_path`，循环解码防双编码绕过。
/// 【预期】全部400/40001，.env内容绝不出现在任何响应。
#[tokio::test]
async fn double_encoded_static_index_only_no_leak() {
    let fixture = StaticFixture::new();
    let (router, _mock) = router_with_mock(|_| {}, Some(&fixture.dir.display().to_string())).await;
    // 双编码穿越，循环解码构成 ".." 段则 400，不落spa fallback
    for uri in [
        "/assets/%252e%252e%252findex.html",
        "/assets/%252e%252e%252fsecret.sql",
        "/%252e%252e%252f.env",
    ] {
        let resp = oneshot(&router, request("GET", uri)).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(
            status,
            StatusCode::BAD_REQUEST,
            "{uri} 应 400(D1 循环解码拦截)"
        );
        assert_eq!(body["code"], 40001, "{uri} 错误码应 40001");
        let text = body.to_string();
        assert!(
            !text.contains(ENV_SECRET) && !text.contains("sql-secret"),
            "{uri} 不得泄露任何文件内容"
        );
    }
}
