//! hyper上游反向代理，`UpstreamClient`连接池复用，idle 60s、每host 16条空闲，
//! forward用`mw_sign`缓冲的`BufferedBody`重建上游请求(含`/api`前缀 + `X-Forwarded-For`/`Proto`)，
//! 响应头到达即返回、响应体流式透传，流式阶段不设超时，不中断大报表下载，
//! 熔断联动`is_ready`/record，非2xx透传原状态码，hop-by-hop头剥离。
//! `proxy_handler`经`UpstreamPool`(`lb.rs`)选实例 + 失败重试转发，
//! 全局熔断粒度降为整体兜底(任一实例成功即record，true，最后一次错误才record，false，
//! 实例级健康由pool探测/剔除管理)
//!
//! 调用关系，`proxy_handler` ← 安全链分发(非ws的`/api`/**)，`lb::UpstreamPool`用send
#![forbid(unsafe_code)]

use crate::circuit::CircuitError;
use crate::headers::inject_trace;
use crate::state::AppState;
use crate::types::{
    BufferedBody, GatewayError, error_response, extract_client_ip, extract_client_ip_from_ext,
    trace_id_of,
};
use axum::body::Body;
use axum::extract::State;
use axum::http::header::{CONNECTION, CONTENT_LENGTH, HOST, HeaderMap, HeaderName, HeaderValue};
use axum::http::{Request, Response, Uri};
use bytes::Bytes;
use http_body_util::BodyExt;
use hyper_util::client::legacy::Client;
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::rt::TokioExecutor;
use std::sync::Arc;
use std::time::Duration;
use thiserror::Error;
use tracing::{error, warn};

/// 上游代理错误，统一转5xx，底层原因记日志
#[derive(Debug, Error)]
pub enum ProxyError {
    /// 上游tcp连接失败
    #[error("上游连接失败")]
    Connect,
    /// `request_timeout`内未获得响应头
    #[error("上游请求超时")]
    Timeout,
    /// 上游`base_url`非法，启动期即应失败
    #[error("非法上游 URI")]
    InvalidUri,
    /// 发送/接收io错误
    #[error("上游 IO 错误")]
    Send,
}

/// 防御性上限，上游响应头声明的`Content-Length`超过此值即拒绝该响应
/// 正常响应体流式透传不缓冲，此检查只拦截恶意/异常首包声明
const MAX_RESPONSE_DECLARED_BYTES: u64 = 2 * 1024 * 1024 * 1024; // 2GiB

/// 上游客户端，hyper-util legacy `Client`，连接池复用
pub struct UpstreamClient {
    client: Client<HttpConnector, Body>,
    /// 形如http://127.0.0.1:8081，无尾部斜杠
    base_url: String,
    /// 上游authority(host:`port`)，转发时强制重写`Host`头(防`Host`投毒)
    upstream_host: String,
    request_timeout: Duration,
}

impl UpstreamClient {
    /// 启动期校验`base_url`合法，uri可解析 + scheme http/https + 有host，
    /// 否则返回`InvalidUri`，main启动失败退出，不等到请求期才暴露。
    ///
    /// `pool_max_idle_per_host`，连接池每host空闲连接上限(配置`upstream.pool_max_idle_per_host`)
    pub fn new(
        base_url: String,
        request_timeout: Duration,
        idle_timeout: Duration,
        pool_max_idle_per_host: usize,
    ) -> Result<Self, ProxyError> {
        let uri: Uri = base_url.parse().map_err(|_| ProxyError::InvalidUri)?;
        match uri.scheme_str() {
            Some("http") | Some("https") => {}
            _ => return Err(ProxyError::InvalidUri),
        }
        let upstream_host = uri
            .authority()
            .map(|a| a.as_str().to_string())
            .ok_or(ProxyError::InvalidUri)?;

        // 连接池，idle_timeout_secs回收、每host最多pool_max_idle_per_host条空闲连接
        // (单连接多请求复用，容量取配置upstream.pool_max_idle_per_host)
        let connector = HttpConnector::new();
        let client = Client::builder(TokioExecutor::new())
            .pool_idle_timeout(Some(idle_timeout))
            .pool_max_idle_per_host(pool_max_idle_per_host)
            .build(connector);
        Ok(Self {
            client,
            base_url: base_url.trim_end_matches('/').to_string(),
            upstream_host,
            request_timeout,
        })
    }

