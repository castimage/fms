//! WebSocket升级代理，盲转发，不解析任何ws帧，path=`/api/nt/ws` + `Upgrade`/`Connection`
//! 双头判定(不满足退回`proxy_handler`)，认证取query ?token= 或`Authorization`头，
//! 用户维度限流 + 熔断，上游重建后send，101 则立即返回并后台双向泵(先101再await升级防死锁，
//! 升级超时10s清理)，非101透传状态码
//!
//! 调用关系，安全链分发调用`ws_handler`，`verify_jwt`/`rate_key`/`is_ready`复用各模块
#![forbid(unsafe_code)]

use crate::auth::{AuthError, verify_jwt};
use crate::circuit::CircuitError;
use crate::headers::{inject_identity, inject_trace, strip_client_fms_headers};
use crate::proxy::{build_upstream_uri, forward_client_headers, proxy_handler, strip_hop_by_hop_keep};
use crate::ratelimit::{RateLimitError, RateTier, rate_key};
use crate::sign::{build_signature_header, compute_body_hash};
use crate::state::AppState;
use crate::types::{GatewayError, LogUserSlot, error_response, extract_client_ip, trace_id_of};
use axum::body::Body;
use axum::extract::State;
use axum::http::header::{AUTHORIZATION, CONNECTION, HeaderMap, HeaderValue, ORIGIN, UPGRADE};
use axum::http::{HeaderName, Request, Response, StatusCode, Uri};
use hyper_util::rt::TokioIo;
use std::sync::Arc;
use std::time::Duration;
use tracing::{info, warn};

/// 从uri query提取token参数，jwt为base64url无需percent-decode，多参数用 & 分隔
pub fn extract_token_from_query(uri: &Uri) -> Option<&str> {
    let query = uri.query()?;
    for pair in query.split('&') {
        if let Some((k, v)) = pair.split_once('=')
            && k == "token"
            && !v.is_empty()
        {
            return Some(v);
        }
    }
    None
}

/// 重建上游握手query，剥离客户端的uid与token参数后注入令牌里的uid，
/// 其余参数原样保留，键大小写不敏感，端点按uid注册会话，该值只认网关注入
pub fn trusted_ws_query(query: Option<&str>, uid: &str) -> String {
    let kept: Vec<&str> = query
        .map(|q| {
            q.split('&')
                .filter(|pair| {
                    !pair.is_empty()
                        && !pair.split_once('=').is_some_and(|(k, _)| {
                            k.eq_ignore_ascii_case("uid") || k.eq_ignore_ascii_case("token")
                        })
                })
                .collect()
        })
        .unwrap_or_default();
    let mut rebuilt = kept.join("&");
    if !rebuilt.is_empty() {
        rebuilt.push('&');
    }
    rebuilt.push_str("uid=");
    rebuilt.push_str(uid);
    rebuilt
}

/// 判断ws升级请求，path == `/api/nt/ws`且`Upgrade`含websocket、`Connection`含upgrade
/// 大小写不敏感、逗号分隔匹配，`sign.rs` / `auth.rs`共用同一判定，杜绝不对称
pub fn is_websocket_request(headers: &HeaderMap, path: &str) -> bool {
    path == "/api/nt/ws"
        && header_contains_token(headers, &UPGRADE, "websocket")
        && header_contains_token(headers, &CONNECTION, "upgrade")
}

/// 头值中是否包含指定token，逗号分隔、trim、大小写不敏感
fn header_contains_token(
    headers: &HeaderMap,
    name: &HeaderName,
    token: &str,
) -> bool {
    headers
        .get_all(name)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .any(|v| {
            v.split(',')
                .any(|part| part.trim().eq_ignore_ascii_case(token))
        })
}

