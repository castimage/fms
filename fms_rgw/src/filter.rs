//! 输入安全，安全链第一层filter，路径规范化防穿越，含`%2e%2e`编码绕过、
//! `Content-Type`白名单、body严格utf-8、sql注入浅特征拦截(应用层第一道浅层过滤，
//! db层rls/触发器为最终防线)、`Content-Length`声明与实际长度双重上限
//! (上限取配置`server.max_body_bytes`，默认10MB)
//!
//! 调用关系，`mw_input_filter`，安全链第一层，`static.rs`内嵌二次校验，`mw_sign`调`check_body_size`
#![forbid(unsafe_code)]

use thiserror::Error;
use crate::state::AppState;
use crate::types::{GatewayError, error_response, extract_client_ip, trace_id_of};
use axum::body::Body;
use axum::extract::State;
use axum::http::header::{CONTENT_LENGTH, CONTENT_TYPE, HeaderMap, TRANSFER_ENCODING, USER_AGENT};
use axum::http::{Method, Request, Response, Uri};
use axum::middleware::Next;
use std::sync::Arc;

/// 请求体上限默认值，10MB(`config.rs` `server.max_body_bytes`缺省同源，改此须同步，
/// 测试与ws声明长度校验引用此默认值)
pub const MAX_BODY_BYTES: usize = 10 * 1024 * 1024;

/// 输入过滤错误
#[derive(Debug, Clone, Copy, PartialEq, Eq, Error)]
pub enum FilterError {
    /// 路径含 .. 穿越 / 空字节 / 反斜杠，或编码绕过
    #[error("非法路径")]
    BadPath,
    /// `Content-Type`不在白名单
    #[error("不支持的 Content-Type")]
    BadContentType,
    /// sql注入浅特征命中
    #[error("请求疑似 SQL 注入")]
    SqlInjection,
    /// 请求体 > 10MB
    #[error("请求体超限")]
    BodyTooLarge,
    /// body非utf-8
    #[error("请求体非 UTF-8")]
    InvalidUtf8,
}

/// 循环percent-decode，最多16轮 + 64KB长度防放大上限，双编码乃至多层编码变体
/// 逐轮解开直至不再含%xx序列，轮数需比目标层数多1，每轮恰好解开一层%25嵌套，
/// 16轮覆盖16层编码，64KB上限防放大攻击。
/// 仅供安全检查，特征/穿越判断使用，不改变透传的原始path，上游收到未解码路径。
///
/// 语义与边界，每轮做最小percent-decode(仅%XX，大小写十六进制均可，非法%序列
/// 按字面保留，不做utf-8结构校验)，解码结果与上轮相同即提前终止，解码后长度
/// 超64KB立即停止，安全检查输入远小于此，防止%25嵌套指数放大占内存。
/// 典型命中，%2e%2e，一层解码得 ".."，%252e%252e%252f 两层解码得 %2e%2e%2f，随后进下一轮
/// 解开为 "../"，8层编码亦在16轮上限内。调用方(`normalize_path`、sql特征检查)
/// 对解码结果再判 ".." 段与注入特征。
///
/// `s`，原始输入，path或query参数对，含%编码序列
///
/// @return最多解码16轮后的字符串，可能含未解码残余，非法序列/超限轮数
pub(crate) fn decode_for_check(s: &str) -> String {
    let mut cur = s.to_string();
    for _ in 0..16 {
        let next = percent_decode(&cur);
        if next == cur {
            break;
        }
        // 防放大，解码结果超64KB即停止，安全检查输入远小于此
        if next.len() > 64 * 1024 {
            break;
        }
        cur = next;
    }
    cur
}

/// 路径规范化，防 ../ 穿越，拒绝含 '\0' / '\\' / ".." 段的路径则返回 `BadPath`，
/// 连续 '/' 折叠、末尾 '/' 保留，静态目录重定向语义，percent-decode后同样检查
/// 循环解码防双编码绕过，返回规范化路径(`/api`/... 保持原样转发)
pub fn normalize_path(path: &str) -> Result<String, FilterError> {
    if path.contains('\0') || path.contains('\\') {
        return Err(FilterError::BadPath);
    }

    // 编码绕过检查，循环percent-decode后同样拒绝 .. 段，双编码亦命中
    if has_dotdot_segment(&decode_for_check(path)) {
        return Err(FilterError::BadPath);
    }

    // 连续 '/' 折叠，首/尾 '/' 语义保留
    let mut out = String::with_capacity(path.len());
    for c in path.chars() {
        if c == '/' {
            if !out.ends_with('/') {
                out.push('/');
            }
        } else {
            out.push(c);
        }
    }
    Ok(out)
}

/// 是否含 ".." 路径段，'/' 分隔
fn has_dotdot_segment(s: &str) -> bool {
    s.split('/').any(|seg| seg == "..")
}

