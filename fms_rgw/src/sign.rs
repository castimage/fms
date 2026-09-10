//! `X-FMS-Signature` hmac-sha256签名与请求体缓冲(`mw_sign`为安全链第四层sign，
//! 身份头纳入hmac输入，防网关到后端一跳内换头不改签，`NonceStore`定义于`nonce.rs`，
//! 本模块re-export保持`fms_rgw::sign::NonceStore`公开路径)
#![forbid(unsafe_code)]

use crate::filter;
use crate::headers::{X_FMS_ROLE, X_FMS_USER_ID};
pub use crate::nonce::NonceStore;
use crate::state::AppState;
use crate::types::{BufferedBody, GatewayError, error_response, now_secs, trace_id_of};
use crate::ws::is_websocket_request;
use axum::body::Body;
use axum::extract::State;
use axum::http::header::{CONTENT_LENGTH, CONTENT_TYPE, HeaderName, HeaderValue};
use axum::http::{Request, Response};
use axum::middleware::Next;
use http_body_util::{BodyExt, Limited};

use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use thiserror::Error;
use uuid::Uuid;

/// hmac-sha256别名
type HmacSha256 = Hmac<Sha256>;

/// 签名校验错误
#[derive(Debug, Clone, Copy, PartialEq, Eq, Error)]
#[allow(dead_code)] // 供后端侧对称校验(verify_signature_header)与测试使用
pub enum SignError {
    /// 请求头中无`X-FMS-Signature`，后端侧校验场景
    #[error("缺少 X-FMS-Signature")]
    Missing,
    /// 非t=&nonce=&sig= 三字段 / 字段非法
    #[error("签名头格式非法")]
    Malformed,
    /// |now - t| > `DEFAULT_REPLAY_WINDOW_SECS`，300s，防重放
    #[error("签名时间戳超窗")]
    Expired,
    /// hmac重算不匹配，恒定时间比较
    #[error("签名不匹配")]
    InvalidSignature,
    /// nonce已见过，重放
    #[error("nonce 重放")]
    DuplicateNonce,
}

/// `body_hash` = hex(sha256(body))，小写64位十六进制，空body即空串哈希(e3b0c442...)
pub fn compute_body_hash(body: &[u8]) -> String {
    hex::encode(Sha256::digest(body))
}