/// 处理函数(`/api/nt/ws`)，依次做 `Origin`校验、认证、限流、熔断、上游握手，随后双向泵。
/// 完整链路，非ws升级请求退回`proxy_handler`(按普通http转发，避免伪造`Upgrade`头
/// 绕过安全链)，随后 `Origin`校验(cswsh防护，`CorsLayer`不约束ws升级，浏览器握手的
/// `Origin`必须命中cors白名单，无`Origin`的非浏览器客户端放行，非白名单则回 403)，
/// 再做认证(query ?token= 优先于`Authorization`头，query token为裸jwt需补 "Bearer "
/// 前缀，否则`verify_jwt`恒按格式非法401，过期40101，其余40100，角色白名单同`mw_auth`)，
/// 随后用户维度限流(ip维度已由`mw_rate_limit`计过一次不重复)，再做熔断检查，随后握手body
/// 声明长度防御性检查(`mw_sign`对ws升级跳过缓冲，此处兜底413)，然后重建上游请求，
/// 最后上游握手。
///
/// 上游请求重建要点，保留`Connection`/`Upgrade`/`Sec-WebSocket-*`与全部业务头，
/// hop-by-hop剥离仅豁免connection/upgrade，升级所需，共享proxy单一清单防漂移，
/// 剥离客户端`X-FMS-*`后注入`Claims`身份与trace，`Host`强制重写为上游authority
/// (防`Host`投毒)，原`Host`记入`X-Forwarded-Host`，`X-Forwarded-Proto`无条件覆盖
/// 客户端伪造值。
///
/// 结果语义，101 则走 `pump_bidirectional`，先回101再后台泵，非101 则透传上游原
/// 状态码，401/404/503等，body流式透传，record(true) 视为成功，握手失败则
/// record(false) + 503。
///
/// `state`，全局状态，auth配置、限流、熔断、上游客户端
///
/// `req`，客户端请求(含`/api/nt/ws`路径，ws升级头)
///
/// @return 101升级响应或错误响应/上游透传响应
pub async fn ws_handler(
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> Response<Body> {
    let trace_id = trace_id_of(&req);

    // 非ws升级请求则按普通 http 交给 proxy_handler
    if !is_websocket_request(req.headers(), req.uri().path()) {
        return proxy_handler(State(state), req).await;
    }

    // Origin校验，cswsh防护，浏览器ws握手携带的Origin必须命中cors白名单
    // (CorsLayer不约束ws升级)，无Origin的非浏览器客户端放行，非白名单则 403
    if let Some(origin) = req.headers().get(ORIGIN).and_then(|v| v.to_str().ok())
        && !state.cfg.cors.allowed_origins.iter().any(|o| o == origin)
    {
        warn!(trace_id = %trace_id, origin, "WS 升级 Origin 不在白名单，拒绝");
        return error_response(
            GatewayError::Forbidden("origin not allowed".to_string()),
            &trace_id,
        );
    }

    // 认证，query ?token= 优先，其次Authorization头，query token为裸jwt，
    // verify_jwt要求 "Bearer " 前缀，需补上，否则按格式非法恒401
    let token: Option<String> = extract_token_from_query(req.uri())
        .map(|t| format!("Bearer {t}"))
        .or_else(|| {
            req.headers()
                .get(AUTHORIZATION)
                .and_then(|v| v.to_str().ok())
                .map(String::from)
        });
    // 登出黑名单，已注销token拒绝建立ws(与mw_auth同语义，验签前检查)
    if let Some(tok) = token.as_deref()
        .and_then(|t| t.strip_prefix("Bearer "))
        && state.deny.is_revoked(tok).await
    {
        warn!(trace_id = %trace_id, "WS 认证失败：token 已注销(登出黑名单)");
        return error_response(GatewayError::TokenExpired, &trace_id);
    }
    let auth_cfg = &state.cfg.auth;
    let claims = match verify_jwt(
        token.as_deref(),
        auth_cfg.jwt_secret.as_bytes(),
        auth_cfg.expected_iss.as_deref(),
        auth_cfg.expected_aud.as_deref(),
        auth_cfg.leeway_secs,
    ) {
        Ok(c) => {
            // 角色白名单，claims.role必须为已登记角色，未知角色拒绝
            if !auth_cfg.allowed_roles.iter().any(|r| r == &c.role) {
                warn!(trace_id = %trace_id, role = %c.role, "WS 认证失败：角色不在白名单");
                return error_response(GatewayError::Unauthorized, &trace_id);
            }
            // 回填访问日志用户槽(外层mw_access_log先于本处理器执行)
            if let Some(slot) = req.extensions().get::<LogUserSlot>()
                && let Ok(mut g) = slot.0.lock()
            {
                *g = Some(c.sub.to_string());
            }
            c
        }
        Err(AuthError::Expired) => {
            warn!(trace_id = %trace_id, "WS 认证失败：token 过期");
            return error_response(GatewayError::TokenExpired, &trace_id);
        }
        Err(_) => {
            warn!(trace_id = %trace_id, "WS 认证失败：无效 token");
            return error_response(GatewayError::Unauthorized, &trace_id);
        }
    };

    // 限流，用户维度(ip维度已由mw_rate_limit计过一次，不重复)+ 熔断检查，
    // 握手body声明长度防御性检查(mw_sign对ws升级跳过缓冲，上限取配置)
    let ip = extract_client_ip(&req);
    if let Some(len) = req
        .headers()
        .get(axum::http::header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok())
        && len > state.cfg.server.max_body_bytes as u64
    {
        warn!(trace_id = %trace_id, len, "WS 握手 body 声明超限");
        return error_response(GatewayError::PayloadTooLarge, &trace_id);
    }
    let user_key = rate_key(RateTier::User, None, Some(&claims.sub.to_string()));
    if let Err(RateLimitError::Exceeded { retry_after_secs }) = state
        .limiter
        .check(
            &user_key,
            state.cfg.limits.user_per_window,
            Duration::from_secs(state.cfg.limits.window_secs),
        )
    {
        info!(trace_id = %trace_id, "WS 限流：用户维度");
        return error_response(GatewayError::RateLimited { retry_after_secs }, &trace_id);
    }
    if let Err(CircuitError::Open { retry_after_secs }) = state.circuit.is_ready() {
        warn!(trace_id = %trace_id, "WS 熔断拒绝");
        return error_response(GatewayError::CircuitOpen { retry_after_secs }, &trace_id);
    }

    // 选取ws目标实例并固定(ip-hash，有ip时或round-robin，长连接粘性，
    // 握手后双向泵全程同一实例，不参与重试)，无健康实例则 503
    // 与proxy路径同语义，实例级剔除由pool健康探测管理
    let Some(target) = state.upstream.ws_target(ip.as_deref()) else {
        warn!(trace_id = %trace_id, "WS 握手：无健康上游实例");
        state.circuit.record(false);
        return error_response(GatewayError::UpstreamUnavailable, &trace_id);
    };

    // 重建上游请求，同path，保留Connection/Upgrade/Sec-WebSocket-*，
    // 剥离X-FMS-*后注入身份、签名与trace
    let (client_parts, _) = req.into_parts();
    let mut headers = client_parts.headers.clone();
    // ws场景剥离hop-by-hop，保留Connection/Upgrade，升级所需，其余共享proxy单一清单
    strip_hop_by_hop_keep(&mut headers, &["connection", "upgrade"]);
    strip_client_fms_headers(&mut headers);
    inject_identity(&mut headers, &claims.sub, &claims.role);
    inject_trace(&mut headers, &trace_id);

    let sign_secret = state.cfg.sign.sign_secret.as_bytes();
    let ws_sign = build_signature_header(
        sign_secret,
        client_parts.method.as_str(),
        client_parts.uri.path(),
        &compute_body_hash(&[]),
        &claims.sub.to_string(),
        &claims.role,
    );
    if let Ok(hv) = HeaderValue::from_str(&ws_sign) {
        // 静态常量头名，parse不可能失败，不变量
        const X_FMS_SIGNATURE: HeaderName =
            HeaderName::from_static("x-fms-signature");
        headers.insert(X_FMS_SIGNATURE, hv);
    }
    // Host强制重写为选中实例的authority(防Host投毒污染上游路由/缓存/日志)，
    // 原Host记入X-Forwarded-Host，若不同于上游host且未预置
    let upstream_host = target.authority.clone();
    let client_host = headers
        .get(axum::http::header::HOST)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());
    if let Ok(hv) = HeaderValue::from_str(&upstream_host) {
        headers.insert(axum::http::header::HOST, hv);
    }
    if let Some(ch) = client_host
        && ch != upstream_host
        && !headers.contains_key("x-forwarded-host")
        && let Ok(hv) = HeaderValue::from_str(&ch)
    {
        // 静态常量头名，parse不可能失败，不变量
        const X_FORWARDED_HOST: HeaderName =
            HeaderName::from_static("x-forwarded-host");
        headers.insert(X_FORWARDED_HOST, hv);
    }
    let proto = if state.cfg.tls.cert.is_some() && state.cfg.tls.key.is_some() {
        "https"
    } else {
        "http"
    };
    // X-Forwarded-Proto预置，无条件覆盖客户端伪造值，与proxy路径行为一致
    if let Ok(hv) = HeaderValue::from_str(proto) {
        // 静态常量头名，parse不可能失败，不变量
        const X_FORWARDED_PROTO: HeaderName =
            HeaderName::from_static("x-forwarded-proto");
        headers.insert(X_FORWARDED_PROTO, hv);
    }
    forward_client_headers(&mut headers, ip.as_deref(), proto);
    // 上游query重建，剥离客户端uid与token后注入令牌里的uid，端点身份取自信任头而非该参数，
    // 此处归一为避免客户端可控值随请求流入上游与访问日志
    let up_query = trusted_ws_query(client_parts.uri.query(), &claims.sub.to_string());
    let uri = match build_upstream_uri(&target.base_url, client_parts.uri.path(), Some(&up_query)) {
        Ok(u) => u,
        Err(_) => {
            return error_response(
                GatewayError::Internal("invalid upstream uri".to_string()),
                &trace_id,
            );
        }
    };
    let mut builder = Request::builder()
        .method(client_parts.method.clone())
        .uri(uri);
    for (k, v) in headers.iter() {
        builder = builder.header(k, v);
    }
    let up_req = match builder.body(Body::empty()) {
        Ok(r) => r,
        Err(_) => {
            return error_response(
                GatewayError::Internal("bad upstream request".to_string()),
                &trace_id,
            );
        }
    };

    // 上游握手(request_timeout覆盖握手全程，发往选定实例，长连接粘性)
    match state.upstream.send_for_ws(up_req, &target).await {
        Ok((resp, _authority)) => {
            if resp.status() == StatusCode::SWITCHING_PROTOCOLS {
                pump_bidirectional(state, client_parts, resp, &trace_id).await
            } else {
                // 非101，透传上游原状态码，如401/404/503，body已为axum Body流
                state.circuit.record(true);
                let (parts, body) = resp.into_parts();
                let mut resp = Response::from_parts(parts, body);
                inject_trace(resp.headers_mut(), &trace_id);
                resp
            }
        }
        Err(e) => {
            state.circuit.record(false);
            warn!(trace_id = %trace_id, error = %e, "WS 上游握手失败");
            error_response(GatewayError::UpstreamUnavailable, &trace_id)
        }
    }
}