    /// 上游authority(host:`port`，来自`base_url`)，ws重建上游请求时强制重写`Host`
    pub(crate) fn upstream_authority(&self) -> &str {
        &self.upstream_host
    }

    /// 原始发送，ws握手 / 健康探测用，不做body缓冲，`request_timeout`包裹，返回完整响应
    /// (响应体转为axum `Body`流式，响应头到达即返回)。
    /// 与forward的区别，直接透传调用方构造的请求(不取extensions.`BufferedBody`、
    /// 不改头、不重写`Host`)，仅负责超时包裹与错误分类。
    ///
    /// 超时语义，`request_timeout`覆盖从发起到响应头到达的全程，超时则回 `Timeout`，
    /// 响应头到达后立即返回，响应体转为流式(`Body::from_stream`逐帧透传，不缓冲、
    /// 不设超时，大报表下载不被中断)。错误分类见`map_hyper_error`(按文本子串
    /// 归类 connect/refused/dns 则 `Connect`，timed out/timeout 则 `Timeout`，其余则 `Send`)。
    ///
    /// `req`，完整上游请求，含body，ws场景为空体 + 升级头
    ///
    /// @return上游响应，响应体为流式，失败按`ProxyError`分类，调用方统一转5xx
    pub async fn send(&self, req: Request<Body>) -> Result<Response<Body>, ProxyError> {
        let fut = self.client.request(req);
        match tokio::time::timeout(self.request_timeout, fut).await {
            Err(_) => Err(ProxyError::Timeout),
            Ok(Err(e)) => Err(map_hyper_error(e)),
            Ok(Ok(resp)) => {
                // 上游Incoming体则转 axum Body 流，逐帧转发，不缓冲、不collect
                let (parts, incoming) = resp.into_parts();
                let body = Body::from_stream(incoming.into_data_stream());
                Ok(Response::from_parts(parts, body))
            }
        }
    }

    /// 转发，http用，请求体已是`BufferedBody`，bytes(`mw_sign`保证，ws场景退化为空体)，
    /// 重建上游请求(原path含`/api`前缀、query保留，带上已注入头 + `X-Forwarded-For`/`Proto`)，
    /// 响应头到达即返回，响应体由调用方流式透传。头处理与uri组装经
    /// [0]统一，多上游重试路径共用同一重建逻辑，防漂移。
    ///
    /// `req`，经安全链处理后的客户端请求(extensions含`BufferedBody`，头已含
    ///            auth注入的身份头与sign注入的`X-FMS-Signature`，原样带往上游)
    ///
    /// @return上游响应，响应头已透传，body为流式，失败分类见`ProxyError`
    pub async fn forward(&self, req: Request<Body>) -> Result<Response<Body>, ProxyError> {
        let (parts, _) = req.into_parts();
        // 请求体，mw_sign已缓冲进extensions，直接取用(Bytes clone廉价)
        let body_bytes: Bytes = parts
            .extensions
            .get::<BufferedBody>()
            .map(|b| b.0.clone())
            .unwrap_or_else(Bytes::new);
        let ip = extract_client_ip_from_ext(&parts.extensions);
        let up_req = build_upstream_request_from(
            &UpstreamRequestParts {
                headers: parts.headers.clone(),
                method: parts.method.clone(),
                path: parts.uri.path().to_string(),
                query: parts.uri.query().map(String::from),
                body_bytes,
            },
            ip.as_deref(),
            &self.base_url,
            &self.upstream_host,
        )?;
        self.send(up_req).await
    }
}

/// 上游请求重建入参(`build_upstream_request_from`用)，头模板 + 方法 + 路径/query +
/// 缓冲体。头模板需可`Clone`，多上游重试时每实例基于模板独立重建(头处理有原地
/// 副作用，不可复用同一份)
pub(crate) struct UpstreamRequestParts {
    pub headers: axum::http::HeaderMap,
    pub method: axum::http::Method,
    pub path: String,
    pub query: Option<String>,
    pub body_bytes: Bytes,
}

