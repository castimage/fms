//! jwt验签，HS256，与pg pgjwt兼容，严格校验签名 + exp，leeway容忍，
//! iss/aud宽松校验，配置了才比对，`mw_auth`为安全链第三层，验签通过后剥离客户端
//! `X-FMS-*`头、注入身份头、执行用户维度限流，`Claims`写入extensions
//!
//! 调用关系，`ws.rs`直接调用`verify_jwt`，ws query token，`log.rs`从extensions取`Claims`
#![forbid(unsafe_code)]

use crate::headers::{inject_identity, strip_client_fms_headers};
use crate::ratelimit::{RateLimitError, RateTier, rate_key};
use crate::state::AppState;
use crate::types::{GatewayError, LogUserSlot, error_response, now_secs, trace_id_of};
use crate::ws::is_websocket_request;
use axum::body::Body;
use axum::extract::State;
use axum::http::header::{AUTHORIZATION, HeaderName};
use axum::http::{Method, Request, Response};
use axum::middleware::Next;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use bytes::Bytes;
use http_body_util::BodyExt;
use jsonwebtoken::errors::ErrorKind;
use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Duration;
use uuid::Uuid;

/// `X-API-Key`头(系统间集成，网关透传，后端校验`um.api_keys`)
const X_API_KEY: HeaderName = HeaderName::from_static("x-api-key");

/// 解析jwt payload的exp，unix秒，base64url无padding，
/// 登出请求携带的access令牌已由`verify_jwt`验签，refresh令牌同会话签发，
/// 此处仅取exp为黑名单条目定ttl，到期即自动失效，解析失败返回`None`，
/// 调用方回退默认ttl，绝不因解析失败而放弃撤销，fail-closed。
fn token_exp_secs(token: &str) -> Option<i64> {
    let payload = token.split('.').nth(1)?;
    let bytes = base64url_decode(payload)?;
    let v: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    v.get("exp").and_then(|e| e.as_i64())
}

/// 最小base64url解码(RFC 4648 url安全字母表，无padding)，委托 base64 crate
fn base64url_decode(s: &str) -> Option<Vec<u8>> {
    URL_SAFE_NO_PAD.decode(s).ok()
}

/// `Claims`，与`pgjwt.sign` 产出的payload一致，HS256
/// sub = 用户id uuid文本，role = 角色，exp = unix秒，iss/aud可选，宽松校验
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Claims {
    pub sub: Uuid,
    pub role: String,
    pub exp: i64,
    pub iss: Option<String>,
    pub aud: Option<String>,
}

/// jwt验签错误
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthError {
    /// 无`Authorization`头
    Missing,
    /// 非Bearer格式 / 解析失败 / sub非uuid
    Malformed,
    /// exp < now - leeway，40101
    Expired,
    /// 签名不匹配(40100，显式映射`ErrorKind::InvalidSignature`，供审计区分签名不符与格式非法)
    InvalidSignature,
    /// iss配置了但不符，40100
    InvalidIssuer,
    /// aud配置了但不符，40100
    InvalidAudience,
}

/// 从`Authorization: Bearer`头验签，严格校验签名 + exp，`leeway`容忍，
/// iss/aud仅在expected_* 配置为Some时比对。
///
/// 与pg `pgjwt.sign` 产出的HS256 jwt兼容(`Claims`字段对齐)，网关与后端共用
/// 同一`jwt_secret`，验签通过即后端可采信的身份。
///
/// 校验顺序，先判缺头(`Missing`)，随后非 "Bearer " 前缀/空token/解析失败/sub非uuid
/// 属 (`Malformed`)，再验 exp过期(`Expired`)，随后签名不匹配(`InvalidSignature`，显式映射
/// `ErrorKind::InvalidSignature`供审计区分)，最后 iss/aud不符(`InvalidIssuer`/
/// `InvalidAudience`)。错误语义，所有失败均不泄露密钥/载荷细节，仅返回分类变体。
///
/// `leeway`取max，0防负数配置，关闭jsonwebtoken库默认aud校验
/// (audiences=`None`会误拒pgjwt省略aud的签发)，改由下方手动宽松比对，
/// expected_* 为`None`即不校验，宽松，为`Some`则必须逐字符相等，严格。
///
/// `header`，`Authorization`头值(`None` = 无认证头，含 "Bearer " 前缀)
///
/// `secret`，`jwt_secret`，HS256对称密钥，启动期已做强密钥校验
///
/// `expected_iss`，配置的iss(`None` = 不校验，`Some` = `claims.iss`必须相等)
///
/// `expected_aud`，配置的aud(`None` = 不校验，`Some` = `claims.aud`必须相等)
///
/// `leeway_secs`，exp容忍秒数，时钟偏差，负数按0处理
///
/// 返回验签通过的`Claims`，sub/role/exp/iss/aud，供注入身份头与限流使用
pub fn verify_jwt(
    header: Option<&str>,
    secret: &[u8],
    expected_iss: Option<&str>,
    expected_aud: Option<&str>,
    leeway_secs: i64,
) -> Result<Claims, AuthError> {
    let token = match header {
        None => return Err(AuthError::Missing),
        Some(h) => h.strip_prefix("Bearer ").ok_or(AuthError::Malformed)?,
    };

    if token.is_empty() {
        return Err(AuthError::Malformed);
    }

    let mut validation = Validation::new(Algorithm::HS256);
    validation.leeway = leeway_secs.max(0) as u64;
    // 关闭库默认aud校验(audiences=None会误拒)，由下方手动宽松比对，pgjwt签发可能省略aud
    validation.validate_aud = false;

    let data = decode::<Claims>(token, &DecodingKey::from_secret(secret), &validation).map_err(
        |e| match e.kind() {
            ErrorKind::ExpiredSignature => AuthError::Expired,
            ErrorKind::InvalidIssuer => AuthError::InvalidIssuer,
            ErrorKind::InvalidAudience => AuthError::InvalidAudience,
            ErrorKind::InvalidSignature => AuthError::InvalidSignature,
            _ => AuthError::Malformed,
        },
    )?;
    let claims = data.claims;

    if let Some(exp_iss) = expected_iss
        && claims.iss.as_deref() != Some(exp_iss)
    {
        return Err(AuthError::InvalidIssuer);
    }

    if let Some(exp_aud) = expected_aud
        && claims.aud.as_deref() != Some(exp_aud)
    {
        return Err(AuthError::InvalidAudience);
    }

    Ok(claims)
}