/// 最小percent-decode，仅%XX，非法%序列按字面保留，不校验utf-8结构
fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let (Some(h), Some(l)) = (hex_val(bytes[i + 1]), hex_val(bytes[i + 2]))
        {
            out.push(h * 16 + l);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_val(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

/// 带body的方法，GET/DELETE/HEAD跳过
fn is_body_method(method: &Method) -> bool {
    matches!(method.as_str(), "POST" | "PUT" | "PATCH")
}

/// `Content-Type`白名单，仅对带body的方法校验，
/// application/json | application/x-www-form-urlencoded | multipart/form-data
/// | text/plain | application/octet-stream，附件上传
/// 缺失`Content-Type` 则返回 `BadContentType`。**实际无body的请求跳过校验**(http语义，
/// 无`Content-Length`或`Content-Length`: 0且无`Transfer-Encoding` = 请求体为空)，
/// 登出/api-key吊销等无body的POST不需要`Content-Type`(部分客户端/浏览器
/// 对空body请求不携带该头属合法行为，按方法一刀切会误伤)
pub fn check_content_type(method: &Method, headers: &HeaderMap) -> Result<(), FilterError> {
    if !is_body_method(method) {
        return Ok(());
    }

    // 实际是否有body，Content-Length > 0或存在Transfer-Encoding，chunked
    let has_body = headers
        .get(CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok())
        .map(|len| len > 0)
        .unwrap_or_else(|| headers.contains_key(TRANSFER_ENCODING));
    if !has_body {
        return Ok(());
    }
    match headers.get(CONTENT_TYPE) {
        None => Err(FilterError::BadContentType),
        Some(v) => {
            let raw = v.to_str().map_err(|_| FilterError::BadContentType)?;
            let mime = raw
                .split(';')
                .next()
                .unwrap_or("")
                .trim()
                .to_ascii_lowercase();
            const ALLOWED: &[&str] = &[
                "application/json",
                "application/x-www-form-urlencoded",
                "multipart/form-data",
                "text/plain",
                "application/octet-stream",
            ];
            if ALLOWED.contains(&mime.as_str()) {
                Ok(())
            } else {
                Err(FilterError::BadContentType)
            }
        }
    }
}

/// body严格utf-8校验
pub fn check_utf8(body: &[u8]) -> Result<(), FilterError> {
    if std::str::from_utf8(body).is_ok() {
        Ok(())
    } else {
        Err(FilterError::InvalidUtf8)
    }
}

/// sql注入浅特征拦截(大小写不敏感，对循环percent-decode后的path+query检查，
/// 双编码变体经多轮解码后命中)。
/// token参数值豁免，值为base64url jwt(合法组合可含`--`等被注释特征误伤的序列，
/// 如签名段 …Ln1--I)，无sql语义且由`verify_jwt`严格验签，浅特征对其无意义，
/// 解析query时跳过该参数值，其余参数与path照常检查
pub fn check_sql_injection_path(path: &str, query: Option<&str>) -> Result<(), FilterError> {
    let hay = decode_for_check(path).to_ascii_lowercase();
    if sql_feature_hit(&hay) {
        return Err(FilterError::SqlInjection);
    }
    if let Some(q) = query {
        // 按 & 拆分参数，token参数值跳过，其余拼回检查
        let mut filtered = String::new();
        for pair in q.split('&') {
            // token参数值豁免，base64url jwt，验签兜底，浅特征不适用
            if let Some((k, _v)) = pair.split_once('=')
                && k.eq_ignore_ascii_case("token")
            {
                continue;
            }
            if !filtered.is_empty() {
                filtered.push('&');
            }
            filtered.push_str(&decode_for_check(pair));
        }
        if sql_feature_hit(&filtered.to_ascii_lowercase()) {
            return Err(FilterError::SqlInjection);
        }
    }
    Ok(())
}

/// 对body的浅特征检查，文本型 + application/json + multipart/form-data均扫描
/// (仅查前两类会被`Content-Type`切换整体绕过第一道防线)，json字符串值与multipart
/// 表单字段值同样拦截。multipart经`extract_multipart_text`只保留**表单字段**
/// 文本，文件part跳过，二进制文件字节流可能含任意ASCII子串(随机字节/真实
/// 文档格式)，整体扫描必然误伤一切上传，注入载荷只能经表单字段传入，字段照常扫描。
pub fn check_sql_injection_body(
    body: &[u8],
    content_type: Option<&str>,
) -> Result<(), FilterError> {
    let ct = content_type
        .map(|s| s.to_ascii_lowercase())
        .unwrap_or_default();
    let applicable = ct.contains("application/x-www-form-urlencoded")
        || ct.contains("text/plain")
        || ct.contains("application/json")
        || ct.contains("multipart/form-data");
    if !applicable {
        return Ok(());
    }
    let scan_body = if ct.contains("multipart/form-data") {
        extract_multipart_text(body, content_type.unwrap_or_default())
    } else {
        body.to_vec()
    };
    let hay = String::from_utf8_lossy(&scan_body).to_ascii_lowercase();
    if sql_feature_hit(&hay) {
        Err(FilterError::SqlInjection)
    } else {
        Ok(())
    }
}

/// multipart预处理，按boundary分隔行拆分各part，拼接**表单字段** part内容，
/// 文件part，头部含filename=按声明`Content-Type`分流，二进制类型(image/*、
/// octet-stream等)内容跳过，字节流无注入面且必误伤上传，**text/\* 文本文件
/// csv/TXT仍参与扫描**(内容可安全文本解析，对账单csv导入等文本入库场景
/// 的注入载荷保持第一道防线拦截，纵深不因上传放宽)。
/// 分隔行按RFC 2046严格匹配，整行恰为 "--boundary"，首分隔或 "--boundary--"
/// 收尾分隔，不做前缀宽松匹配，防止攻击者借边界前缀把携带注入载荷的行伪造成
/// "分隔行" 逃过扫描，`Content-Type`缺boundary参数或为空时原样返回，不剥离。
fn extract_multipart_text(body: &[u8], content_type: &str) -> Vec<u8> {
    let boundary = content_type.split(';').skip(1).find_map(|p| {
        let p = p.trim();
        let (k, v) = p.split_once('=')?;
        if k.trim().eq_ignore_ascii_case("boundary") {
            Some(v.trim().trim_matches('"').to_string())
        } else {
            None
        }
    });
    let Some(boundary) = boundary else {
        return body.to_vec();
    };
    if boundary.is_empty() {
        return body.to_vec();
    }
    let delim = format!("--{boundary}");
    let closing = format!("--{boundary}--");
    let mut out = Vec::with_capacity(body.len().min(65536));
    let mut is_file = false;     // 当前part是否为文件(Content-Disposition含filename=)
    let mut file_is_text = false; // 文件part声明类型是否为text/*，可安全文本扫描
    let mut in_headers = false;  // 是否处于part头部段，空行后进入内容段
    for raw in body.split(|&b| b == b'\n') {
        let line = raw.strip_suffix(b"\r").unwrap_or(raw);
        if line == delim.as_bytes() || line == closing.as_bytes() {
            // 新part，或收尾分隔，重置状态
            is_file = false;
            file_is_text = false;
            in_headers = true;
            continue;
        }
        if in_headers {
            if line.is_empty() {
                in_headers = false; // 头部结束则进入内容段
            } else if line
                .windows(9)
                .any(|w| w.eq_ignore_ascii_case(b"filename="))
            {
                is_file = true; // 文件part，二进制内容跳过，text/* 文本文件仍扫描
            } else if is_file {
                // 读文件part声明的Content-Type，大小写不敏感
                let lower = line.to_ascii_lowercase();
                if let Some(pos) = lower
                    .windows(13)
                    .position(|w| w.eq_ignore_ascii_case(b"content-type:"))
                {
                    let ct = String::from_utf8_lossy(&lower[pos + 13..]).trim().to_string();
                    file_is_text = ct.starts_with("text/") || ct == "application/csv";
                }
            }
            continue;
        }
        if !is_file || file_is_text {
            out.extend_from_slice(line);
            out.push(b'\n');
        }
    }
    out
}

/// 浅特征集合，大小写不敏感，输入已lowercase，
/// "'" 与or/and邻接、union select、select…from、insert into、update…set、delete from、
/// drop table、";" 后跟sql关键字、"--"、"/*"、sleep(/benchmark( 函数注入特征
fn sql_feature_hit(hay: &str) -> bool {
    const SQL_KEYWORDS: &[&str] = &[
        "select", "insert", "update", "delete", "drop", "union", "alter", "create", "truncate",
    ];

    // 引号与or/and邻接，' OR 1=1类，覆盖无空格/单空格/多空格变体
    for kw in ["or", "and"] {
        if hay.contains(&format!("'{kw}"))
            || hay.contains(&format!("' {kw}"))
            || hay.contains(&format!("'  {kw}"))
            || hay.contains(&format!("{kw}'"))
        {
            return true;
        }
    }

    // 恒真/恒假恒等式，' or 1=1、x=1=1、1=1--等
    if hay.contains("1=1") || hay.contains("1=2") {
        return true;
    }
    if hay.contains("union select") || hay.contains("union all select") {
        return true;
    }
    if hay.contains("select") && hay.contains(" from ") {
        return true;
    }
    if hay.contains("insert into") {
        return true;
    }
    if hay.contains("update") && hay.contains(" set ") {
        return true;
    }
    if hay.contains("delete from") || hay.contains("drop table") {
        return true;
    }
    if hay.contains("--") || hay.contains("/*") {
        return true;
    }
    if hay.contains("sleep(") || hay.contains("benchmark(") {
        return true;
    }

    // ";" 后跟sql关键字
    for kw in SQL_KEYWORDS {
        if hay.contains(&format!(";{kw}")) {
            return true;
        }
    }
    false
}

/// 已知爬虫/脚本ua特征(防爬虫执行层，`robots.txt`为声明层)，
/// 命中即403拒绝。特征仅覆盖自动脚本爬虫，python-requests/scrapy/aiohttp等，
/// 不拦运维工具，curl/wget与真实浏览器ua，防误伤正常访问与排障手段
pub fn is_crawler_ua(user_agent: Option<&str>) -> bool {
    let Some(ua) = user_agent else { return false };
    let ua = ua.to_ascii_lowercase();
    const FRAGMENTS: &[&str] = &[
        "python-requests",
        "python-urllib",
        "python/",
        "aiohttp",
        "scrapy",
        "httpx",
        "go-http-client",
        "okhttp",
        "node-fetch",
        "libwww-perl",
    ];
    FRAGMENTS.iter().any(|f| ua.contains(f))
}

/// 请求体大小，`Content-Length`声明超限直接拒绝，实际缓冲长度超限(`mw_sign`内)同样拒绝。
/// 上限由调用方传入(配置`server.max_body_bytes`，测试可注入任意值)
pub fn check_body_size(
    content_length: Option<u64>,
    body_len: usize,
    max_body_bytes: usize,
) -> Result<(), FilterError> {
    if let Some(len) = content_length
        && len > max_body_bytes as u64
    {
        return Err(FilterError::BodyTooLarge);
    }
    if body_len > max_body_bytes {
        return Err(FilterError::BodyTooLarge);
    }
    Ok(())
}

/// 全局爬虫ua拦截中间件(`Router`最外层layer，覆盖全部路径含静态`/healthz`，
/// 爬虫主要目标就是公开面首页/登录页，仅拦`/api`面防不住)，
/// `server.block_crawlers`开关，默认开命中已知脚本爬虫特征则 403 直接丢弃。
/// `robots.txt`为声明层、本层为执行层，双层防爬
pub async fn mw_crawler_block(
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
    next: Next,
) -> Response<Body> {
    if state.cfg.server.block_crawlers
        && is_crawler_ua(req.headers().get(USER_AGENT).and_then(|v| v.to_str().ok()))
    {
        tracing::info!(path = %req.uri().path(), "输入过滤：爬虫 UA 拦截(403)");
        return error_response(GatewayError::Forbidden("crawler blocked".to_string()), "");
    }
    next.run(req).await
}

/// 中间件，安全链第一层，
/// 依次 `normalize_path`(重写`uri.path`)、`check_content_type`、`check_sql_injection_path`
/// 任一步失败则 400/413 + 日志(记录`trace_id`)。
///
/// 校验链，TRACE显式禁用(405/40500，避免认证通过的TRACE被`any`路由转发上游
/// 形成反射回显xst)后做路径规范化(拒绝 '\0'/'\\'/".." 段及编码绕过变体，连续 '/'
/// 折叠，规范化结果与原始不同则重写`uri.path`，query原样保留，重写失败则 400)，随后
/// `Content-Type`白名单(仅带body方法校验，实际无body的请求跳过，http语义，
/// 无`Content-Length`或`Content-Length`: 0且无`Transfer-Encoding` = 空body，登出/
/// 吊销等无body POST不需要`Content-Type`，带body却缺失即拒绝，415语义未建模
/// 按400/40001)再做 sql注入浅特征(path + 全量query，双编码经
/// `decode_for_check`多轮解码后命中，token参数值豁免，base64url jwt合法组合可含
/// `--`等特征序列，由`verify_jwt`验签兜底)。
///
/// 失败语义，全部为400(`BadRequest`)或405，TRACE，统一错误体 + WARN日志，
/// 通过后不修改body/头，直接进入下一层。此层为应用层第一道浅过滤，db层
/// rls/触发器仍是最终防线，浅特征误伤由后端400兜底。
///
/// _state 全局状态，本层不取用，保留签名对齐安全链
pub async fn mw_input_filter(
    State(_state): State<Arc<AppState>>,
    mut req: Request<Body>,
    next: Next,
) -> Response<Body> {
    let trace_id = trace_id_of(&req);

    // TRACE显式禁用，405，避免认证通过的TRACE被any路由转发上游形成反射回显，xst
    if req.method() == Method::TRACE {
        tracing::warn!(trace_id = %trace_id, path = %req.uri().path(), "输入过滤：TRACE 方法禁用");
        return error_response(GatewayError::MethodNotAllowed, &trace_id);
    }

    // 路径规范化，失败则 400
    let path = req.uri().path();
    let normalized = match normalize_path(path) {
        Ok(p) => p,
        Err(_) => {
            tracing::warn!(trace_id = %trace_id, path = %path, "输入过滤：非法路径");
            return error_response(GatewayError::BadRequest("invalid path".to_string()), &trace_id);
        }
    };
    if normalized != path {
        // 重写uri，保留query
        let mut pqs = normalized;
        if let Some(q) = req.uri().query() {
            pqs.push('?');
            pqs.push_str(q);
        }
        match pqs.parse::<Uri>() {
            Ok(uri) => {
                *req.uri_mut() = uri;
            }
            Err(_) => {
                return error_response(GatewayError::BadRequest("invalid path".to_string()), &trace_id);
            }
        }
    }

    // Content-Type白名单，415语义未建模，按400/40001返回
    if check_content_type(req.method(), req.headers()).is_err() {
        tracing::warn!(trace_id = %trace_id, path = %req.uri().path(), "输入过滤：Content-Type 拒绝");
        return error_response(
            GatewayError::BadRequest("unsupported content-type".to_string()),
            &trace_id,
        );
    }

    // sql注入浅特征，path + query
    if check_sql_injection_path(req.uri().path(), req.uri().query()).is_err() {
        tracing::warn!(trace_id = %trace_id, path = %req.uri().path(), "输入过滤：SQL 注入特征拦截");
        return error_response(
            GatewayError::BadRequest("sql injection pattern".to_string()),
            &trace_id,
        );
    }

    // 到这里输入校验全部通过
    next.run(req).await
}

// ip访问白名单，配置server.ip_allowlist，ip/CIDR列表，空 = 不启用，安全链最前
// 先于filter/rate/auth按真实客户端ip判定，非白名单来源直接丢弃，403 fail-closed，
// 且xff/X-Real-IP伪造不影响判定(取对端真实ip，见extract_client_ip)

/// 解析后的ip网段，精确ip = /32或 /128，prefix 0 = 全匹配
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IpNet {
    addr: std::net::IpAddr,
    prefix: u8,
}

impl IpNet {
    /// 解析 "1.2.3.4" 或 "10.0.0.0/8"，ipv4/ipv6，非法输入返回`None`
    pub fn parse(s: &str) -> Option<IpNet> {
        let s = s.trim();
        if s.is_empty() {
            return None;
        }
        let (addr_str, prefix) = match s.split_once('/') {
            Some((a, p)) => (a, Some(p.parse::<u8>().ok()?)),
            None => (s, None),
        };
        let addr: std::net::IpAddr = addr_str.parse().ok()?;
        let max = if addr.is_ipv4() { 32 } else { 128 };
        let prefix = match prefix {
            Some(0) => 0, // 显式 /0 = 全匹配
            Some(p) if p <= max => p,
            Some(_) => return None, // 前缀越界非法
            None => max,            // 无斜杠 = 精确ip，/32或 /128
        };
        Some(IpNet { addr, prefix })
    }

    /// 网段是否包含目标ip，掩码比较，ipv4/ipv6异族恒false
    pub fn contains(&self, ip: &std::net::IpAddr) -> bool {
        if self.prefix == 0 {
            return true; // /0全匹配，含::/0
        }
        match (self.addr, ip) {
            (std::net::IpAddr::V4(a), std::net::IpAddr::V4(b)) => {
                let mask = if self.prefix == 32 {
                    u32::MAX
                } else {
                    u32::MAX << (32 - self.prefix as u32)
                };
                // match ergonomics，self.addr为owned、ip为引用则 a值 / b引用
                (u32::from(a) & mask) == (u32::from(*b) & mask)
            }
            (std::net::IpAddr::V6(a), std::net::IpAddr::V6(b)) => {
                let a = u128::from(a);
                let b = u128::from(*b);
                if self.prefix == 128 {
                    a == b
                } else {
                    let shift = 128 - self.prefix as u32;
                    (a >> shift) == (b >> shift)
                }
            }
            _ => false,
        }
    }
}

/// 解析白名单配置(非法条目返回`Err`，配置错误启动期fail-closed，不静默忽略)
pub fn parse_allowlist(list: &[String]) -> Result<Vec<IpNet>, String> {
    list.iter().map(|s| IpNet::parse(s).ok_or_else(|| format!("非法 IP 白名单条目: {s}"))).collect()
}

/// 白名单判定，allowlist空 = 未启用放行全部，ip不可得(`None`)/非法文本时拒绝，fail-closed
pub fn ip_allowed(ip: Option<&str>, allowlist: &[IpNet]) -> bool {
    if allowlist.is_empty() {
        return true;
    }
    let ip = match ip.and_then(|s| s.parse::<std::net::IpAddr>().ok()) {
        Some(ip) => ip,
        None => return false,
    };
    allowlist.iter().any(|net| net.contains(&ip))
}

/// ip白名单中间件，安全链最前，allowlist非空时非白名单来源403直接丢弃
pub async fn mw_ip_allowlist(
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
    next: Next,
) -> Response<Body> {
    let allowlist = &state.cfg.server.ip_allowlist;
    if allowlist.is_empty() {
        return next.run(req).await; // 未启用，零开销直通
    }
    let trace_id = trace_id_of(&req);
    let ip = extract_client_ip(&req);
    let nets = match parse_allowlist(allowlist) {
        Ok(nets) => nets,
        Err(e) => {
            // 配置错误已由build_state启动期拦截，此处兜底fail-closed
            tracing::error!(trace_id = %trace_id, "IP 白名单配置非法: {e}");
            return error_response(GatewayError::Forbidden("ip allowlist config error".to_string()), &trace_id);
        }
    };
    if !ip_allowed(ip.as_deref(), &nets) {
        tracing::info!(trace_id = %trace_id, ip = %ip.as_deref().unwrap_or("unknown"),
            "IP 白名单拒绝(非白名单来源直接丢弃)");
        return error_response(GatewayError::Forbidden("ip not allowed".to_string()), &trace_id);
    }
    next.run(req).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::state::{test_state, test_state_with};
    use axum::http::header::HeaderValue;
    use axum::middleware;
    use tower::ServiceExt;

    #[test]
    fn normalize_path_folds_slashes_keeps_trailing() {
        assert_eq!(normalize_path("/a//b/").unwrap(), "/a/b/");
        assert_eq!(normalize_path("/a/b").unwrap(), "/a/b");
        assert_eq!(normalize_path("//a///b").unwrap(), "/a/b");
        assert_eq!(normalize_path("/").unwrap(), "/");
    }

    #[test]
    fn normalize_path_rejects_traversal_and_encoding() {
        assert_eq!(normalize_path("/../etc/passwd"), Err(FilterError::BadPath));
        assert_eq!(normalize_path("/a/../b"), Err(FilterError::BadPath));
        assert_eq!(normalize_path("/%2e%2e/x"), Err(FilterError::BadPath));
        assert_eq!(normalize_path("/a/%2e%2e%2f"), Err(FilterError::BadPath));
        assert_eq!(normalize_path("/a\\b"), Err(FilterError::BadPath));
        assert_eq!(normalize_path("/a\0b"), Err(FilterError::BadPath));
        // 正常文件名含 . 不误伤
        assert!(normalize_path("/app.3f9a2c1d.js").is_ok());
    }

    #[test]
    fn check_content_type_whitelist() {
        let mut h = HeaderMap::new();
        // 无body方法跳过
        assert!(check_content_type(&Method::GET, &h).is_ok());
        assert!(check_content_type(&Method::DELETE, &h).is_ok());
        // 实际无body的POST(无Content-Length/Transfer-Encoding)则跳过校验
        // (登出/吊销等无body请求不要求Content-Type，浏览器对空body不携带属合法)
        assert!(check_content_type(&Method::POST, &h).is_ok());
        // Content-Length: 0 则视为无body，放行
        h.insert(CONTENT_LENGTH, "0".parse().unwrap());
        assert!(check_content_type(&Method::POST, &h).is_ok());
        // 带body方法且实际有body，缺失Content-Type则拒绝
        h.insert(CONTENT_LENGTH, "5".parse().unwrap());
        assert!(matches!(
            check_content_type(&Method::POST, &h),
            Err(FilterError::BadContentType)
        ));
        h.insert(CONTENT_TYPE, "application/json".parse().unwrap());
        assert!(check_content_type(&Method::POST, &h).is_ok());
        h.insert(
            CONTENT_TYPE,
            "application/json; charset=utf-8".parse().unwrap(),
        );
        assert!(check_content_type(&Method::PUT, &h).is_ok());
        h.insert(CONTENT_TYPE, "text/html".parse().unwrap());
        assert!(matches!(
            check_content_type(&Method::POST, &h),
            Err(FilterError::BadContentType)
        ));
        // Transfer-Encoding: chunked，无CL则视为有body，缺失CT则拒绝
        let mut h2 = HeaderMap::new();
        h2.insert(TRANSFER_ENCODING, "chunked".parse().unwrap());
        assert!(matches!(
            check_content_type(&Method::POST, &h2),
            Err(FilterError::BadContentType)
        ));
    }

    #[test]
    fn sql_injection_path_detection() {
        assert!(check_sql_injection_path("/api/gl/vouchers", Some("page=1")).is_ok());
        assert!(check_sql_injection_path("/api/x", Some("q=' or 1=1")).is_err());
        assert!(
            check_sql_injection_path("/api/x", Some("q=%27%20or%201%3D1")).is_err(),
            "编码绕过"
        );
        assert!(check_sql_injection_path("/api/x", Some("q=union select")).is_err());
        assert!(check_sql_injection_path("/api/x", Some("q=select * from users")).is_err());
        assert!(check_sql_injection_path("/api/x", Some("q=;drop table users")).is_err());
        assert!(check_sql_injection_path("/api/x", Some("q=1--")).is_err());
        assert!(check_sql_injection_path("/api/x", Some("q=sleep(5)")).is_err());
    }

    #[test]
    fn sql_injection_body_only_for_form_types() {
        let evil = b"name=admin'; drop table users;--";
        assert!(check_sql_injection_body(evil, Some("application/x-www-form-urlencoded")).is_err());
        assert!(check_sql_injection_body(evil, Some("text/plain")).is_err());
        // json / multipart同样扫描(Content-Type切换不再绕过第一道防线)
        assert!(
            check_sql_injection_body(evil, Some("application/json")).is_err(),
            "JSON body 必须扫描(F6)"
        );
        assert!(
            check_sql_injection_body(evil, Some("multipart/form-data")).is_err(),
            "multipart 必须扫描(F6)"
        );
        // 良性数据不误伤
        assert!(
            check_sql_injection_body(b"name=alice", Some("application/x-www-form-urlencoded"))
                .is_ok()
        );
        assert!(
            check_sql_injection_body(
                br#"{"page":1,"size":20,"name":"alice"}"#,
                Some("application/json")
            )
            .is_ok()
        );
        assert!(check_sql_injection_body(br#"{"amount":1=1}"#, Some("application/json")).is_err());
        // json字符串值中的注入载荷命中
        let json_evil = br#"{"query":"admin'; drop table users;--"}"#;
        assert!(
            check_sql_injection_body(json_evil, Some("application/json")).is_err(),
            "JSON 字符串值注入载荷必须拦截(F6)"
        );
    }

    #[test]
    fn multipart_file_part_skipped_field_scanned() {
        // 文件part，二进制非utf-8内容 + 注入特征字节则跳过，不误伤上传
        let body = b"--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n\x89PNG\xff\xfe select * from users --\r\n--x--\r\n";
        assert!(
            check_sql_injection_body(body, Some("multipart/form-data; boundary=x")).is_ok(),
            "文件 part 二进制内容必须跳过扫描"
        );
        // 表单字段携带注入载荷则命中，防绕过语义保持
        let evil = b"--x\r\nContent-Disposition: form-data; name=\"query\"\r\n\r\nadmin'; drop table users;--\r\n--x--\r\n";
        assert!(
            check_sql_injection_body(evil, Some("multipart/form-data; boundary=x")).is_err(),
            "表单字段注入载荷必须拦截"
        );
        // 字段干净 + 文件字节含select特征则整体放行，混合上传场景
        let mixed = b"--x\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nalice\r\n--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n\r\nselect from -- \xff\r\n--x--\r\n";
        assert!(
            check_sql_injection_body(mixed, Some("multipart/form-data; boundary=x")).is_ok(),
            "混合 part：文件字节特征不得误伤"
        );
        // text/csv文件part，文本可安全解析则仍参与扫描，csv导入纵深保持
        let csv_evil = b"--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.csv\"\r\nContent-Type: text/csv\r\n\r\ntrx_date,amount,summary\r\n2026-08-01,100.00,x'; drop table users;--\r\n--x--\r\n";
        assert!(
            check_sql_injection_body(csv_evil, Some("multipart/form-data; boundary=x")).is_err(),
            "text/csv 文件内容注入载荷必须拦截"
        );
        // image/png文件，二进制则跳过，图片字节含特征不误伤
        let png = b"--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n\x89PNG\xff\xfe select from -- \r\n--x--\r\n";
        assert!(
            check_sql_injection_body(png, Some("multipart/form-data; boundary=x")).is_ok(),
            "image/png 二进制文件跳过扫描"
        );
    }

    #[test]
    fn crawler_ua_detection() {
        // 已知爬虫/脚本特征命中
        assert!(is_crawler_ua(Some("python-requests/2.31.0")));
        assert!(is_crawler_ua(Some("Scrapy/2.11 (bot)")));
        assert!(is_crawler_ua(Some("aiohttp/3.9")));
        assert!(is_crawler_ua(Some("Go-http-client/1.1")));
        assert!(is_crawler_ua(Some("okhttp/4.12")));
        assert!(is_crawler_ua(Some("node-fetch/1.0")));
        assert!(is_crawler_ua(Some("Python/3.11")));
        // 运维工具与真实浏览器不拦，防误伤
        assert!(!is_crawler_ua(Some("curl/8.5.0")));
        assert!(!is_crawler_ua(Some("Wget/1.21")));
        assert!(!is_crawler_ua(Some(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151.0.0.0 Safari/537.36 Edg/151.0.0.0"
        )));
        assert!(!is_crawler_ua(None));
        assert!(!is_crawler_ua(Some("")));
    }

    #[tokio::test]
    async fn mw_crawler_block_rejects_on_all_paths() {
        // 全局中间件，爬虫ua对/api、静态根路径、/healthz一律403，防爬覆盖公开面
        let state = test_state(); // 默认block_crawlers=true
        let app = axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(echo_ok))
            .route("/healthz", axum::routing::get(echo_ok))
            .fallback(echo_ok)
            .layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_crawler_block,
            ))
            .with_state(state);
        for path in ["/api/gl/vouchers", "/", "/healthz", "/login"] {
            let req = axum::http::Request::builder()
                .method("GET")
                .uri(path)
                .header("user-agent", "python-requests/2.31")
                .body(Body::empty())
                .unwrap();
            let resp = app.clone().oneshot(req).await.unwrap();
            assert_eq!(
                resp.status(),
                axum::http::StatusCode::FORBIDDEN,
                "{path} 爬虫 UA 应 403"
            );
        }
        // 正常浏览器ua则放行，全部路径
        let req = axum::http::Request::builder()
            .method("GET")
            .uri("/api/gl/vouchers")
            .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/151.0.0.0")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
    }

    #[tokio::test]
    async fn mw_crawler_block_can_be_disabled() {
        let mut cfg = Config::default();
        cfg.server.block_crawlers = false;
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(echo_ok))
            .fallback(echo_ok)
            .layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_crawler_block,
            ))
            .with_state(state);
        let req = axum::http::Request::builder()
            .method("GET")
            .uri("/")
            .header("user-agent", "python-requests/2.31")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK, "关闭开关后爬虫 UA 放行");
    }

    #[test]
    fn check_body_size_limits() {
        assert!(check_body_size(Some(MAX_BODY_BYTES as u64), 0, MAX_BODY_BYTES).is_ok());
        assert!(matches!(
            check_body_size(Some(MAX_BODY_BYTES as u64 + 1), 0, MAX_BODY_BYTES),
            Err(FilterError::BodyTooLarge)
        ));
        assert!(check_body_size(None, MAX_BODY_BYTES, MAX_BODY_BYTES).is_ok());
        assert!(matches!(
            check_body_size(None, MAX_BODY_BYTES + 1, MAX_BODY_BYTES),
            Err(FilterError::BodyTooLarge)
        ));
    }

    #[test]
    fn check_utf8_strict() {
        assert!(check_utf8(b"hello").is_ok());
        assert!(check_utf8(b"\xff\xfe").is_err());
    }

    #[test]
    fn normalize_path_boundaries() {
        // 边界，根路径、点段保留，. 不是 ..、单段 ..、首尾斜杠语义
        assert_eq!(normalize_path("/").unwrap(), "/");
        assert_eq!(normalize_path("/a/./b").unwrap(), "/a/./b", ". 段不误伤");
        assert_eq!(normalize_path("/a/b/").unwrap(), "/a/b/", "末尾斜杠保留");
        assert_eq!(normalize_path("//a///b//").unwrap(), "/a/b/");
        // 穿越边界，段恰为 ..、编码变体，大小写十六进制、混合、路径仅 ..
        assert_eq!(normalize_path(".."), Err(FilterError::BadPath));
        assert_eq!(normalize_path("/.."), Err(FilterError::BadPath));
        assert_eq!(normalize_path("/a/.."), Err(FilterError::BadPath));
        assert_eq!(
            normalize_path("/%2E%2E/x"),
            Err(FilterError::BadPath),
            "大写十六进制编码"
        );
        assert_eq!(
            normalize_path("/a/%2e%2e%2Fb"),
            Err(FilterError::BadPath),
            "混合大小写编码"
        );
        assert_eq!(
            normalize_path("/%2e%2e%2f%2e%2e/etc"),
            Err(FilterError::BadPath)
        );
        // 双编码绕过，循环解码后仍构成 .. 段则拒绝
        assert_eq!(
            normalize_path("/a/%252e%252e%252f"),
            Err(FilterError::BadPath),
            "双编码穿越"
        );
        assert_eq!(
            normalize_path("/%252e%252e%252fetc"),
            Err(FilterError::BadPath),
            "双编码穿越(深)"
        );
        assert_eq!(
            normalize_path("/a/..%252fb"),
            Err(FilterError::BadPath),
            "双编码混合 ..%2f"
        );
        // 深层编码绕过，4层编码解码后仍构成 .. 段则拒绝
        assert_eq!(
            normalize_path("/assets/%2525252e%2525252e%2525252findex.html"),
            Err(FilterError::BadPath),
            "4 层编码穿越必须拦截(F7)"
        );
        assert_eq!(
            normalize_path("/%2525252e%2525252e%2525252fetc"),
            Err(FilterError::BadPath),
            "4 层编码穿越(深)必须拦截(F7)"
        );
        // 8层编码(decode_for_check上限轮数内)同样拦截
        assert_eq!(
            normalize_path("/%25252525252525252e%25252525252525252e%25252525252525252fetc"),
            Err(FilterError::BadPath),
            "8 层编码穿越必须拦截(F7)"
        );
        // 非法%序列按字面保留不误伤，非ASCII路径合法
        assert!(normalize_path("/a%zzb").is_ok());
        assert!(normalize_path("/中文/文件").is_ok());
        assert!(
            normalize_path("/api/gl/vouchers?x=1").is_ok(),
            "query 不在 path 规范化范围"
        );
    }

    #[test]
    fn content_type_branches_complete() {
        let mut h = HeaderMap::new();
        // HEAD同样跳过，与GET/DELETE一致，无body方法不校验
        assert!(check_content_type(&Method::HEAD, &h).is_ok());
        // 白名单各类型 + 参数后缀
        for ct in [
            "application/json",
            "application/x-www-form-urlencoded",
            "multipart/form-data",
            "text/plain",
            "application/octet-stream",
        ] {
            h.insert(CONTENT_TYPE, ct.parse().unwrap());
            assert!(check_content_type(&Method::POST, &h).is_ok(), "{ct} 应放行");
        }
        h.insert(
            CONTENT_TYPE,
            "multipart/form-data; boundary=----x".parse().unwrap(),
        );
        assert!(
            check_content_type(&Method::POST, &h).is_ok(),
            "带 boundary 参数应放行"
        );
        // 大小写不敏感
        h.insert(
            CONTENT_TYPE,
            "Application/JSON; charset=utf-8".parse().unwrap(),
        );
        assert!(check_content_type(&Method::POST, &h).is_ok());
        // 白名单外则拒绝，头值非utf-8 则拒绝，带body语义，声明CL>0
        h.insert(CONTENT_LENGTH, "5".parse().unwrap());
        h.insert(CONTENT_TYPE, "text/html".parse().unwrap());
        assert_eq!(
            check_content_type(&Method::POST, &h),
            Err(FilterError::BadContentType)
        );
        h.insert(
            CONTENT_TYPE,
            HeaderValue::from_bytes(b"app/json\xff").unwrap(),
        );
        assert_eq!(
            check_content_type(&Method::POST, &h),
            Err(FilterError::BadContentType)
        );
    }

    #[test]
    fn sql_injection_feature_matrix_all_patterns() {
        // 引号 + or/and邻接，无空格 / 单空格 / 多空格 / 后缀与恒等式
        let cases = [
            "q=1' or '1'='1",
            "q=1'or'1",
            "q=1'  or  1",
            "q=x'and'1",
            "q=x and' y",
            "q=1' OR 1=1", // 大小写不敏感
            "q=1=1",
            "q=x=1=2",
            "q=1=1--",
        ];
        for c in cases {
            assert!(
                check_sql_injection_path("/api/x", Some(c)).is_err(),
                "{c} 应拦截"
            );
        }
        // 语句型特征
        let stmt = [
            "q=union select a,b from t",
            "q=union all select 1",
            "q=select * from users",
            "q=insert into users values(1)",
            "q=update users set name='x'",
            "q=delete from users",
            "q=drop table users",
            "q=x;select 1", // 分号后跟关键字
            "q=x;drop table t",
            "q=1--comment",
            "q=1/*comment*/",
            "q=sleep(5)",
            "q=benchmark(1000,md5(1))",
        ];
        for c in stmt {
            assert!(
                check_sql_injection_path("/api/x", Some(c)).is_err(),
                "{c} 应拦截"
            );
        }
        // 编码绕过，percent-decode后命中
        assert!(check_sql_injection_path("/api/x", Some("q=%27%20or%201%3D1%20--")).is_err());
        assert!(check_sql_injection_path("/api/x", Some("q=union%20select%20name")).is_err());
        // 双编码绕过，循环解码后命中特征
        assert!(
            check_sql_injection_path("/api/x", Some("q=%2527%2520or%25201%253D1")).is_err(),
            "双编码 ' or 1=1 应拦截"
        );
        assert!(
            check_sql_injection_path("/api/x", Some("q=%2527%2520or%25201%253D1%2520--")).is_err(),
            "双编码带尾注释应拦截"
        );
        assert!(
            check_sql_injection_path("/api/x", Some("q=union%2520select%2520name")).is_err(),
            "双编码 union select 应拦截"
        );
        // path自身携带特征(query=None分支)
        assert!(check_sql_injection_path("/api/x;select", None).is_err());
        assert!(check_sql_injection_path("/api/x/1=1", None).is_err());
        // 负例，合法业务参数不误伤
        let benign = [
            "page=1&size=20",
            "q=order by name",
            "q=for sale",
            "q=selectname",
            "name=floor(x)", // floor( 非特征
            "a=1-1",
        ];
        for c in benign {
            assert!(
                check_sql_injection_path("/api/gl/vouchers", Some(c)).is_ok(),
                "{c} 不应误伤"
            );
        }
        assert!(check_sql_injection_path("/api/gl/vouchers", None).is_ok());
    }

    #[test]
    fn token_query_param_exempt_from_sql_features() {
        // ws token为base64url jwt，合法组合可含--，sql注释特征等序列，
        // 如签名段…Ln1--I，token参数值必须豁免浅特征，验签兜底，
        // 否则合法ws升级被误拦400
        let jwt_like = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.Ln1--I_9abc-xyz";
        assert!(
            check_sql_injection_path("/api/nt/ws", Some(&format!("token={jwt_like}"))).is_ok(),
            "token 参数含 -- 不应误拦(JWT base64url 合法字符)"
        );
        assert!(
            check_sql_injection_path(
                "/api/nt/ws",
                Some(&format!("token={jwt_like}&other=1' or 1=1"))
            )
            .is_err(),
            "非 token 参数仍必须拦截"
        );
        assert!(
            check_sql_injection_path("/api/nt/ws", Some(&format!("token={jwt_like}&page=1")),)
                .is_ok(),
            "token + 合法参数应放行"
        );
        // 大小写参数名也豁免
        assert!(
            check_sql_injection_path("/api/nt/ws", Some(&format!("TOKEN={jwt_like}"))).is_ok(),
            "TOKEN 大写参数名同样豁免"
        );
        // token参数值整体豁免(含注入特征形态的值也放行，仅用于验签、
        // 不进入任何sql上下文，安全由verify_jwt兜底)
        assert!(
            check_sql_injection_path("/api/nt/ws", Some("token=1' or 1=1")).is_ok(),
            "token 参数值一律豁免(验签兜底)"
        );
    }

    #[test]
    fn sql_injection_body_content_type_branches() {
        let evil = b"name=admin'; drop table users;--";
        // 大小写不敏感 + 带charset参数仍检查
        assert!(check_sql_injection_body(evil, Some("TEXT/PLAIN")).is_err());
        assert!(check_sql_injection_body(evil, Some("text/plain; charset=utf-8")).is_err());
        // json / multipart带参数后缀同样检查
        assert!(check_sql_injection_body(evil, Some("application/json; charset=utf-8")).is_err());
        assert!(
            check_sql_injection_body(evil, Some("multipart/form-data; boundary=----x")).is_err()
        );
        // 无Content-Type / 白名单外类型则视为不适用，放行
        assert!(check_sql_injection_body(evil, None).is_ok());
        assert!(check_sql_injection_body(evil, Some("application/octet-stream")).is_ok());
        // 非utf-8 body，utf8_lossy后按文本检查，安全兜底，不panic
        assert!(check_sql_injection_body(b"\xff\xfe", Some("text/plain")).is_ok());
    }

    #[test]
    fn multipart_boundary_delimiters_not_flagged_but_part_values_scanned() {
        // 真实multipart，分隔行 "--boundary"/"--boundary--" 恒含 "--"，RFC 2046，
        // 逐字整体扫描会误伤一切上传，400/40001 body rejected
        let benign = b"--fmsbnd0001\r\n\
Content-Disposition: form-data; name=\"bankAccountId\"\r\n\
\r\n\
3f9a2c1d-0000-4000-8000-000000000001\r\n\
--fmsbnd0001\r\n\
Content-Disposition: form-data; name=\"file\"; filename=\"probe.csv\"\r\n\
Content-Type: text/csv\r\n\
\r\n\
trx_date,amount,summary\n\
2026-08-01,100.00,probe row alpha\n\
\r\n\
--fmsbnd0001--\r\n";
        assert!(
            check_sql_injection_body(benign, Some("multipart/form-data; boundary=fmsbnd0001"))
                .is_ok(),
            "multipart 分隔行含 -- 不得误伤"
        );
        // 带引号boundary同样放行
        assert!(
            check_sql_injection_body(benign, Some("multipart/form-data; boundary=\"fmsbnd0001\""))
                .is_ok(),
            "带引号 boundary 不得误伤"
        );
        // 无boundary参数则不剥离原样扫描，含 "--" 的multipart会被拦截(无boundary无法
        // 识别分隔行，保守拒绝)
        assert!(
            check_sql_injection_body(benign, Some("multipart/form-data")).is_err(),
            "缺 boundary 参数时保持原扫描语义"
        );
        // 表单字段part携带注入载荷仍必须拦截，文件part内容跳过、字段照常扫描
        let evil = b"--fmsbnd0001\r\n\
Content-Disposition: form-data; name=\"query\"\r\n\
\r\n\
x'; drop table users;--\r\n\
--fmsbnd0001--\r\n";
        assert!(
            check_sql_injection_body(evil, Some("multipart/form-data; boundary=fmsbnd0001"))
                .is_err(),
            "表单字段 part 注入载荷仍须拦截"
        );
        // 前缀宽松匹配拒绝，boundary=b时 "bxxx" 行不得被当作分隔行剥离
        let prefix_evil = b"--bx'; drop table users;--\r\n";
        assert!(
            check_sql_injection_body(prefix_evil, Some("multipart/form-data; boundary=b")).is_err(),
            "非完整分隔行不得剥离(防边界前缀绕过)"
        );
        // CRLF与LF混合行结构均正确处理
        let lf_only = b"--fmsbnd0001\nContent-Disposition: form-data; name=\"f\"; filename=\"a.csv\"\n\nok,1\n--fmsbnd0001--\n";
        assert!(
            check_sql_injection_body(lf_only, Some("multipart/form-data; boundary=fmsbnd0001"))
                .is_ok(),
            "LF 行结构不得误伤"
        );
    }

    async fn echo_ok() -> &'static str {
        "ok"
    }

    fn filter_router() -> axum::Router {
        let state = test_state();
        axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(echo_ok))
            .route_layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_input_filter,
            ))
            .with_state(state)
    }

    #[tokio::test]
    async fn mw_input_filter_rejects_trace_405() {
        // TRACE显式禁用则 405/40500，认证与否均拒绝，反射回显不转移上游
        let app = filter_router();
        let req = axum::http::Request::builder()
            .method("TRACE")
            .uri("/api/gl/vouchers")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::METHOD_NOT_ALLOWED,
            "TRACE 应 405"
        );
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 40500);
    }

    #[tokio::test]
    async fn mw_input_filter_rejects_bad_path_sql_and_content_type() {
        let app = filter_router();
        // 非法路径，穿越则 400
        let req = axum::http::Request::builder()
            .uri("/api/a/../b")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::BAD_REQUEST);
        // sql注入特征，path 上命中则 400
        let req = axum::http::Request::builder()
            .uri("/api/x/1=1")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::BAD_REQUEST);
        // 带body方法缺Content-Type，声明CL>0，实际有body则 400
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .header("content-length", "1")
            .body(Body::from("x"))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::BAD_REQUEST);
        // 无body的POST，无CL，如登出/吊销则放行(不需要Content-Type)
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        // 合法请求放行
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .header(CONTENT_TYPE, "application/json")
            .body(Body::from("{\"a\":1}"))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
    }

    // ip白名单

    #[test]
    fn ipnet_parse_valid_and_invalid() {
        assert!(IpNet::parse("1.2.3.4").is_some());
        assert!(IpNet::parse("10.0.0.0/8").is_some());
        assert!(IpNet::parse("::1").is_some());
        assert!(IpNet::parse("2001:db8::/32").is_some());
        assert!(IpNet::parse("").is_none());
        assert!(IpNet::parse("not-an-ip").is_none());
        assert!(IpNet::parse("1.2.3.4/33").is_none(), "IPv4 前缀 >32 非法");
        assert!(IpNet::parse("::1/129").is_none(), "IPv6 前缀 >128 非法");
    }

    #[test]
    fn ipnet_contains_cidr_and_exact() {
        let net = IpNet::parse("10.0.0.0/8").unwrap();
        assert!(net.contains(&"10.1.2.3".parse().unwrap()));
        assert!(net.contains(&"10.255.255.255".parse().unwrap()));
        assert!(!net.contains(&"11.0.0.1".parse().unwrap()));
        assert!(!net.contains(&"::1".parse().unwrap()), "IPv4/IPv6 异族恒 false");
        let exact = IpNet::parse("1.2.3.4").unwrap(); // 无斜杠 = /32
        assert!(exact.contains(&"1.2.3.4".parse().unwrap()));
        assert!(!exact.contains(&"1.2.3.5".parse().unwrap()));
        let any = IpNet::parse("0.0.0.0/0").unwrap(); // /0全匹配
        assert!(any.contains(&"8.8.8.8".parse().unwrap()));
        let v6 = IpNet::parse("2001:db8::/32").unwrap();
        assert!(v6.contains(&"2001:db8::1".parse().unwrap()));
        assert!(!v6.contains(&"2001:db9::1".parse().unwrap()));
    }

    #[test]
    fn ip_allowed_empty_list_allows_all() {
        assert!(ip_allowed(Some("1.2.3.4"), &[]));
        assert!(ip_allowed(Some("9.9.9.9"), &[]));
    }

    #[test]
    fn ip_allowed_unknown_ip_rejected_fail_closed() {
        let nets = parse_allowlist(&["10.0.0.0/8".to_string()]).unwrap();
        assert!(!ip_allowed(None, &nets), "IP 不可得应拒绝(fail-closed)");
        assert!(!ip_allowed(Some("not-an-ip"), &nets), "非法 IP 文本应拒绝");
    }

    #[test]
    fn ip_allowed_match_and_reject() {
        let nets = parse_allowlist(&["10.0.0.0/8".to_string(), "192.168.1.5".to_string()]).unwrap();
        assert!(ip_allowed(Some("10.0.0.1"), &nets));
        assert!(ip_allowed(Some("192.168.1.5"), &nets), "精确 IP 命中");
        assert!(!ip_allowed(Some("192.168.1.6"), &nets));
        assert!(!ip_allowed(Some("8.8.8.8"), &nets));
    }

    #[test]
    fn parse_allowlist_rejects_invalid_entry() {
        assert!(parse_allowlist(&["10.0.0.0/8".to_string()]).is_ok());
        assert!(parse_allowlist(&["10.0.0.0/8".to_string(), "bad".to_string()]).is_err());
    }

    async fn echo_ok_ip() -> &'static str {
        "ok"
    }

    #[tokio::test]
    async fn mw_ip_allowlist_rejects_outside_and_passes_inside() {
        let mut cfg = Config::default();
        cfg.server.ip_allowlist = vec!["127.0.0.1".to_string(), "10.0.0.0/8".to_string()];
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(echo_ok_ip))
            .route_layer(middleware::from_fn_with_state(
                state.clone(),
                mw_ip_allowlist,
            ))
            .with_state(state);

        use std::net::SocketAddr;
        // 对端 = 本机回环，白名单内则放行
        let req = axum::http::Request::builder()
            .uri("/api/gl/vouchers")
            .extension(axum::extract::connect_info::ConnectInfo(
                SocketAddr::from(([127, 0, 0, 1], 40001)),
            ))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);

        // 对端 = 白名单外9.9.9.9，xff伪造成白名单ip则仍 403，判定取真实对端，伪造不绕过
        let req = axum::http::Request::builder()
            .uri("/api/gl/vouchers")
            .extension(axum::extract::connect_info::ConnectInfo(
                SocketAddr::from(([9, 9, 9, 9], 40002)),
            ))
            .header("x-forwarded-for", "127.0.0.1")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::FORBIDDEN,
            "非白名单对端应 403 直接丢弃(XFF 伪造不绕过)"
        );
    }

    #[tokio::test]
    async fn mw_ip_allowlist_empty_list_passes() {
        let mut cfg = Config::default();
        cfg.server.ip_allowlist = Vec::new(); // 未启用
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(echo_ok_ip))
            .route_layer(middleware::from_fn_with_state(
                state.clone(),
                mw_ip_allowlist,
            ))
            .with_state(state);
        let req = axum::http::Request::builder()
            .uri("/api/gl/vouchers")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
    }
}