/// 重建上游请求的公共部分(`UpstreamClient::forward`与`lb::UpstreamPool::forward`共用)。
///
/// 头处理要点，剥离hop-by-hop(RFC 7230，含`Connection`声明字段)，`Host`强制重写
/// 为目标实例authority(防`Host`投毒影响上游虚拟主机路由/缓存/日志)，原`Host`记入
/// `X-Forwarded-Host`，仅当不同于上游host且未预置，`X-Forwarded-For`已有值则逗号
/// 追加，不覆盖链路上游代理的既有记录，`X-Forwarded-Proto`仅缺失时写入(调用方
/// `proxy_handler`已按tls配置预置，此处保留)。
///
/// body缺失时退化为空体(防御性，正常链路由`mw_sign`保证必有缓冲)，
/// 重建失败，非法uri/头 则 `InvalidUri`
///
/// `parts`，请求模板，headers将被原地修改，调用方重试时须传独立克隆
///
/// `ip`，客户端ip(`X-Forwarded-For`追加用)
///
/// `base_url`，目标实例`base_url`，上游uri组装
///
/// `upstream_host`，目标实例authority(`Host`重写)
pub(crate) fn build_upstream_request_from(
    parts: &UpstreamRequestParts,
    ip: Option<&str>,
    base_url: &str,
    upstream_host: &str,
) -> Result<Request<Body>, ProxyError> {
    // 从模板克隆头，本函数原地修改头，模板须可复用，重试时每实例独立克隆
    let mut headers = parts.headers.clone();

    // 头处理，剥离hop-by-hop，追加X-Forwarded-For / X-Forwarded-Proto
    // (X-Forwarded-Proto已由调用方按tls配置预置时保留)
    strip_hop_by_hop(&mut headers);

    // Host强制重写为目标实例authority，原Host记入X-Forwarded-Host
    let client_host = headers
        .get(HOST)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());
    if let Ok(hv) = HeaderValue::from_str(upstream_host) {
        headers.insert(HOST, hv);
    }
    if let Some(ch) = client_host
        && ch != upstream_host
        && !headers.contains_key(X_FORWARDED_HOST)
        && let Ok(hv) = HeaderValue::from_str(&ch)
    {
        headers.insert(X_FORWARDED_HOST, hv);
    }

    forward_client_headers(&mut headers, ip, "http");
    let upstream_uri = build_upstream_uri(base_url, &parts.path, parts.query.as_deref())?;
    let mut builder = Request::builder()
        .method(parts.method.clone())
        .uri(upstream_uri);
    for (k, v) in headers.iter() {
        builder = builder.header(k, v);
    }
    builder
        .body(Body::from(parts.body_bytes.clone()))
        .map_err(|_| ProxyError::InvalidUri)
}

/// hyper底层错误映射业务错误分类，连接/超时/其余io
fn map_hyper_error(e: impl std::fmt::Display) -> ProxyError {
    // hyper 1无is_connect/is_timeout，hyper_util legacy Error亦如此，按错误文本归类
    let msg = e.to_string().to_lowercase();
    if msg.contains("connect") || msg.contains("refused") || msg.contains("dns") {
        ProxyError::Connect
    } else if msg.contains("timed out") || msg.contains("timeout") {
        ProxyError::Timeout
    } else {
        ProxyError::Send
    }
}

/// 组装上游uri，`base_url`，去尾部斜杠+ 原path(含`/api`前缀)+ query，如有
pub(crate) fn build_upstream_uri(
    base_url: &str,
    path: &str,
    query: Option<&str>,
) -> Result<Uri, ProxyError> {
    let base = base_url.trim_end_matches('/');
    let mut s = String::with_capacity(base.len() + path.len() + query.map_or(0, |q| q.len() + 1));
    s.push_str(base);
    s.push_str(path);
    if let Some(q) = query {
        s.push('?');
        s.push_str(q);
    }
    s.parse::<Uri>().map_err(|_| ProxyError::InvalidUri)
}