/// 匿名路径白名单，不要求jwt，`/healthz`、`/api/auth/login`、`/api/auth/refresh`、
/// `/api/auth/recovery`(含第二步`/api/auth/recovery/reset`)、`/api/auth/totp`/*、
/// `/api/nt/ws`、`/api/public`/*，登录页背景配置与背景图匿名读取，其余`/api`/** 一律
/// 要求认证，登录端点由限流单独保护，login tier。
///
/// 注，`/api/nt/ws`并非无条件匿名，仅真正的ws升级请求免jwt(query token由
/// `ws_handler`自持校验)，非升级http请求必须验签，2fa/refresh/recovery均发生在
/// access jwt签发之前，登录闭环由后端会话票据完成，网关只做ip/登录维度限流
/// recovery/reset复用ip维度每窗100次，一次性票据由后端30min ttl + 单次核销兜底
pub fn is_anonymous_path(path: &str) -> bool {
    matches!(
        path,
        "/healthz"
            | "/api/auth/login"
            | "/api/auth/refresh"
            | "/api/auth/recovery"
            | "/api/auth/recovery/reset"
            | "/api/nt/ws"
    ) || path.starts_with("/api/auth/totp/")
        || path.starts_with("/api/public/")
}

/// 中间件，安全链第三层auth，匿名路径放行(`/api/nt/ws`仅真ws升级请求免jwt)，
/// 验签失败则回 401，40100/40101，WARN不注入任何身份，通过则剥离客户端`X-FMS-*`、
/// 注入身份头、做用户维度限流，每窗200次超限429，`Claims`写入extensions供后续
/// sign/proxy/log使用。
///
/// 判定序，先匿名路径(含`/api/nt/ws`的双头判定，复用`ws::is_websocket_request`单一
/// 实现)，随后 api-key模式(无`Authorization`但带非空`X-API-Key`，网关无密钥库不校验，
/// 剥离客户端`X-FMS-*`后原样透传，由后端校验`um.api_keys`，签名身份段为空串，
/// 后端校验通过前不采信任何身份)，再 jwt验签，随后角色白名单，最后注入身份。
///
/// 安全语义，验签通过后剥离客户端`X-FMS-*`再注入`Claims`身份(客户端伪造头必须
/// 失效)，角色白名单拒绝未知角色(防任意字符串经`X-FMS-Role`注入使后端SET ROLE
/// 落空)，匿名路径默认不注入身份，但携带有效Bearer令牌时"尽力"注入(2fa
/// setup/enable/disable等匿名白名单端点既在登录闭环内又在已登录上下文中调用，
/// 后端强要身份，见`mw_auth`匿名分支)，用户维度限流在身份采信后执行，失败429时
/// 响应携带`Retry-After`。
/// 失败分类，缺失/格式非法/签名不符则 40100，过期则 40101，iss/aud不符则 40100。
///
/// `state`，全局状态(取`auth.jwt_secret`、iss/aud、leeway、`allowed_roles`、限流配置)
///
/// `req`，http请求
pub async fn mw_auth(
    State(state): State<Arc<AppState>>,
    mut req: Request<Body>,
    next: Next,
) -> Response<Body> {
    let trace_id = trace_id_of(&req);

    // 匿名路径放行，/api/nt/ws仅真ws升级请求(Upgrade+Connection双头)免jwt，
    // 非升级http请求必须验签，防未认证请求被匿名豁免转发上游
    let path = req.uri().path().to_string();
    // /api/nt/ws判定复用ws::is_websocket_request，单一实现，杜绝判定不对称
    let anonymous = if path == "/api/nt/ws" {
        is_websocket_request(req.headers(), &path)
    } else {
        is_anonymous_path(&path)
    };

    if anonymous {
        // 匿名路径双通道，无令牌/令牌无效则原样放行(不注入身份，2fa登录闭环
        // 依赖后端会话票据)，带有效Bearer令牌则验签 + 角色白名单后注入身份
        // (/api/auth/totp/setup|enable|disable等在已登录上下文中调用，后端
        // RequestContext.requireUserId 强要身份，不注入恒40100契约矛盾)。
        // 注入失败不阻断，匿名语义保持，仅放行时"尽力"带身份。
        if let Some(hdr) = req
            .headers()
            .get(AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            && let Ok(claims) = verify_jwt(
                Some(hdr),
                state.cfg.auth.jwt_secret.as_bytes(),
                state.cfg.auth.expected_iss.as_deref(),
                state.cfg.auth.expected_aud.as_deref(),
                state.cfg.auth.leeway_secs,
            )
            && state.cfg.auth.allowed_roles.iter().any(|r| r == &claims.role)
        {
            strip_client_fms_headers(req.headers_mut());
            inject_identity(req.headers_mut(), &claims.sub, &claims.role);
            tracing::debug!(
                trace_id = %trace_id,
                path = %req.uri().path(),
                user = %claims.sub,
                "匿名路径携带有效令牌：已注入身份"
            );
        }
        return next.run(req).await;
    }

    // 认证分支，jwt(Authorization: Bearer)或X-API-Key，系统间集成
    let auth_cfg = &state.cfg.auth;
    // owned String(避免借用req跨后续into_parts/extensions_mut移动冲突)
    let auth_header = req
        .headers()
        .get(AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .map(str::to_string);
    let api_key = req
        .headers()
        .get(X_API_KEY)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.trim())
        .filter(|s| !s.is_empty());

    // 无Authorization但带非空X-API-Key则进入 api Key模式，网关无密钥库不校验，
    // 原样透传由后端校验um.api_keys(sha256(key_hash) 比对)，不注入身份头、
    // 签名身份段为空串，后端校验通过前不得采信任何身份
    if auth_header.is_none() && api_key.is_some() {
        tracing::info!(trace_id = %trace_id, path = %req.uri().path(), "API Key 认证：透传后端校验(网关不注入身份)");
        strip_client_fms_headers(req.headers_mut());
        return next.run(req).await;
    }

    // 登出/改密黑名单，jwt模式，登出或改密成功后原access token在剩余
    // ttl内拒绝。验签前检查，被撤销的令牌即使签名仍有效也立即拒绝(与后端
    // refresh黑名单"验签之前先查"同序语义)，仅对Bearer jwt生效，api-key
    // 模式无状态不适用。命中则 401/40101。
    if let Some(token) = auth_header
        .as_deref()
        .and_then(|h| h.strip_prefix("Bearer "))
        .filter(|t| !t.is_empty())
        && state.deny.is_revoked(token).await
    {
        tracing::warn!(trace_id = %trace_id, path = %req.uri().path(), "认证失败：token 已注销(登出黑名单)");
        return error_response(GatewayError::TokenExpired, &trace_id);
    }

    let claims = match verify_jwt(
        auth_header.as_deref(),
        auth_cfg.jwt_secret.as_bytes(),
        auth_cfg.expected_iss.as_deref(),
        auth_cfg.expected_aud.as_deref(),
        auth_cfg.leeway_secs,
    ) {
        Ok(c) => c,
        Err(AuthError::Missing) | Err(AuthError::Malformed) => {
            tracing::warn!(trace_id = %trace_id, path = %req.uri().path(), "认证失败：缺失/非法 token");
            return error_response(GatewayError::Unauthorized, &trace_id);
        }
        Err(AuthError::Expired) => {
            tracing::warn!(trace_id = %trace_id, path = %req.uri().path(), "认证失败：token 过期");
            return error_response(GatewayError::TokenExpired, &trace_id);
        }
        Err(AuthError::InvalidSignature) => {
            tracing::warn!(trace_id = %trace_id, path = %req.uri().path(), "认证失败：签名不匹配");
            return error_response(GatewayError::Unauthorized, &trace_id);
        }
        Err(_) => {
            tracing::warn!(trace_id = %trace_id, path = %req.uri().path(), "认证失败：iss/aud 不符");
            return error_response(GatewayError::Unauthorized, &trace_id);
        }
    };

    // 角色白名单，claims.role必须为已登记角色，未知角色拒绝(防任意字符串
    // 经X-FMS-Role注入使后端SET ROLE落空或误映射)
    if !auth_cfg.allowed_roles.iter().any(|r| r == &claims.role) {
        tracing::warn!(trace_id = %trace_id, path = %req.uri().path(), role = %claims.role, "认证失败：角色不在白名单");
        return error_response(GatewayError::Unauthorized, &trace_id);
    }

    // 身份已采信，剥离客户端X-FMS-* 后注入身份，再做用户维度限流
    strip_client_fms_headers(req.headers_mut());
    inject_identity(req.headers_mut(), &claims.sub, &claims.role);
    let user_key = rate_key(RateTier::User, None, Some(&claims.sub.to_string()));
    if let Err(RateLimitError::Exceeded { retry_after_secs }) = state.limiter.check(
        &user_key,
        state.cfg.limits.user_per_window,
        Duration::from_secs(state.cfg.limits.window_secs),
    ) {
        tracing::info!(
            trace_id = %trace_id,
            user = %claims.sub,
            path = %req.uri().path(),
            retry_after_secs,
            "限流命中：用户维度"
        );
        return error_response(GatewayError::RateLimited { retry_after_secs }, &trace_id);
    }

    // Claims供后续sign/proxy使用，回填访问日志用户槽(外层mw_access_log先执行，
    // 经LogUserSlot拿到验签后的user)
    let sub = claims.sub.to_string();
    req.extensions_mut().insert(claims);
    if let Some(slot) = req.extensions().get::<LogUserSlot>()
        && let Ok(mut g) = slot.0.lock()
    {
        *g = Some(sub);
    }

    // 登出/改密拦截(body令牌原值写入网关进程内黑名单，ttl = 令牌自身exp，
    // 未解析出exp回退auth.logout_blacklist_ttl_secs(默认7200s对齐后端
    // access ttl))，使登出/改密后同一access token立即失效(业务端点
    // 401/40101)，弥补无状态jwt验签无法撤销的缺口。body在此缓冲后原样
    // 重建，对下游mw_sign/代理透明(请求自身的令牌刚验签通过且此刻尚未入
    // 黑名单，本请求正常放行)。
    //
    // 改密联动，改密后旧access token立即失效，POST /api/auth/change-password
    // 与登出同机制注销body携带的access/refresh令牌，前端契约
    // changePassword，oldPassword,newPassword 不携带令牌时回退注销当前
    // Authorization令牌，当前会话也必须立即失效，强制重新登录。与登出的
    // 差异，改密仅在先转发、后端响应2xx，改密成功后才注销，失败请求
    // 如旧密码错误401不注销令牌，避免误伤仍在正常使用的会话。
    if (path == "/api/auth/logout" || path == "/api/auth/change-password")
        && req.method() == Method::POST
    {
        // 改密body未携带令牌时的回退注销源，当前Authorization令牌原值
        let auth_token_owned = auth_header
            .as_deref()
            .and_then(|h| h.strip_prefix("Bearer "))
            .filter(|t| !t.is_empty())
            .map(str::to_string);
        let (parts, body) = req.into_parts();
        let bytes = match BodyExt::collect(body).await {
            Ok(b) => b.to_bytes(),
            Err(_) => Bytes::new(),
        };

        let mut pending: Vec<(String, i64)> = Vec::new();
        if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&bytes) {
            for key in ["accessToken", "refreshToken"] {
                if let Some(tok) = v.get(key).and_then(|t| t.as_str())
                    && !tok.is_empty()
                {
                    let exp = token_exp_secs(tok)
                        .unwrap_or_else(|| now_secs() + state.cfg.auth.logout_blacklist_ttl_secs);
                    pending.push((tok.to_string(), exp));
                }
            }
        }

        if path == "/api/auth/change-password"
            && pending.is_empty()
            && let Some(tok) = auth_token_owned
        {
            // 改密请求未携带令牌时，回退注销当前Authorization令牌，当前会话立即失效
            let exp = token_exp_secs(&tok)
                .unwrap_or_else(|| now_secs() + state.cfg.auth.logout_blacklist_ttl_secs);
            pending.push((tok, exp));
        }

        req = Request::from_parts(parts, Body::from(bytes));
        if path == "/api/auth/logout" {
            // 登出，无条件注销，幂等，请求本身放行
            for (tok, exp) in pending {
                state.deny.revoke(&tok, exp).await;
                tracing::info!(trace_id = %trace_id, "登出：token 已入网关黑名单(TTL={exp}s)");
            }
            return next.run(req).await;
        }

        // 改密，先转发，后端2xx，改密成功才注销，失败不误伤当前会话
        let resp = next.run(req).await;
        if resp.status().is_success() {
            for (tok, exp) in pending {
                state.deny.revoke(&tok, exp).await;
                tracing::info!(trace_id = %trace_id, "改密成功：token 已入网关黑名单(TTL={exp}s)");
            }
        } else {
            tracing::info!(
                trace_id = %trace_id,
                status = resp.status().as_u16(),
                "改密未成功(不注销令牌)"
            );
        }

        return resp;
    }

    next.run(req).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::test_state;
    use crate::types::now_secs;
    use jsonwebtoken::{EncodingKey, Header, encode};
    use tower::ServiceExt;

    fn sign_token(claims: &Claims, secret: &[u8]) -> String {
        encode(
            &Header::new(Algorithm::HS256),
            claims,
            &EncodingKey::from_secret(secret),
        )
        .expect("测试 token 签发")
    }

    #[test]
    fn verify_valid_token() {
        let secret = b"test-secret";
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: Some("fms".to_string()),
            aud: Some("fms-gateway".to_string()),
        };
        let token = sign_token(&claims, secret);
        let out = verify_jwt(
            Some(&format!("Bearer {token}")),
            secret,
            Some("fms"),
            Some("fms-gateway"),
            60,
        )
        .expect("验签通过");
        assert_eq!(out.sub, claims.sub);
        assert_eq!(out.role, "operator");
    }

    #[test]
    fn expired_token_rejected() {
        let secret = b"test-secret";
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "viewer".to_string(),
            exp: now_secs() - 1000,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, secret);
        let err = verify_jwt(Some(&format!("Bearer {token}")), secret, None, None, 60)
            .expect_err("过期必须拒绝");
        assert_eq!(err, AuthError::Expired);
    }

    #[test]
    fn tampered_signature_rejected() {
        let secret = b"test-secret";
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "auditor".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, secret);
        // 篡改签名段中部字符，长度不变且base64仍规范，解出不同字节即命中签名不符
        let mut tampered = token.clone();
        let len = tampered.len();
        let idx = len - 5;
        let repl = if tampered.as_bytes()[idx] == b'a' { b'b' } else { b'a' };
        tampered.replace_range(idx..idx + 1, &repl.to_string());
        let err = verify_jwt(Some(&format!("Bearer {tampered}")), secret, None, None, 60)
            .expect_err("篡改签名必须拒绝");
        // 签名不符显式映射为InvalidSignature
        assert_eq!(err, AuthError::InvalidSignature);
    }

    #[test]
    fn issuer_and_audience_strict_when_configured() {
        let secret = b"test-secret";
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "report".to_string(),
            exp: now_secs() + 3600,
            iss: Some("other-issuer".to_string()),
            aud: Some("other-aud".to_string()),
        };
        let token = sign_token(&claims, secret);
        let err = verify_jwt(
            Some(&format!("Bearer {token}")),
            secret,
            Some("fms"),
            None,
            60,
        )
        .expect_err("iss 不符必须拒绝");
        assert_eq!(err, AuthError::InvalidIssuer);
        let err2 = verify_jwt(
            Some(&format!("Bearer {token}")),
            secret,
            None,
            Some("fms-gateway"),
            60,
        )
        .expect_err("aud 不符必须拒绝");
        assert_eq!(err2, AuthError::InvalidAudience);
        // 未配置时宽松放行
        assert!(verify_jwt(Some(&format!("Bearer {token}")), secret, None, None, 60).is_ok());
    }

    #[test]
    fn missing_and_malformed_headers() {
        assert_eq!(
            verify_jwt(None, b"k", None, None, 60),
            Err(AuthError::Missing)
        );
        assert_eq!(
            verify_jwt(Some("Basic abc"), b"k", None, None, 60),
            Err(AuthError::Malformed),
            "非 Bearer 前缀按格式非法处理"
        );
        assert_eq!(
            verify_jwt(Some("Bearer not.a.jwt"), b"k", None, None, 60),
            Err(AuthError::Malformed)
        );
    }

    #[test]
    fn anonymous_path_whitelist() {
        // 基础三件套
        assert!(is_anonymous_path("/healthz"));
        assert!(is_anonymous_path("/api/auth/login"));
        assert!(is_anonymous_path("/api/nt/ws"));
        // 2fa/refresh/recovery在access jwt签发前，必须匿名
        assert!(is_anonymous_path("/api/auth/refresh"));
        assert!(is_anonymous_path("/api/auth/recovery"));
        // 找回第二步，一次性票据重置密码，匿名闭环第二步，非匿名会恒401
        assert!(is_anonymous_path("/api/auth/recovery/reset"));
        assert!(!is_anonymous_path("/api/auth/recovery/other"));
        assert!(is_anonymous_path("/api/auth/totp/verify"));
        assert!(is_anonymous_path("/api/auth/totp/setup"));
        assert!(is_anonymous_path("/api/auth/totp/enable"));
        assert!(is_anonymous_path("/api/auth/totp/disable"));
        // 登录页公开端点，背景配置与背景图匿名读取
        assert!(is_anonymous_path("/api/public/login-background"));
        assert!(is_anonymous_path("/api/public/files/0190abcd-0000-7000-8000-000000000001"));
        // 前缀误伤防护，totp前缀外的auth端点与业务路径一律要求认证
        assert!(!is_anonymous_path("/api/auth/totp"));
        assert!(!is_anonymous_path("/api/auth/logout"));
        assert!(!is_anonymous_path("/api/auth/"));
        assert!(!is_anonymous_path("/api/gl/vouchers"));
        assert!(!is_anonymous_path("/api/"));
    }

    #[test]
    fn role_whitelist_accepts_only_registered_roles() {
        let state = test_state();
        // 默认白名单，operator/viewer/auditor/report/admin
        assert!(state.cfg.auth.allowed_roles.iter().any(|r| r == "operator"));
        assert!(state.cfg.auth.allowed_roles.iter().any(|r| r == "admin"));
        assert!(
            !state
                .cfg
                .auth
                .allowed_roles
                .iter()
                .any(|r| r == "superroot")
        );
    }

    async fn echo_identity(req: Request<Body>) -> String {
        // 回显，注入的X-FMS-UserId / X-FMS-Role / Claims是否可见
        let uid = req
            .headers()
            .get("x-fms-userid")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();
        let role = req
            .headers()
            .get("x-fms-role")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();
        let claims_sub = req
            .extensions()
            .get::<Claims>()
            .map(|c| c.sub.to_string())
            .unwrap_or_default();
        format!("{uid}|{role}|{claims_sub}")
    }

    fn auth_router(state: Arc<AppState>) -> axum::Router {
        axum::Router::new()
            .route("/api/{*rest}", axum::routing::any(echo_identity))
            .route_layer(axum::middleware::from_fn_with_state(state.clone(), mw_auth))
            .with_state(state)
    }

    #[tokio::test]
    async fn mw_auth_ws_path_anonymous_only_for_real_upgrade() {
        // /api/nt/ws的匿名豁免仅限真ws升级请求，非升级http请求无token则 401
        let state = test_state();
        let app = auth_router(state);
        // 非升级http请求(无Upgrade头)+ 无token则 401/40100
        let req = Request::builder()
            .uri("/api/nt/ws")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::UNAUTHORIZED,
            "非升级 /api/nt/ws 请求必须验签(F4)，不得匿名转发"
        );
        // 真正的ws升级请求(Upgrade+Connection)则匿名放行(token由ws_handler校验)
        let req = Request::builder()
            .uri("/api/nt/ws")
            .header("upgrade", "websocket")
            .header("connection", "Upgrade")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::OK,
            "真 WS 升级请求保持匿名放行"
        );
        // 伪造升级头(仅Upgrade无Connection)则按非升级处理，401
        let req = Request::builder()
            .uri("/api/nt/ws")
            .header("upgrade", "websocket")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::UNAUTHORIZED,
            "缺 Connection: upgrade 不算升级请求(F4)"
        );
        // 非升级 + 有效token则放行并注入身份
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        let req = Request::builder()
            .uri("/api/nt/ws")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::OK,
            "带有效 token 的非升级请求应放行"
        );
    }

    #[tokio::test]
    async fn mw_auth_anonymous_path_passes_without_token() {
        let state = test_state();
        let app = auth_router(state);
        // 匿名路径无Authorization则放行，不注入身份
        let req = Request::builder()
            .uri("/api/auth/login")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        assert_eq!(
            String::from_utf8(body.to_vec()).unwrap(),
            "||",
            "匿名路径不得注入身份"
        );
    }

    #[tokio::test]
    async fn mw_auth_anonymous_path_injects_identity_when_valid_bearer() {
        // /api/auth/totp/* 匿名白名单端点带有效Bearer令牌时须注入身份
        // (后端RequestContext.requireUserId 强要身份)
        let state = test_state();
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        let app = auth_router(state.clone());
        let req = Request::builder()
            .uri("/api/auth/totp/setup")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK, "匿名路径带有效令牌应放行");
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        let (uid, rest) = text.split_once('|').expect("响应含 uid");
        let (role, _) = rest.split_once('|').expect("响应含 role");
        assert_eq!(role, "operator", "匿名路径带有效令牌应注入角色");
        assert_eq!(uid, claims.sub.to_string(), "匿名路径带有效令牌应注入用户 id");
        // 客户端伪造身份头必须被剥离，白名单重写
        let req = Request::builder()
            .uri("/api/auth/totp/enable")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .header("x-fms-role", "admin")
            .header("x-fms-userid", "forged")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        assert_eq!(text, format!("{}|operator|", claims.sub), "伪造身份头须剥离后注入 Claims 身份");
        // 无效令牌则按匿名放行，不注入身份，不401
        let req = Request::builder()
            .uri("/api/auth/totp/verify")
            .header(AUTHORIZATION, "Bearer fake.jwt.token")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK, "无效令牌在匿名路径应匿名放行");
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        assert_eq!(String::from_utf8(body.to_vec()).unwrap(), "||", "无效令牌不得注入身份");
    }

    #[tokio::test]
    async fn mw_auth_missing_or_invalid_token_rejected() {
        let state = test_state();
        let app = auth_router(state);
        // 非匿名/api路径无Authorization则 401 + 40100
        let req = Request::builder()
            .uri("/api/gl/vouchers")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::UNAUTHORIZED);
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 40100);
        // 伪造token则 401
        let req = Request::builder()
            .uri("/api/gl/vouchers")
            .header(AUTHORIZATION, "Bearer fake.jwt.token")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn mw_auth_valid_token_injects_identity_and_strips_client_fms() {
        let state = test_state();
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        let app = auth_router(state);
        let req = Request::builder()
            .uri("/api/gl/vouchers")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            // 客户端伪造身份头必须被剥离，白名单重写
            .header("x-fms-role", "admin")
            .header("x-fms-userid", "forged")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        let mut it = text.split('|');
        let uid = it.next().unwrap_or("");
        let role = it.next().unwrap_or("");
        let sub = it.next().unwrap_or("");
        assert_eq!(uid, claims.sub.to_string(), "注入的 UserId 必须取自 Claims");
        assert_eq!(
            role, "operator",
            "客户端伪造 role 必须被剥离并重写为 Claims.role"
        );
        assert_eq!(sub, claims.sub.to_string(), "Claims 必须写入 extensions");
    }

    #[tokio::test]
    async fn mw_auth_expired_token_returns_40101() {
        let state = test_state();
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "viewer".to_string(),
            exp: now_secs() - 1000,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        let app = auth_router(state);
        let req = Request::builder()
            .uri("/api/gl/vouchers")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::UNAUTHORIZED);
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 40101, "过期 token 应返回 40101");
    }

    #[tokio::test]
    async fn mw_auth_unknown_role_rejected_40100() {
        // 签名正确但role不在白名单，任意字符串 / 未登记角色则 401/40100，
        // 不得注入身份头(防未知role值经X-FMS-Role注入后端)
        let state = test_state();
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "superroot".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        let app = auth_router(state);
        let req = Request::builder()
            .uri("/api/gl/vouchers")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::UNAUTHORIZED,
            "未知角色必须 401"
        );
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 40100, "未知角色按未认证 40100 处理");
    }

    #[tokio::test]
    async fn mw_auth_api_key_mode_passes_through_without_identity() {
        // 无Authorization但带非空X-API-Key则放行，网关不校验，后端校验，
        // 不注入身份头，X-API-Key原样透传
        let state = test_state();
        let app = auth_router(state);
        let req = Request::builder()
            .uri("/api/rpt/entries")
            .header("x-api-key", "ak_live_abc123")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::OK,
            "API Key 请求必须放行(后端校验)"
        );
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        // 回显格式uid|role|claims_sub，三者皆空 = 未注入身份
        assert_eq!(
            String::from_utf8(body.to_vec()).unwrap(),
            "||",
            "API Key 模式不得注入身份"
        );
        // 空X-API-Key，仅空格/空串不构成api Key模式则按无Authorization 回 401
        let req = Request::builder()
            .uri("/api/rpt/entries")
            .header("x-api-key", "   ")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::UNAUTHORIZED,
            "空白 API Key 不得放行"
        );
        // 同时带合法jwt与X-API-Key则 jwt优先，注入jwt身份
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        let req = Request::builder()
            .uri("/api/rpt/entries")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .header("x-api-key", "ak_live_abc123")
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        assert!(
            text.starts_with(&format!("{}|operator|", claims.sub)),
            "JWT 优先于 API Key"
        );
    }

    #[tokio::test]
    async fn mw_auth_logout_revokes_tokens_and_blocks_reuse() {
        // 登出拦截，POST /api/auth/logout携带access/refresh则入网关黑名单，
        // 登出后同一access token访问业务端点则 401/40101，其他token不受影响
        let state = test_state();
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        let app = auth_router(state);
        // 登出请求，携带access/refresh令牌则放行，登出本身200
        let body = format!(r#"{{"accessToken":"{token}","refreshToken":"{token}"}}"#);
        let req = Request::builder()
            .method("POST")
            .uri("/api/auth/logout")
            .header("content-type", "application/json")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::from(body))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::OK,
            "登出请求本身应放行"
        );
        // 登出后同一access token访问业务端点则 401/40101
        let req2 = Request::builder()
            .uri("/api/gl/vouchers")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let resp2 = app.clone().oneshot(req2).await.unwrap();
        assert_eq!(
            resp2.status(),
            axum::http::StatusCode::UNAUTHORIZED,
            "登出后原 access token 必须失效(401)"
        );
        let body2 = BodyExt::collect(resp2.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body2).expect("合法 JSON");
        assert_eq!(v["code"], 40101, "已注销 token 按 40101 处理");
        // 其他用户token不受影响
        let claims2 = Claims {
            sub: Uuid::now_v7(),
            role: "viewer".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token2 = sign_token(&claims2, b"test-jwt-secret");
        let req3 = Request::builder()
            .uri("/api/gl/vouchers")
            .header(AUTHORIZATION, format!("Bearer {token2}"))
            .body(Body::empty())
            .unwrap();
        let resp3 = app.clone().oneshot(req3).await.unwrap();
        assert_eq!(
            resp3.status(),
            axum::http::StatusCode::OK,
            "未注销 token 不受影响"
        );
    }

    #[tokio::test]
    async fn mw_auth_revoked_token_rejected_before_signature_check() {
        // 黑名单检查先于验签，注销后即使令牌签名仍有效也立即拒绝，
        // 未注销的伪造token仍按40100，验签失败，登出黑名单不改变失败分类
        let state = test_state();
        // 手工构造已过期令牌入黑名单，ttl=now+3600，模拟"签名有效但已注销"
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        state.deny.revoke(&token, now_secs() + 3600).await;
        let app = auth_router(state);
        let req = Request::builder()
            .uri("/api/gl/vouchers")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::UNAUTHORIZED);
        let body = BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body).expect("合法 JSON");
        assert_eq!(v["code"], 40101);
    }

    #[tokio::test]
    async fn mw_auth_change_password_revokes_tokens_only_on_success() {
        // 改密联动，改密成功，后端2xx后原access token立即失效，401/40101，
        // 改密失败，如旧密码错误401不得注销令牌，会话保持可用
        let state = test_state();
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        // 失败改密，后端返回401，如旧密码错误则不得注销令牌
        let failing_app = axum::Router::new()
            .route(
                "/api/auth/change-password",
                axum::routing::post(|| async { axum::http::StatusCode::UNAUTHORIZED }),
            )
            .route("/api/{*rest}", axum::routing::any(echo_identity))
            .route_layer(axum::middleware::from_fn_with_state(
                state.clone(),
                mw_auth,
            ))
            .with_state(state.clone());
        let body = format!(r#"{{"oldPassword":"wrong","newPassword":"newPass123","accessToken":"{token}","refreshToken":"{token}"}}"#);
        let req = Request::builder()
            .method("POST")
            .uri("/api/auth/change-password")
            .header("content-type", "application/json")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::from(body))
            .unwrap();
        let resp = failing_app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::UNAUTHORIZED);
        // 失败后令牌仍可用，不误伤当前会话
        let req2 = Request::builder()
            .uri("/api/gl/vouchers")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let resp2 = failing_app.clone().oneshot(req2).await.unwrap();
        assert_eq!(
            resp2.status(),
            axum::http::StatusCode::OK,
            "改密失败不得注销令牌"
        );
        // 成功改密，后端2xx则 body携带的access token立即失效
        let app = auth_router(state);
        let body = format!(r#"{{"oldPassword":"oldPass1","newPassword":"newPass123","accessToken":"{token}","refreshToken":"{token}"}}"#);
        let req = Request::builder()
            .method("POST")
            .uri("/api/auth/change-password")
            .header("content-type", "application/json")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::from(body))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::OK,
            "改密请求本身应放行"
        );
        // 改密成功后同一access token访问业务端点则 401/40101
        let req3 = Request::builder()
            .uri("/api/gl/vouchers")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let resp3 = app.clone().oneshot(req3).await.unwrap();
        assert_eq!(
            resp3.status(),
            axum::http::StatusCode::UNAUTHORIZED,
            "改密成功后原 access token 必须失效(401)"
        );
        let body3 = BodyExt::collect(resp3.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body3).expect("合法 JSON");
        assert_eq!(v["code"], 40101, "已注销 token 按 40101 处理");
    }

    #[tokio::test]
    async fn mw_auth_change_password_fallback_revokes_authorization_token() {
        // 前端契约场景，改密body不携带令牌，changePassword，oldPassword,newPassword，
        // 网关回退注销当前Authorization令牌，当前会话立即失效
        let state = test_state();
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "operator".to_string(),
            exp: now_secs() + 3600,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        let app = auth_router(state);
        // 改密请求，body无令牌，仅带oldPassword/newPassword则 2xx放行
        let body = r#"{"oldPassword":"oldPass1","newPassword":"newPass123"}"#;
        let req = Request::builder()
            .method("POST")
            .uri("/api/auth/change-password")
            .header("content-type", "application/json")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::from(body))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        // 改密成功后当前Authorization令牌再访问则 401/40101
        let req2 = Request::builder()
            .uri("/api/auth/session")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let resp2 = app.clone().oneshot(req2).await.unwrap();
        assert_eq!(
            resp2.status(),
            axum::http::StatusCode::UNAUTHORIZED,
            "改密成功(body 无令牌)后当前会话令牌必须失效(401)"
        );
        let body2 = BodyExt::collect(resp2.into_body())
            .await
            .unwrap()
            .to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&body2).expect("合法 JSON");
        assert_eq!(v["code"], 40101);
    }

    #[test]
    fn token_exp_secs_parses_payload_and_falls_back() {
        // 正常jwt，payload含exp则返回 exp
        let claims = Claims {
            sub: Uuid::now_v7(),
            role: "report".to_string(),
            exp: now_secs() + 7200,
            iss: None,
            aud: None,
        };
        let token = sign_token(&claims, b"test-jwt-secret");
        assert_eq!(token_exp_secs(&token), Some(claims.exp));
        // 畸形token则返回 None，调用方回退默认ttl
        assert_eq!(token_exp_secs("not.a.jwt"), None);
        assert_eq!(token_exp_secs(""), None);
        // base64url非法字符则返回 None
        assert_eq!(token_exp_secs("eyJhIjoxfQ.!!!.sig"), None);
    }
}