/// 重算签名，input = method|path|`body_hash`|t|nonce|uid|role，身份头纳入hmac输入
/// 一跳内换头即签名不匹配，匿名/api-key请求uid/role传空串。
/// 这是`X-FMS-Signature`协议的核心原语，网关`mw_sign`用它生成签名、后端
/// `GatewaySignatureFilter`与`verify_signature_header`用它重算比对，两端必须逐字一致。
/// hmac-sha256密钥即`sign_secret`，与jwt密钥相互独立，'|'，0x7C作字段分隔符，
/// 输入中不含该字符，method/path/hex/t的十进制/uuid均无 '|'，故无需转义。
///
/// 字段含义，method为大写http方法，path为原始请求路径(含`/api`前缀，query不参与)，
/// `body_hash`为`compute_body_hash`输出，t为发起方unix秒，nonce为本次请求唯一随机串
/// 防重放，uuid v7文本，uid/role为网关注入的身份头值，auth层验签后注入，
/// 参与hmac输入后，网关到后端一跳内被替换身份头即验签失败，杜绝身份劫持/提权。
///
/// 结果恒为64位小写hex，hmac-sha256输出32字节，相同输入必然产出相同
/// 签名，确定性，因此时间戳必须随请求携带，由时间窗校验防重放。
#[allow(clippy::too_many_arguments)]
pub fn compute_signature(
    secret: &[u8],
    method: &str,
    path: &str,
    body_hash: &str,
    t: i64,
    nonce: &str,
    uid: &str,
    role: &str,
) -> String {
    let input = format!("{method}|{path}|{body_hash}|{t}|{nonce}|{uid}|{role}");
    // KeyInit与Mac均提供new_from_slice，需完全限定消歧
    let mut mac = <HmacSha256 as hmac::digest::KeyInit>::new_from_slice(secret)
        .expect("HMAC 可接受任意长度密钥(算法不变量)");
    mac.update(input.as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

/// 生成完整头值，format!("t={t}&nonce={nonce}&sig={sig}")，t = 当前unix秒，nonce = uuid v7
pub fn build_signature_header(
    secret: &[u8],
    method: &str,
    path: &str,
    body_hash: &str,
    uid: &str,
    role: &str,
) -> String {
    let t = now_secs();
    let nonce = Uuid::now_v7().to_string();
    let sig = compute_signature(secret, method, path, body_hash, t, &nonce, uid, role);
    format!("t={t}&nonce={nonce}&sig={sig}")
}

/// 校验`X-FMS-Signature`，后端侧同构实现，解析后校验时间窗 |now - t| ≤ 300s，随后重算 sig
/// 恒定时间比较，nonce去重由调用方经`NonceStore`执行。
/// 校验顺序即失败优先级，格式非法(`Malformed`) 优先，再时间超窗(`Expired`)，随后签名不匹配
/// (`InvalidSignature`)，与后端`GatewaySignatureFilter`的10步校验链语义一致。
///
/// 失败语义，`Missing`由调用方在无头时自行构造，本函数仅在`header_value`为空时按
/// `Malformed`处理，时间窗用`abs_diff`双向覆盖，t在未来一侧同样拒绝，防提前重放，
/// 边界 |now - t| == 300放行、301拒绝，恒定时间比较防时序侧信道。
///
/// secret `sign_secret`(与`compute_signature`同一密钥，否则恒`InvalidSignature`)
/// method 请求方法，大写，篡改即签名不匹配
/// path 原始路径(含`/api`前缀，不含query)
/// body_hash 请求体哈希(`compute_body_hash`输出，body被篡改即失败)
/// header_value `X-FMS-Signature`头值，t=&nonce=&sig= 三段，'&' 分隔
/// now 校验方当前unix秒，时钟回拨场景由调用方决定取值
/// uid/role 校验方采信的身份，一跳内与签名时的身份头比对
#[allow(dead_code)] // 后端侧同构校验入口，供下游/测试对称验证
#[allow(clippy::too_many_arguments)] // 校验输入与compute_signature对称
pub fn verify_signature_header(
    secret: &[u8],
    method: &str,
    path: &str,
    body_hash: &str,
    header_value: &str,
    now: i64,
    uid: &str,
    role: &str,
) -> Result<(), SignError> {
    // 解析t / nonce / sig三字段
    let mut t: Option<i64> = None;
    let mut nonce: Option<&str> = None;
    let mut sig: Option<&str> = None;
    for pair in header_value.split('&') {
        let (k, v) = pair.split_once('=').ok_or(SignError::Malformed)?;
        match k {
            "t" => {
                t = Some(v.parse::<i64>().map_err(|_| SignError::Malformed)?);
            }
            "nonce" => {
                if v.is_empty() {
                    return Err(SignError::Malformed);
                }
                nonce = Some(v);
            }
            "sig" => {
                if v.is_empty() {
                    return Err(SignError::Malformed);
                }
                sig = Some(v);
            }
            _ => return Err(SignError::Malformed),
        }
    }
    let t = t.ok_or(SignError::Malformed)?;
    let nonce = nonce.ok_or(SignError::Malformed)?;
    let sig = sig.ok_or(SignError::Malformed)?;

    // 时间窗，|now - t| ≤ 300(abs_diff覆盖t在未来一侧，saturating_sub会使未来t差值饱和为0漏放行)
    if t.abs_diff(now) > DEFAULT_REPLAY_WINDOW_SECS as u64 {
        return Err(SignError::Expired);
    }

    // 恒定时间比较，hex定长，XOR累积
    let expected = compute_signature(secret, method, path, body_hash, t, nonce, uid, role);
    if ct_eq(expected.as_bytes(), sig.as_bytes()) {
        Ok(())
    } else {
        Err(SignError::InvalidSignature)
    }
}

/// 恒定时间比较，长度不等直接失败，长度相等时逐字节XOR累积
#[allow(dead_code)] // 恒定时间比较，verify_signature_header内部使用
fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

/// 防重放时间窗常量300s，协议级共享值，无配置项可覆盖
///
/// 后端同构校验与网关生成侧必须使用同一窗口
#[allow(dead_code)]
const DEFAULT_REPLAY_WINDOW_SECS: i64 = 300;

const X_FMS_SIGNATURE: HeaderName = HeaderName::from_static("x-fms-signature");

/// 中间件，安全链第四层sign，ws升级握手跳过(判定与`ws.rs`同构，缺双头判定会给任意
/// `/api`/** POST留绕过签名注入的口子)，缓冲body(≤`server.max_body_bytes` + utf-8)写
/// extensions 后计算 `body_hash` 并注入 `X-FMS-Signature`(身份段取auth已注入的身份头，
/// 匿名/api-key为空串)。
///
/// 执行序，安全链最内层，位于auth之后，链序保证auth先完成验签与身份注入，
/// 本层签名时身份头已是可信值，若auth被绕过，如api-key模式，身份段为空串，
/// 后端在api-key校验通过前不会采信任何身份，签名仍闭环。
///
/// 行为/边界，`Content-Length`声明超限或实际缓冲超上限则 413(先查声明不等body，
/// 再`Limited`收集兜底实际超限)，body非utf-8则 400，文本型`Content-Type`命中
/// sql注入浅特征则 400，ws升级请求完全跳过(不缓冲、不签名、不注入特征检查，
/// 其body本就不存在)，签名注入失败，头值构造失败，理论不可达静默跳过，
/// 后端会以`Missing`拒绝，fail-closed语义由后端兜底。
///
/// `state`，全局状态(取`sign.sign_secret`，t/nonce由`build_signature_header`生成)
pub async fn mw_sign(
    State(state): State<Arc<AppState>>,
    mut req: Request<Body>,
    next: Next,
) -> Response<Body> {
    let trace_id = trace_id_of(&req);

    // ws握手跳过，无body不签名，判定与ws_handler完全一致，双头 + 路径精确匹配，
    //    防跳过签名却按普通http转发
    if is_websocket_request(req.headers(), req.uri().path()) {
        return next.run(req).await;
    }

    // Content-Length声明超限则 413(不等body，上限取配置server.max_body_bytes)
    let max_body_bytes = state.cfg.server.max_body_bytes;
    let declared = req
        .headers()
        .get(CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok());
    if filter::check_body_size(declared, 0, max_body_bytes).is_err() {
        tracing::warn!(trace_id = %trace_id, "签名中间件：Content-Length 声明超限");
        return error_response(GatewayError::PayloadTooLarge, &trace_id);
    }

    // Limited收集，超限则 413，utf-8校验(失败则 400，仅文本型Content-Type)
    let body = std::mem::take(req.body_mut());
    let limited = Limited::new(body, max_body_bytes + 1);
    let collected = match limited.collect().await {
        Ok(c) => c.to_bytes(),
        Err(_) => {
            tracing::warn!(trace_id = %trace_id, "签名中间件：请求体实际超限");
            return error_response(GatewayError::PayloadTooLarge, &trace_id);
        }
    };
    // Content-Type判定(multipart/octet-stream为二进制文件上传，字节流非utf-8
    // 属正常，utf-8校验与sql注入浅特征均只对文本型body生效)
    let content_type = req
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|v| v.to_str().ok());
    let is_text_body = content_type
        .map(|ct| {
            let mime = ct.split(';').next().unwrap_or("").trim().to_ascii_lowercase();
            !mime.contains("multipart/") && mime != "application/octet-stream"
        })
        .unwrap_or(true);
    if is_text_body && filter::check_utf8(&collected).is_err() {
        tracing::warn!(trace_id = %trace_id, "签名中间件：请求体非 UTF-8");
        return error_response(
            GatewayError::BadRequest("body is not utf-8".to_string()),
            &trace_id,
        );
    }
    if filter::check_body_size(None, collected.len(), max_body_bytes).is_err() {
        return error_response(GatewayError::PayloadTooLarge, &trace_id);
    }

    // sql注入浅特征，请求体，接入点，body缓冲完成处(仅文本型Content-Type)
    if filter::check_sql_injection_body(&collected, content_type).is_err() {
        tracing::warn!(trace_id = %trace_id, "签名中间件：请求体命中 SQL 注入特征");
        return error_response(GatewayError::BadRequest("body rejected".to_string()), &trace_id);
    }

    // BufferedBody写入extensions，proxy直接复用
    req.extensions_mut().insert(BufferedBody(collected.clone()));

    // body_hash + X-FMS-Signature注入
    let body_hash = compute_body_hash(&collected);
    let method = req.method().as_str().to_ascii_uppercase();
    let path = req.uri().path().to_string();
    // 身份段，取auth已注入的身份头，链序保证auth先执行，匿名/api-key请求为空串
    let uid = req
        .headers()
        .get(X_FMS_USER_ID)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let role = req
        .headers()
        .get(X_FMS_ROLE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let sig_header = build_signature_header(
        state.cfg.sign.sign_secret.as_bytes(),
        &method,
        &path,
        &body_hash,
        &uid,
        &role,
    );
    if let Ok(hv) = HeaderValue::from_str(&sig_header) {
        req.headers_mut().insert(X_FMS_SIGNATURE, hv);
    }
    next.run(req).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::test_state;
    use tower::ServiceExt;

    #[test]
    fn body_hash_known_vector() {
        // sha256，"" 的标准值
        assert_eq!(
            compute_body_hash(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            compute_body_hash(b"hello"),
            hex::encode(Sha256::digest(b"hello"))
        );
    }

    #[test]
    fn signature_is_deterministic_and_header_roundtrip() {
        let secret = b"sign-secret";
        let body_hash = compute_body_hash(b"{\"a\":1}");
        let t = now_secs() - 100;
        let nonce = "8f3a4b5c-1111-2222-3333-444455556666";
        let uid = "3f9a2c1d-0000-4000-8000-000000000001";
        let role = "operator";
        let sig = compute_signature(
            secret,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            t,
            nonce,
            uid,
            role,
        );
        assert_eq!(sig.len(), 64);
        assert_eq!(
            compute_signature(
                secret,
                "POST",
                "/api/gl/vouchers",
                &body_hash,
                t,
                nonce,
                uid,
                role
            ),
            sig
        );
        // 篡改任一输入均改变签名
        assert_ne!(
            compute_signature(
                secret,
                "GET",
                "/api/gl/vouchers",
                &body_hash,
                t,
                nonce,
                uid,
                role
            ),
            sig
        );
        assert_ne!(
            compute_signature(
                secret,
                "POST",
                "/api/gl/vouchers",
                &body_hash,
                t + 1,
                nonce,
                uid,
                role
            ),
            sig
        );
        // 完整头值roundtrip校验通过
        let header =
            build_signature_header(secret, "POST", "/api/gl/vouchers", &body_hash, uid, role);
        assert!(header.starts_with("t=") && header.contains("&nonce=") && header.contains("&sig="));
        assert!(
            verify_signature_header(
                secret,
                "POST",
                "/api/gl/vouchers",
                &body_hash,
                &header,
                now_secs(),
                uid,
                role
            )
            .is_ok()
        );
    }

    #[test]
    fn verify_rejects_tamper_and_out_of_window() {
        let secret = b"sign-secret";
        let body_hash = compute_body_hash(b"payload");
        let t = now_secs() - 100;
        let nonce = "8f3a4b5c-0000-0000-0000-000000000001";
        let uid = "3f9a2c1d-0000-4000-8000-000000000001";
        let role = "operator";
        let sig = compute_signature(secret, "POST", "/api/x", &body_hash, t, nonce, uid, role);
        let header = format!("t={t}&nonce={nonce}&sig={sig}");
        // 正确则返回 Ok
        assert!(
            verify_signature_header(
                secret,
                "POST",
                "/api/x",
                &body_hash,
                &header,
                now_secs(),
                uid,
                role
            )
            .is_ok()
        );
        // 篡改sig则 InvalidSignature
        // 确定性篡改，翻转首字符0↔1(末位拼接法在真实签名末位恰为 'a' 时拼接结果相同，
        // 断言Err会以 ~1/16概率偶发失败)
        let first = sig.as_bytes()[0];
        let repl = if first == b'0' { b'1' } else { b'0' };
        let mut bad_sig = sig.clone();
        bad_sig.replace_range(0..1, &(repl as char).to_string());
        assert_ne!(bad_sig, sig, "篡改后的 sig 必须与原签名不同");
        let bad_header = format!("t={t}&nonce={nonce}&sig={bad_sig}");
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/api/x",
                &body_hash,
                &bad_header,
                now_secs(),
                uid,
                role
            ),
            Err(SignError::InvalidSignature)
        );
        // 篡改body_hash则 InvalidSignature(body_hash参与hmac输入)
        let other_hash = compute_body_hash(b"other");
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/api/x",
                &other_hash,
                &header,
                now_secs(),
                uid,
                role
            ),
            Err(SignError::InvalidSignature)
        );
        // 超窗则 Expired，window 300s，t在400s前
        let old_t = now_secs() - 400;
        let old_sig = compute_signature(
            secret, "POST", "/api/x", &body_hash, old_t, nonce, uid, role,
        );
        let old_header = format!("t={old_t}&nonce={nonce}&sig={old_sig}");
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/api/x",
                &body_hash,
                &old_header,
                now_secs(),
                uid,
                role
            ),
            Err(SignError::Expired)
        );
    }

    /// 身份头已纳入hmac输入，一跳内替换身份头，换uid或role必须签名不匹配
    #[test]
    fn verify_rejects_identity_header_tamper() {
        let secret = b"sign-secret";
        let body_hash = compute_body_hash(b"payload");
        let t = now_secs() - 100;
        let nonce = "8f3a4b5c-0000-0000-0000-000000000002";
        let uid = "3f9a2c1d-0000-4000-8000-000000000001";
        let role = "operator";
        let sig = compute_signature(secret, "POST", "/api/x", &body_hash, t, nonce, uid, role);
        let header = format!("t={t}&nonce={nonce}&sig={sig}");
        // 原身份则 Ok
        assert!(
            verify_signature_header(
                secret,
                "POST",
                "/api/x",
                &body_hash,
                &header,
                now_secs(),
                uid,
                role
            )
            .is_ok()
        );
        // 换uid则 InvalidSignature，劫持他人身份
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/api/x",
                &body_hash,
                &header,
                now_secs(),
                "00000000-0000-4000-8000-000000000099",
                role
            ),
            Err(SignError::InvalidSignature),
            "替换 X-FMS-UserId 必须导致签名不匹配"
        );
        // 换role则 InvalidSignature，提权
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/api/x",
                &body_hash,
                &header,
                now_secs(),
                uid,
                "admin"
            ),
            Err(SignError::InvalidSignature),
            "替换 X-FMS-Role 必须导致签名不匹配"
        );
        // 匿名，空身份与已签名身份互不通用
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/api/x",
                &body_hash,
                &header,
                now_secs(),
                "",
                ""
            ),
            Err(SignError::InvalidSignature),
            "空身份不能通过带身份签名"
        );
    }

    #[test]
    fn verify_rejects_malformed() {
        assert!(matches!(
            verify_signature_header(b"k", "GET", "/", "", "", 0, "", ""),
            Err(SignError::Malformed) | Err(SignError::Missing)
        ));
        assert!(matches!(
            verify_signature_header(b"k", "GET", "/", "", "t=abc&nonce=&sig=", 0, "", ""),
            Err(SignError::Malformed)
        ));
    }

    #[test]
    fn signature_input_format_pinned() {
        // 契约固定input = method|path|body_hash|t|nonce|uid|role('|' 分隔无转义，
        // uid/role为网关注入的身份头值，匿名/api-key为空串)
        let secret = b"key";
        let method = "POST";
        let path = "/api/gl/vouchers";
        let body_hash = "6f1e8a2b";
        let t = 1_750_000_000_i64;
        let nonce = "8f3a4b5c-0000-0000-0000-0000000000ab";
        let uid = "3f9a2c1d-0000-4000-8000-000000000001";
        let role = "operator";
        let sig = compute_signature(secret, method, path, body_hash, t, nonce, uid, role);
        // 用hmac crate对文档化输入字符串独立重算，锁定input格式，防未来重构改格式
        let input = format!("{method}|{path}|{body_hash}|{t}|{nonce}|{uid}|{role}");
        let mut mac = <HmacSha256 as hmac::digest::KeyInit>::new_from_slice(secret).unwrap();
        mac.update(input.as_bytes());
        assert_eq!(sig, hex::encode(mac.finalize().into_bytes()));
        assert_eq!(sig.len(), 64, "sig 必须为 64 位小写 hex");
    }

    #[test]
    fn verify_window_boundaries_both_sides() {
        let secret = b"sign-secret";
        let body_hash = compute_body_hash(b"payload");
        let nonce = "8f3a4b5c-0000-0000-0000-0000000000cd";
        let now = now_secs();
        // |now - t| == 300 则放行，301 则拒绝
        for delta in [300_i64, -300] {
            let t = now + delta;
            let sig = compute_signature(secret, "POST", "/api/x", &body_hash, t, nonce, "", "");
            let header = format!("t={t}&nonce={nonce}&sig={sig}");
            assert!(
                verify_signature_header(secret, "POST", "/api/x", &body_hash, &header, now, "", "")
                    .is_ok(),
                "t = now {delta:+} 秒(恰好 300s 窗边)应放行"
            );
        }
        for delta in [301_i64, -301] {
            let t = now + delta;
            let sig = compute_signature(secret, "POST", "/api/x", &body_hash, t, nonce, "", "");
            let header = format!("t={t}&nonce={nonce}&sig={sig}");
            assert_eq!(
                verify_signature_header(secret, "POST", "/api/x", &body_hash, &header, now, "", ""),
                Err(SignError::Expired),
                "t = now {delta:+} 秒应拒绝(含未来时间戳方向)"
            );
        }
    }

    #[test]
    fn verify_malformed_field_variants() {
        let secret = b"k";
        let now = now_secs();
        let t = now - 10;
        let body_hash = compute_body_hash(b"b");
        let nonce = "8f3a4b5c-0000-0000-0000-0000000000ef";
        let sig = compute_signature(secret, "POST", "/p", &body_hash, t, nonce, "", "");
        // 缺字段 / 未知字段 / 非数字t / 空头值
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/p",
                &body_hash,
                &format!("nonce={nonce}&sig={sig}"),
                now,
                "",
                ""
            ),
            Err(SignError::Malformed)
        );
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/p",
                &body_hash,
                &format!("t={t}&nonce={nonce}&sig={sig}&extra=1"),
                now,
                "",
                ""
            ),
            Err(SignError::Malformed),
            "未知字段必须拒绝"
        );
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/p",
                &body_hash,
                &format!("t=abc&nonce={nonce}&sig={sig}"),
                now,
                "",
                ""
            ),
            Err(SignError::Malformed)
        );
        assert_eq!(
            verify_signature_header(secret, "POST", "/p", &body_hash, "", now, "", ""),
            Err(SignError::Malformed),
            "空头值必须拒绝"
        );
        // 非hex sig，长度64但非法字符则恒定时间比较失败，回 InvalidSignature
        let bad_sig = "g".repeat(64);
        assert_eq!(
            verify_signature_header(
                secret,
                "POST",
                "/p",
                &body_hash,
                &format!("t={t}&nonce={nonce}&sig={bad_sig}"),
                now,
                "",
                ""
            ),
            Err(SignError::InvalidSignature)
        );
    }

    async fn echo_middleware_view(req: axum::http::Request<Body>) -> axum::http::Response<Body> {
        // 回显，X-FMS-Signature是否存在、BufferedBody是否存在及内容
        let sig = req
            .headers()
            .get(X_FMS_SIGNATURE)
            .map(|v| v.to_str().unwrap_or("").to_string())
            .unwrap_or_default();
        let buffered = req
            .extensions()
            .get::<BufferedBody>()
            .map(|b| String::from_utf8_lossy(&b.0).into_owned())
            .unwrap_or_default();
        let body = format!("sig={sig}|buffered={buffered}");
        axum::http::Response::builder()
            .status(200)
            .body(Body::from(body))
            .unwrap()
    }

    fn sign_router(path: &str) -> axum::Router {
        let state = test_state();
        axum::Router::new()
            .route(path, axum::routing::any(echo_middleware_view))
            .route_layer(axum::middleware::from_fn_with_state(state.clone(), mw_sign))
            .with_state(state)
    }

    #[tokio::test]
    async fn mw_sign_skips_ws_handshake_only_on_ws_path() {
        // ws升级头 + /api/nt/ws 则跳过，不签名、不缓冲
        let app = sign_router("/api/nt/ws");
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/nt/ws")
            .header("upgrade", "websocket")
            .header("connection", "upgrade")
            .body(Body::from("frame-bytes"))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        assert!(
            text.starts_with("sig=|buffered="),
            "WS 握手必须跳过签名与缓冲，实际 {text}"
        );
        // 同一ws头但路径非/api/nt/ws则不得跳过，防伪造签名绕过
        let app2 = sign_router("/api/other");
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/other")
            .header("upgrade", "websocket")
            .header("connection", "upgrade")
            .body(Body::from("x"))
            .unwrap();
        let resp = app2.clone().oneshot(req).await.unwrap();
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        assert!(
            text.starts_with("sig=t="),
            "非 WS 路径带 Upgrade 头必须照常签名，实际 {text}"
        );
    }

    #[tokio::test]
    async fn mw_sign_injects_verifiable_signature_and_buffered_body() {
        let app = sign_router("/api/items");
        let payload = "{\"a\":1}";
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .header("content-type", "application/json")
            .body(Body::from(payload))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        let (sig, buffered) = text.split_once("|buffered=").expect("回显格式");
        assert_eq!(buffered, payload, "BufferedBody 内容应与请求体一致");
        let sig = sig.strip_prefix("sig=").expect("签名头前缀");
        // 注入的签名必须能通过后端同构校验，t/nonce/sig三字段 + 时间窗 + hmac，
        // 本测试路由无auth注入身份则身份段为空串
        let body_hash = compute_body_hash(payload.as_bytes());
        assert!(
            verify_signature_header(
                "test-sign-secret".as_bytes(),
                "POST",
                "/api/items",
                &body_hash,
                sig,
                now_secs(),
                "",
                "",
            )
            .is_ok(),
            "mw_sign 注入的签名必须可验证(身份段空)"
        );
    }

    /// 网关侧，`mw_sign`必须把auth注入的身份头纳入hmac输入，用该身份可验、换身份即失效
    #[tokio::test]
    async fn mw_sign_covers_injected_identity_headers() {
        let app = sign_router("/api/items");
        let payload = "{\"a\":1}";
        let uid = "3f9a2c1d-0000-4000-8000-000000000001";
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .header("content-type", "application/json")
            .header("x-fms-userid", uid)
            .header("x-fms-role", "operator")
            .body(Body::from(payload))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        let text = String::from_utf8(body.to_vec()).unwrap();
        let sig = text
            .split_once("|buffered=")
            .expect("回显格式")
            .0
            .strip_prefix("sig=")
            .expect("签名头前缀");
        let body_hash = compute_body_hash(payload.as_bytes());
        // 注入时的身份，uid + operator则可验
        assert!(
            verify_signature_header(
                "test-sign-secret".as_bytes(),
                "POST",
                "/api/items",
                &body_hash,
                sig,
                now_secs(),
                uid,
                "operator",
            )
            .is_ok(),
            "身份段必须参与签名(原身份可验)"
        );
        // 换身份，他人uid / admin role / 空身份则签名失效
        for (bad_uid, bad_role) in [
            ("00000000-0000-4000-8000-000000000099", "operator"),
            (uid, "admin"),
            ("", ""),
        ] {
            assert_eq!(
                verify_signature_header(
                    "test-sign-secret".as_bytes(),
                    "POST",
                    "/api/items",
                    &body_hash,
                    sig,
                    now_secs(),
                    bad_uid,
                    bad_role,
                ),
                Err(SignError::InvalidSignature),
                "身份段被替换(uid={bad_uid}, role={bad_role})必须签名失效"
            );
        }
    }

    #[tokio::test]
    async fn mw_sign_rejects_oversize_and_bad_body() {
        // 声明Content-Length超限则 413
        let app = sign_router("/api/items");
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .header(
                "content-length",
                (filter::MAX_BODY_BYTES as u64 + 1).to_string(),
            )
            .body(Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::PAYLOAD_TOO_LARGE);
        // 实际body超限(无Content-Length声明)则 413
        let big = vec![b'a'; filter::MAX_BODY_BYTES + 1];
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .body(Body::from(big))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::PAYLOAD_TOO_LARGE);
        // 非utf-8 body，无CT = 视为文本型则 400
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .body(Body::from(vec![0xffu8, 0xfe]))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::BAD_REQUEST);
        // multipart二进制文件，非utf-8字节流则放行，上传场景，utf-8校验跳过
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .header(axum::http::header::CONTENT_TYPE, "multipart/form-data; boundary=----x")
            .body(Body::from(vec![0xffu8, 0xfe, b'\r', b'\n', 0x89, 0x50]))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::OK,
            "multipart 二进制 body 应放行(上传文件非 UTF-8 属正常)"
        );
        // octet-stream二进制同样放行
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .header(axum::http::header::CONTENT_TYPE, "application/octet-stream")
            .body(Body::from(vec![0x89u8, 0x50, 0x4e, 0x47]))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK, "octet-stream 二进制放行");
        // 文本型body命中sql特征则 400
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/items")
            .header("content-type", "application/x-www-form-urlencoded")
            .body(Body::from("name=admin';drop table t"))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::BAD_REQUEST);
    }
}