/// 剥离hop-by-hop头，RFC 7230语义，`Connection`/`Keep-Alive`/`Transfer-Encoding`/`Upgrade`/`TE`/
/// `Trailer`/`Proxy`-*，以及`Connection`头中显式声明的字段名(如keep-alive, `X-Foo`)
pub fn strip_hop_by_hop(headers: &mut HeaderMap) {
    // Connection头声明的字段也一并剥离，防止上游状态被客户端声明的连接字段污染，
    // 连接头自身随后被剥离，声明字段须在移除前读取
    let mut extra: Vec<HeaderName> = Vec::new();
    if let Some(conn) = headers.get(CONNECTION)
        && let Ok(v) = conn.to_str()
    {
        for part in v.split(',') {
            let token = part.trim();
            if !token.is_empty()
                && let Ok(n) = HeaderName::from_bytes(token.as_bytes())
            {
                extra.push(n);
            }
        }
    }
    strip_hop_by_hop_keep(headers, &[]);
    for name in extra {
        headers.remove(name);
    }
}

/// 剥离hop-by-hop头但保留指定字段名(ws升级场景须保留`Connection`/`Upgrade`)，
/// 与[0]共享同一清单，防清单漂移
pub(crate) fn strip_hop_by_hop_keep(headers: &mut HeaderMap, keep: &[&str]) {
    const HOP_BY_HOP: &[&str] = &[
        "connection",
        "keep-alive",
        "transfer-encoding",
        "upgrade",
        "te",
        "trailer",
        "proxy-connection",
        "proxy-authorization",
        "proxy-authenticate",
    ];
    let mut to_remove: Vec<HeaderName> = Vec::new();
    for name in headers.keys() {
        let lower = name.as_str().to_ascii_lowercase();
        if !keep.contains(&lower.as_str()) && HOP_BY_HOP.contains(&lower.as_str()) {
            to_remove.push(name.clone());
        }
    }
    for name in to_remove {
        headers.remove(name);
    }
}

/// 追加`X-Forwarded-For`，已有值则逗号追加、`X-Forwarded-Proto`，仅缺失时设置，
/// 并覆盖`X-Real-IP`为真实客户端ip(单值语义，恒可信，上游若信任`X-Real-IP`取真实
/// ip，原样透传会被直连客户端伪造，`X-Forwarded-For`的追加语义保留链路既有代理记录)，
/// 头值含非法字符时丢弃，构造失败即忽略，不panic
pub fn forward_client_headers(headers: &mut HeaderMap, ip: Option<&str>, proto: &str) {
    if let Some(ip) = ip {
        match headers.get(X_FORWARDED_FOR) {
            Some(existing) => {
                if let Ok(v) = existing.to_str() {
                    let mut merged = String::with_capacity(v.len() + ip.len() + 2);
                    merged.push_str(v);
                    merged.push_str(", ");
                    merged.push_str(ip);
                    if let Ok(hv) = HeaderValue::from_str(&merged) {
                        headers.insert(X_FORWARDED_FOR, hv);
                    }
                } else if let Ok(hv) = HeaderValue::from_str(ip) {
                    headers.insert(X_FORWARDED_FOR, hv);
                }
            }
            None => {
                if let Ok(hv) = HeaderValue::from_str(ip) {
                    headers.insert(X_FORWARDED_FOR, hv);
                }
            }
        }
        // X-Real-IP覆盖为真实客户端ip，客户端伪造值被替换，不追加保留
        if let Ok(hv) = HeaderValue::from_str(ip) {
            headers.insert(X_REAL_IP, hv);
        }
    }
    if !headers.contains_key(X_FORWARDED_PROTO)
        && let Ok(hv) = HeaderValue::from_str(proto)
    {
        headers.insert(X_FORWARDED_PROTO, hv);
    }
}

/// http crate未收录`X-Forwarded-*`/`X-Real-IP`常量，本地以静态`HeaderName`定义(`from_static`为const)
const X_FORWARDED_FOR: HeaderName = HeaderName::from_static("x-forwarded-for");
const X_FORWARDED_PROTO: HeaderName = HeaderName::from_static("x-forwarded-proto");
const X_FORWARDED_HOST: HeaderName = HeaderName::from_static("x-forwarded-host");
const X_REAL_IP: HeaderName = HeaderName::from_static("x-real-ip");