/// 双向泵，先立即返回101(头部透传上游，含`Sec-WebSocket-Accept`)，后台任务内
/// await两侧upgrade(hyper 1.x语义，101不代表升级必然发生，`None` = 对端未真正升级)
/// 再经 `TokioIo` 转发 `copy_bidirectional`。
/// 必须先返回101再await升级，hyper服务端只有在写出101后才完成升级并触发
/// upgrade future，同步等待会死锁，101永不写出、任务与连接永久占用，可被连接耗尽dos，
/// 升级等待带`ws.upgrade_timeout_secs`超时兜底。
///
/// 泵生命周期与熔断联动，两侧升级成功则 `copy_bidirectional` 双向盲转发(不解析任何
/// ws帧)，连接断开即record，true，长连接正常存续即视为上游可用，任一侧升级失败
/// (对端未真正升级，返回`None`)则 record 置 false，`upgrade_timeout_secs`内未完成升级则
/// 清理任务与连接，防永久占用，record(false)。本函数不做任何ws帧级校验，全部
/// 语义交给上游与客户端。
///
/// `state`，熔断器
///
/// `client_parts`，客户端请求parts(供`hyper::upgrade::on`触发客户端侧升级)
///
/// `server_resp`，上游101响应，parts用于触发服务端侧升级，头复制给客户端
///
/// `trace_id`，链路追踪id，写回客户端101响应头与日志
///
/// @return 101响应(含上游`Sec-WebSocket-Accept`等升级头)，构造失败回500
async fn pump_bidirectional(
    state: Arc<AppState>,
    client_parts: axum::http::request::Parts,
    server_resp: Response<Body>,
    trace_id: &str,
) -> Response<Body> {
    let (server_parts, _) = server_resp.into_parts();
    // 复制101响应头(Sec-WebSocket-Accept等)给客户端，parts随后被on消费
    let mut server_headers = server_parts.headers.clone();
    strip_hop_by_hop_keep(&mut server_headers, &["connection", "upgrade"]);
    inject_trace(&mut server_headers, trace_id);

    // hyper 1的CanUpgrade实现于Request/Response(而非Parts)，需重建空体消息
    let client_upgrade = hyper::upgrade::on(Request::from_parts(client_parts, ()));
    let server_upgrade = hyper::upgrade::on(Response::from_parts(server_parts, ()));
    let trace_id_log = trace_id.to_string();
    let upgrade_timeout = Duration::from_secs(state.cfg.ws.upgrade_timeout_secs);

    // 先返回101，触发请求侧升级future，后台任务再await两侧升级并双向泵，不解析任何ws帧
    tokio::spawn(async move {
        let upgraded = tokio::time::timeout(upgrade_timeout, async {
            match (client_upgrade.await, server_upgrade.await) {
                (Ok(client_conn), Ok(server_conn)) => Some((client_conn, server_conn)),
                _ => None,
            }
        })
        .await;
        match upgraded {
            Ok(Some((client_conn, server_conn))) => {
                // 两侧升级就绪，开始双向泵
                let mut client_io = TokioIo::new(client_conn);
                let mut server_io = TokioIo::new(server_conn);
                let result = tokio::io::copy_bidirectional(&mut client_io, &mut server_io).await;
                if let Err(e) = result {
                    info!(trace_id = %trace_id_log, error = %e, "WS 双向泵 IO 结束(非零退出为正常断开)");
                }
                state.circuit.record(true);
            }
            Ok(None) => {
                // 任一侧升级失败，对端未真正升级
                warn!(trace_id = %trace_id_log, "WS 升级失败(对端未完成升级)");
                state.circuit.record(false);
            }
            Err(_) => {
                warn!(trace_id = %trace_id_log, "WS 升级超时(upgrade_timeout_secs 内未完成握手)，清理连接");
                state.circuit.record(false);
            }
        }
    });

    // 返回101，hyper服务器据此完成客户端侧升级
    match Response::builder()
        .status(StatusCode::SWITCHING_PROTOCOLS)
        .body(Body::empty())
    {
        Ok(mut resp) => {
            *resp.headers_mut() = server_headers;
            resp
        }
        Err(_) => error_response(GatewayError::Internal("bad 101 response".to_string()), trace_id),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::Claims;
    use crate::filter::MAX_BODY_BYTES;
    use crate::state::test_state;
    use crate::types::now_secs;
    use axum::http::header::HeaderValue;
    use tower::ServiceExt;

    fn uri_with_query(q: &str) -> Uri {
        format!("http://x/api/nt/ws?{q}").parse().unwrap()
    }

    #[test]
    fn extract_token_present() {
        assert_eq!(
            extract_token_from_query(&uri_with_query("token=abc.def.ghi")),
            Some("abc.def.ghi")
        );
    }

    #[test]
    fn trusted_query_replaces_client_uid_and_drops_token() {
        assert_eq!(
            trusted_ws_query(Some("uid=attacker&token=leak.jwt.value"), "trusted-uid"),
            "uid=trusted-uid"
        );
    }

    #[test]
    fn trusted_query_keeps_other_params() {
        assert_eq!(
            trusted_ws_query(Some("UID=attacker&a=1&b=2&token=x"), "u"),
            "a=1&b=2&uid=u"
        );
    }

    #[test]
    fn trusted_query_handles_absent_query() {
        assert_eq!(trusted_ws_query(None, "u"), "uid=u");
        assert_eq!(trusted_ws_query(Some(""), "u"), "uid=u");
    }

    #[test]
    fn extract_token_absent() {
        assert_eq!(extract_token_from_query(&uri_with_query("code=42")), None);
        assert_eq!(
            extract_token_from_query(&Uri::from_static("/api/nt/ws")),
            None
        );
        assert_eq!(
            extract_token_from_query(&uri_with_query("token=")),
            None,
            "空 token 视为缺失"
        );
    }

    #[test]
    fn extract_token_with_other_params() {
        let uri = uri_with_query("a=1&token=xyz&b=2");
        assert_eq!(extract_token_from_query(&uri), Some("xyz"));
    }

    #[test]
    fn is_websocket_request_detection() {
        use axum::http::header::{CONNECTION, UPGRADE};
        let mut h = HeaderMap::new();
        h.insert(UPGRADE, HeaderValue::from_static("websocket"));
        h.insert(CONNECTION, HeaderValue::from_static("keep-alive, Upgrade"));
        assert!(is_websocket_request(&h, "/api/nt/ws"));
        // 大小写不敏感
        let mut h2 = HeaderMap::new();
        h2.insert(UPGRADE, HeaderValue::from_static("WebSocket"));
        h2.insert(CONNECTION, HeaderValue::from_static("upgrade"));
        assert!(is_websocket_request(&h2, "/api/nt/ws"));
        // 路径不匹配 / 缺头则按非 ws
        assert!(!is_websocket_request(&h, "/api/nt/other"));
        let mut h3 = HeaderMap::new();
        h3.insert(UPGRADE, HeaderValue::from_static("websocket"));
        assert!(
            !is_websocket_request(&h3, "/api/nt/ws"),
            "缺 Connection: upgrade"
        );
        let mut h4 = HeaderMap::new();
        h4.insert(CONNECTION, HeaderValue::from_static("upgrade"));
        assert!(
            !is_websocket_request(&h4, "/api/nt/ws"),
            "缺 Upgrade: websocket"
        );
    }

    #[test]
    fn strip_hop_by_hop_ws_keeps_upgrade_headers() {
        use axum::http::header::{CONNECTION, HeaderName, TRANSFER_ENCODING, UPGRADE};
        let mut h = HeaderMap::new();
        h.insert(UPGRADE, HeaderValue::from_static("websocket"));
        h.insert(CONNECTION, HeaderValue::from_static("Upgrade"));
        h.insert(
            HeaderName::from_static("keep-alive"),
            HeaderValue::from_static("timeout=5"),
        );
        h.insert(TRANSFER_ENCODING, HeaderValue::from_static("chunked"));
        h.insert(
            HeaderName::from_static("proxy-authorization"),
            HeaderValue::from_static("x"),
        );
        strip_hop_by_hop_keep(&mut h, &["connection", "upgrade"]);
        assert!(h.contains_key(UPGRADE), "WS 必须保留 Upgrade");
        assert!(h.contains_key(CONNECTION), "WS 必须保留 Connection");
        assert!(!h.contains_key(HeaderName::from_static("keep-alive")));
        assert!(!h.contains_key(TRANSFER_ENCODING));
        assert!(!h.contains_key(HeaderName::from_static("proxy-authorization")));
    }

    /// 强制熔断OPEN(默认cfg，`min_samples` 20)
    fn open_circuit(state: &Arc<AppState>) {
        for _ in 0..20 {
            state.circuit.record(false);
        }
    }

    /// 签发测试token，HS256
    fn sign_test_token(claims: &Claims) -> String {
        jsonwebtoken::encode(
            &jsonwebtoken::Header::new(jsonwebtoken::Algorithm::HS256),
            claims,
            &jsonwebtoken::EncodingKey::from_secret(b"test-jwt-secret"),
        )
        .expect("测试 token 签发")
    }

    fn ws_router(state: Arc<AppState>) -> axum::Router {
        axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(ws_handler))
            .with_state(state)
    }

    #[tokio::test]
    async fn ws_handler_unauthorized_without_token() {
        // 升级请求但无token则 401，40100
        let state = test_state();
        let app = ws_router(state);
        let req = Request::builder()
            .method("GET")
            .uri("/api/nt/ws")
            .header(UPGRADE, HeaderValue::from_static("websocket"))
            .header(CONNECTION, HeaderValue::from_static("Upgrade"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 40100);
    }

    #[tokio::test]
    async fn ws_handler_expired_token_is_40101() {
        // 过期token则 401 + code 40101
        let state = test_state();
        let claims = Claims {
            sub: uuid::Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() - 1000,
            iss: None,
            aud: None,
        };
        let token = sign_test_token(&claims);
        let app = ws_router(state);
        let req = Request::builder()
            .method("GET")
            .uri(format!("/api/nt/ws?token={token}"))
            .header(UPGRADE, HeaderValue::from_static("websocket"))
            .header(CONNECTION, HeaderValue::from_static("Upgrade"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 40101);
    }

    #[tokio::test]
    async fn ws_handler_valid_token_rejected_by_open_circuit() {
        // 有效token，query+ 熔断OPEN 则 503，无网络调用，链路到circuit为止
        let state = test_state();
        let claims = Claims {
            sub: uuid::Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_test_token(&claims);
        open_circuit(&state);
        let app = ws_router(state);
        let req = Request::builder()
            .method("GET")
            .uri(format!("/api/nt/ws?token={token}"))
            .header(UPGRADE, HeaderValue::from_static("websocket"))
            .header(CONNECTION, HeaderValue::from_static("Upgrade"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert!(resp.headers().contains_key("retry-after"));
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 50300);
    }

    #[tokio::test]
    async fn ws_handler_evil_origin_403() {
        // 非白名单Origin的ws升级则 403/40300，cswsh防护，
        // 即使持有有效token也拒绝，不触达认证之后任何环节
        let state = test_state();
        let claims = Claims {
            sub: uuid::Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_test_token(&claims);
        let app = ws_router(state);
        let req = Request::builder()
            .method("GET")
            .uri(format!("/api/nt/ws?token={token}"))
            .header(UPGRADE, HeaderValue::from_static("websocket"))
            .header(CONNECTION, HeaderValue::from_static("Upgrade"))
            .header(ORIGIN, HeaderValue::from_static("https://evil.example.com"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            StatusCode::FORBIDDEN,
            "非白名单 Origin 应 403"
        );
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 40300);
    }

    #[tokio::test]
    async fn ws_handler_whitelisted_origin_and_missing_origin_allowed() {
        // 兼容性，白名单Origin，与cors一致与无Origin，Flutter/cli均放行，
        // 进入认证链，evil/null Origin仍403
        let state = test_state();
        let app = ws_router(state);
        // 白名单Origin + 无token则走认证，最终 401(未被Origin校验误伤)
        let req = Request::builder()
            .method("GET")
            .uri("/api/nt/ws")
            .header(UPGRADE, HeaderValue::from_static("websocket"))
            .header(CONNECTION, HeaderValue::from_static("Upgrade"))
            .header(ORIGIN, HeaderValue::from_static("https://fms.example.com"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            StatusCode::UNAUTHORIZED,
            "白名单 Origin 应进入认证链(401)"
        );
        // 无Origin，非浏览器客户端则同样进入认证链，最终 401
        let req = Request::builder()
            .method("GET")
            .uri("/api/nt/ws")
            .header(UPGRADE, HeaderValue::from_static("websocket"))
            .header(CONNECTION, HeaderValue::from_static("Upgrade"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            StatusCode::UNAUTHORIZED,
            "无 Origin 应进入认证链(401)"
        );
        // null Origin 则 403，与cors白名单一致，禁止file:///sandbox场景
        let req = Request::builder()
            .method("GET")
            .uri("/api/nt/ws")
            .header(UPGRADE, HeaderValue::from_static("websocket"))
            .header(CONNECTION, HeaderValue::from_static("Upgrade"))
            .header(ORIGIN, HeaderValue::from_static("null"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::FORBIDDEN, "null Origin 应 403");
    }

    #[tokio::test]
    async fn ws_handler_oversize_handshake_body_413() {
        // ws握手body声明超限(mw_sign对ws跳过缓冲，此处防御性检查)则 413
        let state = test_state();
        let claims = Claims {
            sub: uuid::Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_test_token(&claims);
        let app = ws_router(state);
        let req = Request::builder()
            .method("GET")
            .uri(format!("/api/nt/ws?token={token}"))
            .header(UPGRADE, HeaderValue::from_static("websocket"))
            .header(CONNECTION, HeaderValue::from_static("Upgrade"))
            .header(
                "content-length",
                (MAX_BODY_BYTES as u64 + 1).to_string(),
            )
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::PAYLOAD_TOO_LARGE);
        // 正常声明长度不受影响(走 circuit 检查，默认配置 CLOSED 则上游连接失败 503，
        // 说明未被body检查误伤)
        let req = Request::builder()
            .method("GET")
            .uri(format!("/api/nt/ws?token={token}"))
            .header(UPGRADE, HeaderValue::from_static("websocket"))
            .header(CONNECTION, HeaderValue::from_static("Upgrade"))
            .header("content-length", "100")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_ne!(
            resp.status(),
            StatusCode::PAYLOAD_TOO_LARGE,
            "正常长度握手不得被 413 误伤"
        );
    }

    #[tokio::test]
    async fn ws_handler_non_upgrade_delegates_to_proxy() {
        // 非升级请求(无Upgrade头)则走 proxy_handler，熔断OPEN 则 503
        let state = test_state();
        open_circuit(&state);
        let app = ws_router(state);
        let req = Request::builder()
            .method("GET")
            .uri("/api/nt/ws")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::SERVICE_UNAVAILABLE);
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 50300, "非升级请求按普通 HTTP 走代理");
    }
}