/// 处理函数(`/api`/{*rest}，body来自extensions.`BufferedBody`)，
/// 1. `circuit.is_ready` 不通过则 503 + `Retry-After`
/// 2. `upstream.forward` 成功则 record 置 true，失败则 record 置 false 并 503
/// 3. 响应头透传，剥离hop-by-hop，响应体`Body::from_stream`流式透传，不缓冲
/// 4. 防御性，上游响应头声明超限则拒绝，非2xx透传原状态码
pub async fn proxy_handler(
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> Response<Body> {
    let trace_id = trace_id_of(&req);

    // 熔断快速失败(OPEN 则 503 + Retry-After)
    if let Err(CircuitError::Open { retry_after_secs }) = state.circuit.is_ready() {
        warn!(trace_id = %trace_id, path = %req.uri().path(), retry_after_secs, "熔断拒绝转发");
        return error_response(GatewayError::CircuitOpen { retry_after_secs }, &trace_id);
    }

    // 预置X-Forwarded-Proto，按tls配置，forward内缺失时才会写入
    let mut req = req;
    let proto = if state.cfg.tls.cert.is_some() && state.cfg.tls.key.is_some() {
        "https"
    } else {
        "http"
    };
    if let Ok(hv) = HeaderValue::from_str(proto) {
        req.headers_mut().insert(X_FORWARDED_PROTO, hv);
    }

    // 转发，超时30s覆盖连接+头交换，多上游经pool选实例 + 失败自动重试
    //   连续失败 ≥3剔除、健康探测恢复，客户端ip供ip-hash模式使用
    //   连接对端地址，不可伪造，与限流/日志同源。
    //   熔断记录，任一实例成功record(true)，全部重试失败，最后一次错误才record(false)，
    //   多实例下熔断粒度降为整体兜底，实例级由健康探测管理
    let ip = extract_client_ip(&req);
    match state.upstream.forward(req, ip.as_deref()).await {
        Ok(resp) => {
            state.circuit.record(true);
            // 防御性，响应头声明超限则拒绝，流式体不缓冲，仅检查首包声明
            if let Some(Ok(v)) = resp.headers().get(CONTENT_LENGTH).map(|v| v.to_str())
                && let Ok(len) = v.parse::<u64>()
                && len > MAX_RESPONSE_DECLARED_BYTES
            {
                state.circuit.record(false);
                error!(trace_id = %trace_id, len, "上游响应声明超限");
                return error_response(GatewayError::UpstreamUnavailable, &trace_id);
            }

            let (mut parts, body) = resp.into_parts();
            // 响应头透传，剥离hop-by-hop，非2xx原状态码透传
            strip_hop_by_hop(&mut parts.headers);
            // 响应侧X-Trace-Id，与请求侧一致，供客户端/后端链路追踪，未采样请求
            // traceId为空串时不写空头，不注入
            if !trace_id.is_empty() {
                inject_trace(&mut parts.headers, &trace_id);
            }
            // 响应体流式透传，不collect、不缓冲
            let streamed = Body::from_stream(body.into_data_stream());
            Response::from_parts(parts, streamed)
        }
        Err(e) => {
            state.circuit.record(false);
            error!(trace_id = %trace_id, error = %e, "上游转发失败");
            let gw_err = match e {
                ProxyError::InvalidUri => GatewayError::Internal("invalid upstream uri".to_string()),
                _ => GatewayError::UpstreamUnavailable,
            };
            error_response(gw_err, &trace_id)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::circuit::CircuitState;
    use crate::state::test_state;
    use axum::http::header::{CONNECTION, HOST, HeaderName, TRANSFER_ENCODING, UPGRADE};
    use tower::ServiceExt;

    #[test]
    fn strip_hop_by_hop_removes_standard_and_connection_listed() {
        let mut h = HeaderMap::new();
        h.insert(CONNECTION, HeaderValue::from_static("keep-alive, x-custom"));
        h.insert(
            HeaderName::from_static("keep-alive"),
            HeaderValue::from_static("timeout=5"),
        );
        h.insert(TRANSFER_ENCODING, HeaderValue::from_static("chunked"));
        h.insert(UPGRADE, HeaderValue::from_static("websocket"));
        h.insert(HOST, HeaderValue::from_static("example.com"));
        h.insert("x-custom", HeaderValue::from_static("should-die"));
        h.insert("x-keep", HeaderValue::from_static("ok"));
        strip_hop_by_hop(&mut h);
        assert!(!h.contains_key(CONNECTION));
        assert!(!h.contains_key(HeaderName::from_static("keep-alive")));
        assert!(!h.contains_key(TRANSFER_ENCODING));
        assert!(!h.contains_key(UPGRADE));
        assert!(
            !h.contains_key("x-custom"),
            "Connection 头声明的字段应被剥离"
        );
        assert!(h.contains_key(HOST));
        assert!(h.contains_key("x-keep"));
    }

    #[test]
    fn forward_client_headers_appends_xff() {
        let mut h = HeaderMap::new();
        forward_client_headers(&mut h, Some("1.2.3.4"), "https");
        assert_eq!(
            h.get(X_FORWARDED_FOR).and_then(|v| v.to_str().ok()),
            Some("1.2.3.4")
        );
        assert_eq!(
            h.get(X_FORWARDED_PROTO).and_then(|v| v.to_str().ok()),
            Some("https")
        );
        // 已有xff则逗号追加
        h.insert(X_FORWARDED_FOR, HeaderValue::from_static("9.9.9.9"));
        forward_client_headers(&mut h, Some("1.2.3.4"), "http");
        assert_eq!(
            h.get(X_FORWARDED_FOR).and_then(|v| v.to_str().ok()),
            Some("9.9.9.9, 1.2.3.4")
        );
        // 已有X-Forwarded-Proto则不覆盖，调用方预置的tls信息保留
        assert_eq!(
            h.get(X_FORWARDED_PROTO).and_then(|v| v.to_str().ok()),
            Some("https")
        );
    }

    #[test]
    fn forward_client_headers_overrides_x_real_ip() {
        // 客户端伪造X-Real-IP则覆盖为真实客户端 ip，防上游信任伪造值
        let mut h = HeaderMap::new();
        h.insert(X_REAL_IP, HeaderValue::from_static("6.6.6.6"));
        forward_client_headers(&mut h, Some("1.2.3.4"), "https");
        assert_eq!(
            h.get(X_REAL_IP).and_then(|v| v.to_str().ok()),
            Some("1.2.3.4"),
            "X-Real-IP 必须覆盖为真实客户端 IP，客户端伪造值不得透传"
        );
    }

    #[test]
    fn forward_client_headers_no_ip() {
        let mut h = HeaderMap::new();
        forward_client_headers(&mut h, None, "http");
        assert!(!h.contains_key(X_FORWARDED_FOR));
        assert_eq!(
            h.get(X_FORWARDED_PROTO).and_then(|v| v.to_str().ok()),
            Some("http")
        );
    }

    #[test]
    fn build_upstream_uri_joins_base_path_query() {
        let uri = build_upstream_uri("http://127.0.0.1:8081/", "/api/gl/vouchers", Some("page=1"))
            .unwrap();
        assert_eq!(
            uri.to_string(),
            "http://127.0.0.1:8081/api/gl/vouchers?page=1"
        );
        let uri2 = build_upstream_uri("http://127.0.0.1:8081", "/api/auth/login", None).unwrap();
        assert_eq!(uri2.to_string(), "http://127.0.0.1:8081/api/auth/login");
    }

    #[test]
    fn build_upstream_uri_rejects_invalid_base() {
        assert!(matches!(
            build_upstream_uri("not a uri", "/x", None),
            Err(ProxyError::InvalidUri)
        ));
    }

    #[test]
    fn build_upstream_uri_preserves_encoding_and_query() {
        // percent编码与多参数query原样保留
        let uri = build_upstream_uri(
            "http://127.0.0.1:8081",
            "/api/search",
            Some("q=%2Fetc%2Fpasswd&page=1&size=20"),
        )
        .unwrap();
        assert_eq!(
            uri.to_string(),
            "http://127.0.0.1:8081/api/search?q=%2Fetc%2Fpasswd&page=1&size=20"
        );
        // base_url多个尾部斜杠全部去除，path含空格则非法 uri
        let uri2 = build_upstream_uri("http://h:1///", "/a", None).unwrap();
        assert_eq!(uri2.to_string(), "http://h:1/a");
        assert!(matches!(
            build_upstream_uri("http://h:1", "/a b", None),
            Err(ProxyError::InvalidUri)
        ));
    }

    #[test]
    fn upstream_client_new_validates_scheme_and_host() {
        let ok = UpstreamClient::new(
            "http://127.0.0.1:8081".to_string(),
            Duration::from_secs(30),
            Duration::from_secs(60),
            16,
        );
        assert!(ok.is_ok());
        assert_eq!(
            ok.unwrap().upstream_host,
            "127.0.0.1:8081",
            "D4：upstream_host 取 base_url authority"
        );
        let no_port = UpstreamClient::new(
            "http://127.0.0.1".to_string(),
            Duration::from_secs(30),
            Duration::from_secs(60),
            16,
        )
        .unwrap();
        assert_eq!(no_port.upstream_host, "127.0.0.1");
        assert!(
            matches!(
                UpstreamClient::new(
                    "ftp://127.0.0.1".to_string(),
                    Duration::from_secs(30),
                    Duration::from_secs(60),
                    16
                ),
                Err(ProxyError::InvalidUri)
            ),
            "非 http/https scheme 拒绝"
        );
        assert!(
            matches!(
                UpstreamClient::new(
                    "http://".to_string(),
                    Duration::from_secs(30),
                    Duration::from_secs(60),
                    16
                ),
                Err(ProxyError::InvalidUri)
            ),
            "无 host 拒绝"
        );
        assert!(matches!(
            UpstreamClient::new(
                "not a uri".to_string(),
                Duration::from_secs(30),
                Duration::from_secs(60),
                16
            ),
            Err(ProxyError::InvalidUri)
        ));
    }

    #[test]
    fn map_hyper_error_classifies_by_message() {
        // 注，实现按错误文本子串归类，"connection reset by peer" 含 "connect" 子串则归 Connect
        assert!(matches!(
            map_hyper_error("tcp connect error: connection refused"),
            ProxyError::Connect
        ));
        assert!(matches!(
            map_hyper_error("connection reset by peer"),
            ProxyError::Connect
        ));
        assert!(matches!(
            map_hyper_error("dns error: no host"),
            ProxyError::Connect
        ));
        assert!(matches!(
            map_hyper_error("operation timed out"),
            ProxyError::Timeout
        ));
        assert!(matches!(
            map_hyper_error("error: timeout exceeded"),
            ProxyError::Timeout
        ));
        assert!(matches!(map_hyper_error("broken pipe"), ProxyError::Send));
        assert!(matches!(
            map_hyper_error("io error: unexpected eof"),
            ProxyError::Send
        ));
    }

    #[test]
    fn strip_hop_by_hop_removes_proxy_and_case_insensitive() {
        let mut h = HeaderMap::new();
        h.insert(CONNECTION, HeaderValue::from_static("keep-alive"));
        h.insert("Keep-Alive", HeaderValue::from_static("timeout=5")); // 大小写变体
        h.insert("TE", HeaderValue::from_static("trailers"));
        h.insert("Trailer", HeaderValue::from_static("X-Checksum"));
        h.insert("Proxy-Authorization", HeaderValue::from_static("Basic abc"));
        h.insert("Proxy-Authenticate", HeaderValue::from_static("Basic"));
        h.insert("Proxy-Connection", HeaderValue::from_static("keep-alive"));
        h.insert("X-Keep", HeaderValue::from_static("ok"));
        strip_hop_by_hop(&mut h);
        for name in [
            "connection",
            "keep-alive",
            "te",
            "trailer",
            "proxy-authorization",
            "proxy-authenticate",
            "proxy-connection",
        ] {
            assert!(!h.contains_key(name), "{name} 应被剥离");
        }
        assert!(h.contains_key("x-keep"));
    }

    #[tokio::test]
    async fn proxy_handler_returns_503_when_circuit_open() {
        // 熔断OPEN，转发前快速失败，无网络调用
        let state = test_state();
        for _ in 0..20 {
            state.circuit.record(false);
        }
        assert_eq!(state.circuit.state(), CircuitState::Open);
        let app = axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(proxy_handler))
            .with_state(state);
        let req = axum::http::Request::builder()
            .uri("/api/gl/vouchers")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::SERVICE_UNAVAILABLE);
        let retry = resp
            .headers()
            .get("retry-after")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.parse::<u64>().ok());
        assert!(retry.is_some_and(|s| s >= 1), "熔断必须带 Retry-After");
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 50300);
    }
}
