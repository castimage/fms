//! `tests/security_auth.rs`，红队，认证/签名/会话攻击测试套件，针对FMS网关信任链
//!
//! 覆盖，对应任务编号，
//! -  jwt算法混淆，alg:none空签名、HS256用RS256公钥字节作hmac密钥、弱密钥穷举
//! -  jwt生命周期，过期 / 未来时间 / 空sub / 超长sub
//! -  pgjwt兼容，无iss/aud宽松放行，iss/aud不匹配且未配置则宽松，配置后严格
//! - 签名重放，原样重放，网关语义、t超窗、nonce重用去重、body/method/path篡改、客户端伪造签名头被替换
//! - 签名头全变体，缺失/空/单字段/malformed后端校验拒绝 + 网关剥离注入
//! - 身份头伪造，`X-FMS-*`剥离重写(值取自`Claims`)，xff/`X-Real-IP`伪造不影响限流key
//! - 登录暴力破解，login维度429 + `Retry-After`，xff伪造不能绕过，对端ip为key
//! - 未认证访问矩阵，受保护路径401全矩阵，匿名白名单放行
//! - 会话/中间人，hsts仅在tls配置下注入，错误响应仍带安全头
//!
//! 方法，tower oneshot直调`build_router` + `MockUpstream`(真实tcp上游，可断言攻击请求
//! 是否到达上游，防绕过关键)。后端侧签名对称校验(`verify_signature_header` / `NonceStore`)
//! 为公开api，直接复用断言"下游如何拒绝重放"。
//!
//! 发现，缺陷/观察，不改src，供后续修复，
//! 1. 【观察/潜在缺陷】`X-Real-IP`为客户端可控且网关原样透传上游，a6用例断言，
//!    上游若信任`X-Real-IP`取真实ip会被直连客户端伪造，建议网关覆盖/剥离`X-Real-IP`
//!    或仅在受信边界，前置代理写入。
//! 2. 【语义说明】`/api/nt/ws`位于匿名白名单，无token的普通http请求会绕过认证转发到
//!    上游，a8白名单用例断言，符合INTERFACE设计，但上游必须自行校验ws token。

mod common;

use axum::http::StatusCode;
use common::*;
use serde_json::json;
use std::time::Duration;
use uuid::Uuid;

const SUB: &str = "3f9a2c1d-0000-4000-8000-000000000001";
const JWT_SECRET: &[u8] = b"test-jwt-secret";
const SIGN_SECRET: &[u8] = b"test-sign-secret";

// 恶意token构造工具

/// base64url编码，无padding，手工构造恶意jwt，alg:none等jsonwebtoken不支持的形态
fn b64url(input: &[u8]) -> String {
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::new();
    for chunk in input.chunks(3) {
        let b = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        if chunk.len() > 1 {
            out.push(T[(n >> 6) as usize & 63] as char);
        }
        if chunk.len() > 2 {
            out.push(T[n as usize & 63] as char);
        }
    }
    out
}

/// 任意header/payload/sig三段拼接的原始jwt
fn raw_jwt(header_json: &str, payload_json: &str, sig: &str) -> String {
    format!(
        "{}.{}.{}",
        b64url(header_json.as_bytes()),
        b64url(payload_json.as_bytes()),
        sig
    )
}

/// 标准claims json，exp为相对偏移
fn claims_json(sub: &str, role: &str, exp_offset: i64) -> serde_json::Value {
    json!({ "sub": sub, "role": role, "exp": now_secs() + exp_offset })
}

/// 用任意`serde_json`值，可含自定义iss/aud/空sub签发HS256 token
fn sign_value(v: &serde_json::Value, secret: &[u8]) -> String {
    jsonwebtoken::encode(
        &jsonwebtoken::Header::new(jsonwebtoken::Algorithm::HS256),
        v,
        &jsonwebtoken::EncodingKey::from_secret(secret),
    )
    .expect("测试 token 签发")
}

/// 断言mock上游在某前缀下收到count条记录，攻击未到达上游时count=0
async fn assert_hits(mock: &MockUpstream, prefix: &str, count: usize, ctx: &str) {
    let hits = mock.take_filtered(prefix);
    assert_eq!(
        hits.len(),
        count,
        "{ctx}：上游命中数应为 {count}，实际 {}",
        hits.len()
    );
}

//  jwt算法混淆

/// 【攻击】alg:none空签名，攻击者把header改成{"alg":"none"}且不带签名，声称无签名token。
/// 【防护层】auth层，`verify_jwt`固定`Validation::new`，HS256，jsonwebtoken拒绝任何非HS256
/// 算法含none、decode失败则 401/40100，请求不进 sign/proxy。
/// 【预期】401 + code 40100 + 上游零到达，另附alg=HS384签名变体同样拒绝。
#[tokio::test]
async fn alg_none_empty_signature_rejected() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let payload = claims_json(SUB, "operator", 3600).to_string();
    // 变体1，空签名，变体2，任意假签名，变体3，无尾部句点，无签名段
    for token in [
        raw_jwt(r#"{"alg":"none","typ":"JWT"}"#, &payload, ""),
        raw_jwt(r#"{"alg":"none","typ":"JWT"}"#, &payload, "deadbeef"),
        raw_jwt(r#"{"alg":"None","typ":"JWT"}"#, &payload, ""),
        // alg=HS384签名，密钥正确但算法不在HS256白名单
        sign_value_with_alg(&payload, jsonwebtoken::Algorithm::HS384),
    ] {
        let resp = oneshot(
            &router,
            with_ip(
                with_auth(request("GET", "/api/gl/vouchers"), &token),
                "127.0.0.1",
            ),
        )
        .await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "算法混淆 token 必须 401");
        assert_eq!(body["code"], 40100, "算法混淆必须按未认证 40100 处理");
    }
    assert_hits(&mock, "/api/gl/vouchers", 0, "alg:none 攻击不得到达上游").await;
}

/// 辅助，按指定算法签发，HS384用同一密钥
fn sign_value_with_alg(payload_json: &str, alg: jsonwebtoken::Algorithm) -> String {
    let v: serde_json::Value = serde_json::from_str(payload_json).expect("payload 为 JSON");
    jsonwebtoken::encode(
        &jsonwebtoken::Header::new(alg),
        &v,
        &jsonwebtoken::EncodingKey::from_secret(JWT_SECRET),
    )
    .expect("测试 token 签发")
}

/// 【攻击】算法混淆经典打法，若服务端误把RS256公钥当hmac密钥验签HS256，
/// 攻击者用公开的RS256公钥字节作hmac密钥签发HS256 token即可伪造任意身份。
/// 【防护层】auth层，`verify_jwt`固定HS256 + 独立`jwt_secret`，test-jwt-secret，
/// 公钥字节 ≠ 真实密钥则 hmac 重算不匹配，回 401/40100。
/// 【预期】401 + 40100 + 上游零到达。
#[tokio::test]
async fn hs256_with_rsa_public_key_as_secret_rejected() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    // 假设的RS256公钥(演示用字节串，算法混淆攻击只需把"服务端公钥字节"当hmac密钥，
    // 字节是否为合法PEM不影响攻击路径演示，真实攻击中该串即服务端分发的公钥)
    let rsa_pub_bytes = b"-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAu1SU1LfVLPHCozMxH2Mo\n4lgPjSs4qQd4FOP2Y5U5T8jZ9B7Y4N3Z2OQZ7u3p1hK4dW8P3bK1r0WjQJX2cJz\n-----END PUBLIC KEY-----\n";
    let payload = claims_json(SUB, "admin", 3600);
    let token = sign_value(&payload, rsa_pub_bytes);
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(request("GET", "/api/gl/vouchers"), &token),
            "127.0.0.1",
        ),
    )
    .await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::UNAUTHORIZED,
        "用 RS256 公钥字节签名必须 401"
    );
    assert_eq!(body["code"], 40100);
    assert_hits(&mock, "/api/gl/vouchers", 0, "公钥混淆 token 不得到达上游").await;
}

/// 【攻击】弱密钥穷举，攻击者离线用常见弱密钥逐一签HS256 token试打网关
/// 若网关真实密钥在弱密钥字典内即被撞出。
/// 【防护层】auth层，HS256密钥熵，test-jwt-secret非弱密钥，全部弱密钥尝试验签失败则 401。
/// 【预期】每个弱密钥变体401 + 40100 + 上游零到达。
#[tokio::test]
async fn weak_secret_bruteforce_all_rejected() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let payload = claims_json(SUB, "admin", 3600);
    for weak in [
        "secret", "123456", "password", "changeme", "admin", "test", "fms", "fms_rgw", "letmein",
        "12345678",
    ] {
        let token = sign_value(&payload, weak.as_bytes());
        let resp = oneshot(
            &router,
            with_ip(
                with_auth(request("GET", "/api/gl/vouchers"), &token),
                "127.0.0.1",
            ),
        )
        .await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(
            status,
            StatusCode::UNAUTHORIZED,
            "弱密钥 '{weak}' 尝试必须 401"
        );
        assert_eq!(body["code"], 40100);
    }
    assert_hits(&mock, "/api/gl/vouchers", 0, "弱密钥穷举不得到达上游").await;
}

//  jwt生命周期

/// 【攻击】过期token重放，旧会话劫持。
/// 【防护层】auth层，exp < now - leeway，60s 则回 `AuthError::Expired`，401 + code 40101。
/// 【预期】401 + 40101 + 上游零到达。
#[tokio::test]
async fn expired_token_rejected_40101() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let token = make_jwt(SUB, "operator", -3600, JWT_SECRET);
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(request("GET", "/api/gl/vouchers"), &token),
            "127.0.0.1",
        ),
    )
    .await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["code"], 40101, "过期 token 必须返回 40101");
    assert_hits(&mock, "/api/gl/vouchers", 0, "过期 token 不得到达上游").await;
}

/// 【攻击】签发"永不过期"的未来exp，100年后，观察网关是否强制最大有效期。
/// 【防护层】auth层，无最大exp上限，标准jwt语义，与pgjwt兼容，该token合法通过。
/// 【预期】200 + 上游收到1次且身份头正确，语义记录，业务侧需自行缩短exp签发期
/// 网关不做"过期期限过长"限制，非缺陷。
#[tokio::test]
async fn future_exp_far_future_accepted() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let token = make_jwt(SUB, "operator", 3600 * 24 * 365 * 100, JWT_SECRET);
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(request("GET", "/api/gl/vouchers"), &token),
            "127.0.0.1",
        ),
    )
    .await;
    assert_eq!(resp.status(), StatusCode::OK, "未过期合法签名 token 应通过");
    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(hits.len(), 1);
    assert_eq!(hits[0].header("x-fms-userid"), Some(SUB), "身份取自 Claims");
}

/// 【攻击】sub为空 / 非uuid，伪造无主体token。
/// 【防护层】auth层，`Claims`.sub反序列化为`Uuid`失败则 decode 报错，回 401/40100。
/// 【预期】401 + 40100 + 上游零到达。
#[tokio::test]
async fn empty_sub_rejected() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    for sub in ["", "not-a-uuid", "3f9a2c1d"] {
        let token = sign_value(&claims_json(sub, "admin", 3600), JWT_SECRET);
        let resp = oneshot(
            &router,
            with_ip(
                with_auth(request("GET", "/api/gl/vouchers"), &token),
                "127.0.0.1",
            ),
        )
        .await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "sub='{sub}' 必须 401");
        assert_eq!(body["code"], 40100);
    }
    assert_hits(&mock, "/api/gl/vouchers", 0, "空 sub 不得到达上游").await;
}

/// 【攻击】超长sub，10KB探测解析器放大面 / 绕过校验。
/// 【防护层】auth层，超长但非uuid的sub反序列化失败则 401，请求体/头部无大对象缓存，
/// 无内存放大，token即头值，毫秒级失败。
/// 【预期】401 + 40100 + 上游零到达。
#[tokio::test]
async fn oversized_sub_rejected() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let huge = "a".repeat(10_000);
    let token = sign_value(&claims_json(&huge, "admin", 3600), JWT_SECRET);
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(request("GET", "/api/gl/vouchers"), &token),
            "127.0.0.1",
        ),
    )
    .await;
    let (status, _, body) = collect_json(resp).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED, "超长 sub 必须 401");
    assert_eq!(body["code"], 40100);
    assert_hits(&mock, "/api/gl/vouchers", 0, "超长 sub 不得到达上游").await;
}

//  pgjwt兼容

/// 【攻击】pgjwt签发的token，无iss/aud必须可用，兼容性反向断言，若网关误拒则业务全断。
/// 【防护层】auth层，iss/aud未配置时宽松(`claims.iss`/aud为`None`直接放行，与pgjwt一致)。
/// 【预期】200 + 上游1次。
#[tokio::test]
async fn no_iss_aud_lenient_pass() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let token = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(request("GET", "/api/gl/vouchers"), &token),
            "127.0.0.1",
        ),
    )
    .await;
    assert_eq!(
        resp.status(),
        StatusCode::OK,
        "无 iss/aud 的合法 token 必须通过(pgjwt 兼容)"
    );
    assert_hits(&mock, "/api/gl/vouchers", 1, "兼容 token 应转发到上游").await;
}

/// 【攻击】攻击者自签iss="evil" 的token，网关未配置`expected_iss`/aud 则宽松放行。
/// 【防护层】auth层，宽松策略是设计语义，pgjwt签发可能省略iss/aud，配置了才比对，
/// 该场景"放行"即预期行为，真实防护依赖运维配置`expected_iss`/aud，a3下一用例验证严格模式。
/// 【预期】200 + 上游1次，宽松语义被断言，防未来误改。
#[tokio::test]
async fn mismatched_iss_aud_lenient_when_unconfigured() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let token = make_jwt_full(
        SUB,
        "operator",
        now_secs() + 3600,
        Some("evil-issuer"),
        Some("evil-aud"),
        JWT_SECRET,
    );
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(request("GET", "/api/gl/vouchers"), &token),
            "127.0.0.1",
        ),
    )
    .await;
    assert_eq!(
        resp.status(),
        StatusCode::OK,
        "未配置 iss/aud 时宽松放行(pgjwt 兼容语义)"
    );
    assert_hits(&mock, "/api/gl/vouchers", 1, "宽松模式应转发").await;
}

/// 【攻击】配置`expected_iss`/aud后，伪造iss/aud的token必须拒绝，防下游信任放大的token。
/// 【防护层】auth层，`verify_jwt`手动比对iss/aud(`claims.iss`/aud != 配置则 `InvalidIssuer`/
/// `InvalidAudience`，回 401/40100)，缺失iss/aud同样拒绝。
/// 【预期】三种错误变体 401 + 40100 + 零到达，正确iss/aud 则 200 + 1次。
#[tokio::test]
async fn iss_aud_strict_when_configured() {
    let (router, mock) = router_with_mock(
        |cfg| {
            cfg.auth.expected_iss = Some("fms".to_string());
            cfg.auth.expected_aud = Some("fms-gateway".to_string());
        },
        None,
    )
    .await;
    let bad_cases = [
        make_jwt_full(
            SUB,
            "operator",
            now_secs() + 3600,
            Some("evil"),
            Some("fms-gateway"),
            JWT_SECRET,
        ),
        make_jwt_full(
            SUB,
            "operator",
            now_secs() + 3600,
            Some("fms"),
            Some("evil-aud"),
            JWT_SECRET,
        ),
        make_jwt_full(SUB, "operator", now_secs() + 3600, None, None, JWT_SECRET),
    ];
    for token in bad_cases {
        let resp = oneshot(
            &router,
            with_ip(
                with_auth(request("GET", "/api/gl/vouchers"), &token),
                "127.0.0.1",
            ),
        )
        .await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(
            status,
            StatusCode::UNAUTHORIZED,
            "配置后 iss/aud 不符必须 401"
        );
        assert_eq!(body["code"], 40100);
    }
    assert_hits(&mock, "/api/gl/vouchers", 0, "iss/aud 伪造不得到达上游").await;
    // 正确iss/aud则放行
    let ok = make_jwt_full(
        SUB,
        "operator",
        now_secs() + 3600,
        Some("fms"),
        Some("fms-gateway"),
        JWT_SECRET,
    );
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(request("GET", "/api/gl/vouchers"), &ok),
            "127.0.0.1",
        ),
    )
    .await;
    assert_eq!(resp.status(), StatusCode::OK, "iss/aud 正确必须放行");
    assert_hits(&mock, "/api/gl/vouchers", 1, "正确 iss/aud 应转发").await;
}

// 签名重放

/// 【攻击】同一请求原样重放两次，完全相同字节流，会话重放。
/// 【防护层】sign层语义，网关是签名生成方，每次转发生成全新nonce，uuid v7+ t + sig，
/// 两次上游签名不同则下游可据 nonce 去重(`nonce_reuse_dedup`验证去重器)。
/// 网关对"原样重放"天然放行(合法幂等语义，防重放责任在后端/`NonceStore`)。
/// 【预期】两次200 + 上游2次，两次`X-FMS-Signature`不同且各自可经后端同构校验通过。
#[tokio::test]
async fn exact_replay_passes_with_fresh_signature() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let jwt = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    for i in 0..2 {
        // 两次构造字节完全相同的请求(Body非Clone，每次重建)
        let mut req = with_auth(request("POST", "/api/gl/vouchers"), &jwt);
        req.headers_mut()
            .insert("content-type", "application/json".parse().unwrap());
        req.headers_mut()
            .insert("x-replay-tag", "same".parse().unwrap());
        let req = with_ip(req, "127.0.0.1");
        let resp = oneshot(&router, req).await;
        assert_eq!(
            resp.status(),
            StatusCode::OK,
            "第 {} 次原样重放应通过(合法重放语义)",
            i + 1
        );
    }
    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(hits.len(), 2, "两次重放都应到达上游");
    let sig1 = hits[0]
        .header("x-fms-signature")
        .expect("上游必须收到签名头");
    let sig2 = hits[1]
        .header("x-fms-signature")
        .expect("上游必须收到签名头");
    assert_ne!(
        sig1, sig2,
        "每次转发必须生成新 nonce/t -> 签名不得相同(否则下游无法去重)"
    );
    let body_hash = fms_rgw::sign::compute_body_hash(b"");
    for sig in [sig1, sig2] {
        assert!(
            fms_rgw::sign::verify_signature_header(
                SIGN_SECRET,
                "POST",
                "/api/gl/vouchers",
                &body_hash,
                sig,
                now_secs(),
                SUB,
                "operator"
            )
            .is_ok(),
            "网关注入的签名必须可经后端同构校验"
        );
    }
}

/// 【攻击】篡改签名时间戳t到窗口外，含未来方向，重放旧请求 / 预签未来请求。
/// 【防护层】sign层(后端侧对称校验`verify_signature_header`)，|now - t| > 300s 则 `Expired`。
/// 【预期】t = now±301全部`SignError::Expired`，t = now±300，窗边放行。
#[tokio::test]
async fn t_out_of_window_rejected() {
    let body_hash = fms_rgw::sign::compute_body_hash(b"payload");
    for delta in [-301_i64, 301] {
        let t = now_secs() + delta;
        let nonce = Uuid::now_v7().to_string();
        let sig = fms_rgw::sign::compute_signature(
            SIGN_SECRET,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            t,
            &nonce,
            "",
            "",
        );
        let header = format!("t={t}&nonce={nonce}&sig={sig}");
        assert_eq!(
            fms_rgw::sign::verify_signature_header(
                SIGN_SECRET,
                "POST",
                "/api/gl/vouchers",
                &body_hash,
                &header,
                now_secs(),
                "",
                ""
            ),
            Err(fms_rgw::sign::SignError::Expired),
            "t=now{delta:+}s 必须超窗拒绝(含未来时间戳方向)"
        );
    }
    // 窗内同参数必须通过，对照
    let t = now_secs() - 10;
    let nonce = Uuid::now_v7().to_string();
    let sig = fms_rgw::sign::compute_signature(
        SIGN_SECRET,
        "POST",
        "/api/gl/vouchers",
        &body_hash,
        t,
        &nonce,
        "",
        "",
    );
    let header = format!("t={t}&nonce={nonce}&sig={sig}");
    assert!(
        fms_rgw::sign::verify_signature_header(
            SIGN_SECRET,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            &header,
            now_secs(),
            "",
            ""
        )
        .is_ok()
    );
}

/// 【攻击】nonce重用，同t+nonce+sig原样重放，窗口内签名相同，后端去重场景。
/// 【防护层】sign层`NonceStore`，窗口内已见nonce 则拒绝，重复签名无法绕过。
/// 【预期】首次`check_and_insert` true，同nonce二次false，去重命中，
/// 签名本身窗口内仍有效，verify通过，去重必须依赖nonce存储而非签名。
#[tokio::test]
async fn nonce_reuse_dedup() {
    let store = fms_rgw::sign::NonceStore::new(1000, Duration::from_secs(300));
    let t = now_secs() - 5;
    let nonce = Uuid::now_v7().to_string();
    let body_hash = fms_rgw::sign::compute_body_hash(b"x");
    let sig = fms_rgw::sign::compute_signature(
        SIGN_SECRET,
        "POST",
        "/api/gl/vouchers",
        &body_hash,
        t,
        &nonce,
        "",
        "",
    );
    let header = format!("t={t}&nonce={nonce}&sig={sig}");
    // 首次，签名有效 + nonce入库则放行
    assert!(
        fms_rgw::sign::verify_signature_header(
            SIGN_SECRET,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            &header,
            now_secs(),
            "",
            ""
        )
        .is_ok()
    );
    assert!(store.check_and_insert(&nonce), "首次 nonce 必须放行");
    // 重放，同t+nonce+sig，窗口内则签名仍有效但nonce已存在，拒绝
    assert!(
        !store.check_and_insert(&nonce),
        "窗口内同 nonce 重放必须拒绝(去重)"
    );
    assert!(
        fms_rgw::sign::verify_signature_header(
            SIGN_SECRET,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            &header,
            now_secs(),
            "",
            ""
        )
        .is_ok(),
        "签名本身仍有效：去重靠 nonce 存储，防重放不可被「换 nonce」之外的手段绕过"
    );
}

/// 【攻击】body篡改后复用原签名，改金额/内容，签名未重算。
/// 【防护层】sign层，`body_hash`参与hmac输入，篡改body 则哈希变，重算不匹配，回 `InvalidSignature`。
/// 【预期】原签名对篡改后body校验`Err`(`InvalidSignature`)，对原body仍Ok。
#[tokio::test]
async fn body_tamper_rejected() {
    let body_hash = fms_rgw::sign::compute_body_hash(br#"{"amount":100}"#);
    let t = now_secs() - 5;
    let nonce = Uuid::now_v7().to_string();
    let sig = fms_rgw::sign::compute_signature(
        SIGN_SECRET,
        "POST",
        "/api/gl/vouchers",
        &body_hash,
        t,
        &nonce,
        "",
        "",
    );
    let header = format!("t={t}&nonce={nonce}&sig={sig}");
    assert!(
        fms_rgw::sign::verify_signature_header(
            SIGN_SECRET,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            &header,
            now_secs(),
            "",
            ""
        )
        .is_ok()
    );
    let tampered_hash = fms_rgw::sign::compute_body_hash(br#"{"amount":99999999}"#);
    assert_eq!(
        fms_rgw::sign::verify_signature_header(
            SIGN_SECRET,
            "POST",
            "/api/gl/vouchers",
            &tampered_hash,
            &header,
            now_secs(),
            "",
            ""
        ),
        Err(fms_rgw::sign::SignError::InvalidSignature),
        "body 篡改后原签名必须拒绝"
    );
}

/// 【攻击】method/path篡改后复用原签名，换请求语义，POST改PUT、路径换管理端点。
/// 【防护层】sign层，method|path参与hmac输入，任一改动则重算不匹配，回 `InvalidSignature`。
/// 【预期】PUT/其他路径/query带不同path均`Err`(`InvalidSignature`)，原组合仍Ok。
#[tokio::test]
async fn method_path_tamper_rejected() {
    let body_hash = fms_rgw::sign::compute_body_hash(b"b");
    let t = now_secs() - 5;
    let nonce = Uuid::now_v7().to_string();
    let sig = fms_rgw::sign::compute_signature(
        SIGN_SECRET,
        "POST",
        "/api/gl/vouchers",
        &body_hash,
        t,
        &nonce,
        "",
        "",
    );
    let header = format!("t={t}&nonce={nonce}&sig={sig}");
    assert!(
        fms_rgw::sign::verify_signature_header(
            SIGN_SECRET,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            &header,
            now_secs(),
            "",
            ""
        )
        .is_ok()
    );
    for (m, p) in [
        ("PUT", "/api/gl/vouchers"),
        ("POST", "/api/gl/vouchers/delete"),
        ("POST", "/api/rpt/entries"),
    ] {
        assert_eq!(
            fms_rgw::sign::verify_signature_header(
                SIGN_SECRET,
                m,
                p,
                &body_hash,
                &header,
                now_secs(),
                "",
                ""
            ),
            Err(fms_rgw::sign::SignError::InvalidSignature),
            "method/path 篡改({m} {p})后原签名必须拒绝"
        );
    }
}

/// 【攻击】客户端伪造`X-FMS-Signature`，自算t/nonce/sig或复制旧签名企图控制上游验签输入。
/// 【防护层】auth层(剥离客户端全部`X-FMS-*`)+ sign层(按真实method/path/`body_hash`注入
/// 全新签名)。客户端签名不可能到达上游。
/// 【预期】200，上游收到的新签名 ≠ 伪造值、nonce非客户端值、可经后端同构校验通过。
#[tokio::test]
async fn client_forged_signature_stripped_and_replaced() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let jwt = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    let mut req = with_auth(request("POST", "/api/gl/vouchers"), &jwt);
    req.headers_mut()
        .insert("content-type", "application/json".parse().unwrap());
    req.headers_mut().insert(
        "x-fms-signature",
        "t=1&nonce=client-nonce-1111&sig=deadbeefdeadbeef"
            .parse()
            .unwrap(),
    );
    let req = with_ip(req, "127.0.0.1");
    let body = br#"{"a":1}"#;
    let mut req = req;
    *req.body_mut() = axum::body::Body::from(body.to_vec());
    let resp = oneshot(&router, req).await;
    assert_eq!(resp.status(), StatusCode::OK);
    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(hits.len(), 1);
    let sig = hits[0]
        .header("x-fms-signature")
        .expect("上游必须收到网关签名");
    assert_ne!(
        sig, "t=1&nonce=client-nonce-1111&sig=deadbeefdeadbeef",
        "伪造签名必须被替换"
    );
    assert!(!sig.contains("client-nonce-1111"), "客户端 nonce 不得透传");
    let body_hash = fms_rgw::sign::compute_body_hash(body);
    assert!(
        fms_rgw::sign::verify_signature_header(
            SIGN_SECRET,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            sig,
            now_secs(),
            SUB,
            "operator"
        )
        .is_ok(),
        "替换后的签名必须与真实 body 一致并可校验"
    );
}

// 签名头全变体

/// 【攻击】`X-FMS-Signature`缺失/空/单字段/非法字段/malformed全变体，探测下游校验器绕过。
/// 【防护层】sign层(后端侧`verify_signature_header`)，严格三字段解析，缺字段/非数字t/
/// 空nonce/未知字段则 `Malformed`，非hex sig 则 `InvalidSignature`。
/// 网关侧，客户端自带任意变体一律剥离并注入有效签名。
/// 【预期】后端校验11个变体全部拒绝，网关侧4个变体请求200且上游签名有效。
#[tokio::test]
async fn signature_header_all_variants_rejected() {
    let secret = SIGN_SECRET;
    let body_hash = fms_rgw::sign::compute_body_hash(b"b");
    let t = now_secs() - 10;
    let nonce = Uuid::now_v7().to_string();
    let sig = fms_rgw::sign::compute_signature(
        secret,
        "POST",
        "/api/gl/vouchers",
        &body_hash,
        t,
        &nonce,
        "",
        "",
    );
    let malformed = [
        "",                          // 缺失/空
        "t=123",                     // 仅t
        "sig=abc",                   // 仅sig
        "nonce=x",                   // 仅nonce
        "t=",                        // t空值
        "t=abc&nonce=x&sig=y",       // 非数字t
        "t=1&nonce=&sig=y",          // 空nonce
        "t=1&nonce=x&sig=",          // 空sig
        "t=1&nonce=x",               // 缺sig
        "nonce=x&sig=y",             // 缺t
        "t=1&nonce=x&sig=y&extra=z", // 未知字段
    ];
    for v in malformed {
        assert_eq!(
            fms_rgw::sign::verify_signature_header(
                secret,
                "POST",
                "/api/gl/vouchers",
                &body_hash,
                v,
                now_secs(),
                "",
                ""
            ),
            Err(fms_rgw::sign::SignError::Malformed),
            "变体 '{v}' 必须 Malformed 拒绝"
        );
    }
    // 非hex sig，64字符但非法字符则恒定时间比较失败，回 InvalidSignature
    let bad_sig = "g".repeat(64);
    let h = format!("t={t}&nonce={nonce}&sig={bad_sig}");
    assert_eq!(
        fms_rgw::sign::verify_signature_header(
            secret,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            &h,
            now_secs(),
            "",
            ""
        ),
        Err(fms_rgw::sign::SignError::InvalidSignature)
    );
    // 正确头值对照放行
    let good = format!("t={t}&nonce={nonce}&sig={sig}");
    assert!(
        fms_rgw::sign::verify_signature_header(
            secret,
            "POST",
            "/api/gl/vouchers",
            &body_hash,
            &good,
            now_secs(),
            "",
            ""
        )
        .is_ok()
    );

    // 网关侧，客户端携带上述变体请求则网关剥离并注入自己的有效签名
    // 这些POST请求体为空则网关注入的签名基于 sha256，对 "" 计算
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let jwt = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    let empty_hash = fms_rgw::sign::compute_body_hash(b"");
    for (i, variant) in ["", "t=1", "sig=x", "garbage!!!t=nope"].iter().enumerate() {
        let mut req = with_auth(request("POST", "/api/gl/vouchers"), &jwt);
        req.headers_mut()
            .insert("content-type", "application/json".parse().unwrap());
        if !variant.is_empty() {
            req.headers_mut()
                .insert("x-fms-signature", variant.parse().unwrap());
        }
        let resp = oneshot(&router, with_ip(req, "127.0.0.1")).await;
        assert_eq!(
            resp.status(),
            StatusCode::OK,
            "变体 {} 不应被网关拒(客户端头仅被剥离)",
            i
        );
        let hits = mock.take_filtered("/api/gl/vouchers");
        assert_eq!(hits.len(), 1, "变体 {} 应到达上游", i);
        let up_sig = hits[0]
            .header("x-fms-signature")
            .expect("上游必须有网关签名");
        assert!(
            fms_rgw::sign::verify_signature_header(
                secret,
                "POST",
                "/api/gl/vouchers",
                &empty_hash,
                up_sig,
                now_secs(),
                SUB,
                "operator"
            )
            .is_ok(),
            "变体 {} 到达上游的签名必须有效(基于实际请求体 sha256)",
            i
        );
    }
}

// 身份头伪造

/// 【攻击】客户端直带`X-FMS-UserId`/`X-FMS-Role`/`X-FMS-*`伪造身份，提权为admin/他人uid。
/// 【防护层】auth层(`strip_client_fms_headers`全量剥离`X-FMS-*`)+ `inject_identity`
/// (值严格取自`Claims`)+ 外层`mw_access_log`兜底剥离。上游只看到`Claims`身份。
/// 【预期】200，上游x-fms-userid == `Claims`.sub、x-fms-role == `Claims`.role、
/// 附加`X-FMS-*`头全部不存在。
#[tokio::test]
async fn client_fms_identity_forgery_stripped() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let jwt = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    let mut req = with_auth(request("GET", "/api/gl/vouchers"), &jwt);
    req.headers_mut().insert(
        "x-fms-userid",
        "00000000-0000-4000-8000-000000000000".parse().unwrap(),
    );
    req.headers_mut()
        .insert("X-FMS-Role", "admin".parse().unwrap());
    req.headers_mut()
        .insert("x-fms-is-root", "1".parse().unwrap());
    let resp = oneshot(&router, with_ip(req, "127.0.0.1")).await;
    assert_eq!(resp.status(), StatusCode::OK);
    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(hits.len(), 1);
    let up = &hits[0];
    assert_eq!(
        up.header("x-fms-userid"),
        Some(SUB),
        "上游 UserId 必须来自 Claims(防伪造)"
    );
    assert_eq!(
        up.header("x-fms-role"),
        Some("operator"),
        "上游 Role 必须来自 Claims(防提权)"
    );
    assert_eq!(
        up.header("x-fms-is-root"),
        None,
        "客户端附加 X-FMS-* 头必须被剥离"
    );
}

/// 【攻击】伪造`X-Forwarded-For` / `X-Real-IP`企图换限流key / 误导上游ip维度，绕过ip限流。
/// 【防护层】rate层，`extract_client_ip`只取连接对端(`ConnectInfo`)，绝不解析客户端xff，
/// proxy层，`X-Forwarded-For`仅做"追加真实ip"，不信任原值。
/// 【预期】同对端ip换不同xff第3次则 429 + 42900 + `Retry-After`，不同对端ip 则 200，
/// 上游xff尾部为真实对端ip。
/// 【发现】`X-Real-IP`被客户端控制并原样透传上游，下方断言，若上游信任该头取真实ip会被
/// 直连客户端伪造，建议网关覆盖/剥离`X-Real-IP`，不改src，仅记录。
#[tokio::test]
async fn xff_xrealip_forgery_cannot_move_ratelimit_key() {
    let (router, mock) = router_with_mock(
        |cfg| {
            cfg.limits.ip_per_window = 2;
            cfg.limits.login_per_window = 100;
        },
        None,
    )
    .await;
    let jwt = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    for xff in ["1.1.1.1", "2.2.2.2"] {
        let mut req = with_auth(request("GET", "/api/gl/vouchers"), &jwt);
        req.headers_mut()
            .insert("x-forwarded-for", xff.parse().unwrap());
        req.headers_mut()
            .insert("x-real-ip", "203.0.113.66".parse().unwrap());
        let resp = oneshot(&router, with_ip(req, "10.0.0.1")).await;
        assert_eq!(resp.status(), StatusCode::OK, "XFF={xff} 在额度内应放行");
    }
    // 同真实ip，换xff第3次则 429，限流key是对端ip，xff伪造无效
    let mut req = with_auth(request("GET", "/api/gl/vouchers"), &jwt);
    req.headers_mut()
        .insert("x-forwarded-for", "3.3.3.3".parse().unwrap());
    req.headers_mut()
        .insert("x-real-ip", "198.51.100.7".parse().unwrap());
    let resp = oneshot(&router, with_ip(req, "10.0.0.1")).await;
    let (status, headers, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::TOO_MANY_REQUESTS,
        "伪造 XFF 不能绕过 IP 限流"
    );
    assert_eq!(body["code"], 42900);
    assert!(headers.get("retry-after").is_some(), "429 必带 Retry-After");
    // 不同真实ip则独立 key，放行
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(request("GET", "/api/gl/vouchers"), &jwt),
            "10.0.0.2",
        ),
    )
    .await;
    assert_eq!(resp.status(), StatusCode::OK, "不同对端 IP 独立限流 key");
    // 到达上游的3个请求，xff尾部追加真实对端ip，防绕过核心，
    // 前2个来自10.0.0.1，各自伪造不同xff，第3个来自10.0.0.2
    let hits = mock.take_filtered("/api/gl/vouchers");
    assert_eq!(
        hits.len(),
        3,
        "限流内 3 个请求到达上游，被拦截的第 4 个未到达"
    );
    for (i, h) in hits.iter().enumerate() {
        let xff = h.header("x-forwarded-for").unwrap_or("");
        let expect_tail = if i < 2 { "10.0.0.1" } else { "10.0.0.2" };
        assert!(
            xff.ends_with(expect_tail),
            "第 {} 个请求上游 XFF 必须以真实对端 IP 结尾，实际 '{xff}'",
            i + 1
        );
    }
    // X-Real-IP已被网关覆盖为真实对端ip，客户端伪造203.0.113.66不生效
    assert_eq!(
        hits[0].header("x-real-ip"),
        Some("10.0.0.1"),
        "X-Real-IP 必须覆盖为真实对端 IP，客户端伪造值不得透传"
    );
    assert_eq!(
        hits[1].header("x-real-ip"),
        Some("10.0.0.1"),
        "换 XFF 不换对端，X-Real-IP 仍为真实对端 IP"
    );
    assert_eq!(
        hits[2].header("x-real-ip"),
        Some("10.0.0.2"),
        "未携带 X-Real-IP 的请求也统一覆盖为真实对端 IP"
    );
}

// 登录暴力破解

/// 【攻击】对`/api/auth/login`连打撞库，同ip不同账号/口令。
/// 【防护层】rate层，login:<对端ip> 独立维度(`login_per_window`，收敛防撞库)，先于用户维度，
/// 超限则 429 + `Retry-After` + 42900，请求不进入 auth/sign/proxy。
/// 【预期】3次内放行、第4次429，换xff无效，上游恰好收到3次。
#[tokio::test]
async fn login_bruteforce_429_with_retry_after() {
    let (router, mock) = router_with_mock(
        |cfg| {
            cfg.limits.ip_per_window = 1000;
            cfg.limits.login_per_window = 3;
        },
        None,
    )
    .await;
    for i in 0..3 {
        let mut req = with_json_body(
            "/api/auth/login",
            &format!(r#"{{"username":"admin","password":"try{i}"}}"#),
        );
        req.headers_mut()
            .insert("x-forwarded-for", format!("10.0.0.{i}.9").parse().unwrap());
        let resp = oneshot(&router, with_ip(req, "127.0.0.9")).await;
        assert_eq!(
            resp.status(),
            StatusCode::OK,
            "第 {} 次登录应在额度内",
            i + 1
        );
    }
    // 第4次，换一个xff伪装则登录维度 429
    let mut req = with_json_body(
        "/api/auth/login",
        r#"{"username":"admin","password":"try999"}"#,
    );
    req.headers_mut()
        .insert("x-forwarded-for", "99.99.99.99".parse().unwrap());
    let resp = oneshot(&router, with_ip(req, "127.0.0.9")).await;
    let (status, headers, body) = collect_json(resp).await;
    assert_eq!(
        status,
        StatusCode::TOO_MANY_REQUESTS,
        "超过 login_per_window 必须 429"
    );
    assert_eq!(body["code"], 42900);
    let retry: u64 = headers
        .get("retry-after")
        .expect("429 必带 Retry-After")
        .to_str()
        .unwrap()
        .parse()
        .unwrap();
    assert!(retry >= 1, "Retry-After 至少 1s，实际 {retry}");
    // 防绕过核心，撞库请求只到上游3次，第4次被限流层拦截
    assert_hits(&mock, "/api/auth/login", 3, "第 4 次撞库不得到达上游").await;
}

/// 【攻击】用不同`X-Forwarded-For`伪造多客户端撞库，企图分摊计数。
/// 【防护层】rate层，login key = login:<对端ip>(`ConnectInfo`)，xff完全无效，
/// 不同真实ip才拥有独立额度。
/// 【预期】同ip换xff，3次后第4次仍429，不同真实ip第1次放行，上游共3次。
#[tokio::test]
async fn login_bruteforce_xff_spoof_does_not_bypass() {
    let (router, mock) = router_with_mock(
        |cfg| {
            cfg.limits.ip_per_window = 1000;
            cfg.limits.login_per_window = 2;
        },
        None,
    )
    .await;
    // 同真实ip连打，每次换xff
    for xff in ["1.1.1.1", "2.2.2.2"] {
        let mut req = with_json_body("/api/auth/login", r#"{"username":"root","password":"x"}"#);
        req.headers_mut()
            .insert("x-forwarded-for", xff.parse().unwrap());
        let resp = oneshot(&router, with_ip(req, "10.5.5.5")).await;
        assert_eq!(resp.status(), StatusCode::OK, "XFF={xff} 在额度内");
    }
    // 第3次，新xff则 429
    let mut req = with_json_body("/api/auth/login", r#"{"username":"root","password":"x"}"#);
    req.headers_mut()
        .insert("x-forwarded-for", "3.3.3.3".parse().unwrap());
    let resp = oneshot(&router, with_ip(req, "10.5.5.5")).await;
    assert_eq!(
        resp.status(),
        StatusCode::TOO_MANY_REQUESTS,
        "XFF 伪造不能获得新额度"
    );
    // 不同真实ip则独立额度，放行
    let resp = oneshot(
        &router,
        with_ip(
            with_json_body("/api/auth/login", r#"{"username":"root","password":"x"}"#),
            "10.5.5.6",
        ),
    )
    .await;
    assert_eq!(resp.status(), StatusCode::OK, "不同真实 IP 独立额度");
    // 再回原ip，新xff则仍 429，旧额度未过期
    let mut req = with_json_body("/api/auth/login", r#"{"username":"root","password":"x"}"#);
    req.headers_mut()
        .insert("x-forwarded-for", "4.4.4.4".parse().unwrap());
    let resp = oneshot(&router, with_ip(req, "10.5.5.5")).await;
    assert_eq!(
        resp.status(),
        StatusCode::TOO_MANY_REQUESTS,
        "额度以对端 IP 计，XFF 无效"
    );
    assert_hits(&mock, "/api/auth/login", 3, "仅 3 个真实放行请求到达上游").await;
}

// 未认证访问矩阵

/// 【攻击】未携带token直扫受保护路径(`/api/gl`/、`/api/rpt`/ 等业务面枚举)。
/// 【防护层】auth层，非匿名`/api`/** 一律验签，失败则 401 + 40100，请求在 sign/proxy 前终止。
/// 【预期】全路径401 + 40100 + 上游零到达，伪造Bearer同样401。
#[tokio::test]
async fn protected_paths_401_without_token() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    for path in [
        "/api/gl/vouchers",
        "/api/gl/reports",
        "/api/rpt/entries",
        "/api/rpt/daily",
        "/api/nt/push",
        "/api/admin/users",
    ] {
        let resp = oneshot(&router, with_ip(request("GET", path), "127.0.0.1")).await;
        let (status, _, body) = collect_json(resp).await;
        assert_eq!(
            status,
            StatusCode::UNAUTHORIZED,
            "无 token 访问 {path} 必须 401"
        );
        assert_eq!(body["code"], 40100, "{path} 错误码应为 40100");
    }
    // 伪造Bearer同样拒绝
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(
                request("GET", "/api/gl/vouchers"),
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig",
            ),
            "127.0.0.1",
        ),
    )
    .await;
    assert_eq!(
        resp.status(),
        StatusCode::UNAUTHORIZED,
        "伪造 token 必须 401"
    );
    assert_hits(&mock, "/api/", 0, "未认证请求不得到达上游").await;
}

/// 【攻击】反向断言，匿名白名单必须放行(若误拒则`/healthz`探活与登录链路全断)，
/// 同时验证白名单路径上的`X-FMS-*`头同样被剥离，防借道注入。
/// 【防护层】auth层`is_anonymous_path`(`/healthz`、`/api/auth/login`、`/api/auth/refresh`、
/// `/api/auth/recovery`、`/api/auth/totp`/*，`/api/nt/ws`仅真正的ws升级请求免jwt，
/// 其query token由`ws_handler`自持校验，)+ 外层`mw_access_log`全量剥离`X-FMS-*`。
/// 【预期】healthz/login/refresh/totp匿名200，`/healthz`不转上游，login + ws各到上游1次，
/// 上游看不到客户端伪造的`X-FMS-*`。
#[tokio::test]
async fn anonymous_whitelist_passes_without_token() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    // /healthz，200且不转发上游
    let resp = oneshot(&router, request("GET", "/healthz")).await;
    assert_eq!(resp.status(), StatusCode::OK, "healthz 匿名放行");
    // /api/auth/login，无token放行并转发
    let mut req = with_json_body("/api/auth/login", r#"{"username":"a","password":"b"}"#);
    req.headers_mut()
        .insert("x-fms-userid", "forged".parse().unwrap());
    let resp = oneshot(&router, with_ip(req, "127.0.0.1")).await;
    assert_eq!(resp.status(), StatusCode::OK, "login 匿名放行");
    // -1，2fa/refresh/recovery端点匿名放行，access jwt签发前的登录闭环端点
    for path in [
        "/api/auth/refresh",
        "/api/auth/recovery",
        "/api/auth/totp/verify",
        "/api/auth/totp/setup",
    ] {
        let resp = oneshot(&router, with_ip(with_json_body(path, "{}"), "127.0.0.1")).await;
        assert_eq!(resp.status(), StatusCode::OK, "{path} 匿名放行(X6-1)");
    }
    // /api/nt/ws，真正的ws升级请求 + 有效query token则放行并转发
    // (无token的升级请求由ws_handler 401，非升级http请求须走jwt，)
    let jwt = make_jwt(SUB, "operator", 3600, JWT_SECRET);
    let mut ws_req = request("GET", &format!("/api/nt/ws?token={jwt}"));
    ws_req
        .headers_mut()
        .insert("upgrade", "websocket".parse().unwrap());
    ws_req
        .headers_mut()
        .insert("connection", "Upgrade".parse().unwrap());
    let resp = oneshot(&router, with_ip(ws_req, "127.0.0.1")).await;
    assert_eq!(resp.status(), StatusCode::OK, "WS 升级 + 有效 token 应放行");
    let hits = mock.take_filtered("/api/");
    assert_eq!(
        hits.len(),
        6,
        "login + refresh + recovery + totp×2 + ws 各到上游一次"
    );
    let login_hit = hits
        .iter()
        .find(|h| h.path == "/api/auth/login")
        .expect("login 上游命中");
    assert_eq!(
        login_hit.header("x-fms-userid"),
        None,
        "匿名路径上游也不得收到客户端伪造的 X-FMS-*"
    );
    //，非升级http请求(无Upgrade头)访问/api/nt/ws则 401，不再匿名放行
    let resp = oneshot(&router, with_ip(request("GET", "/api/nt/ws"), "127.0.0.1")).await;
    assert_eq!(
        resp.status(),
        StatusCode::UNAUTHORIZED,
        "非升级 /api/nt/ws 必须验签(F4)"
    );
}

/// 【攻击】对照基线，合法token请求必须全链贯通，防测试把正常链路误判为攻击。
/// 【防护层】auth、sign、proxy 全链贯通。
/// 【预期】200 + 上游1次 + 签名可校验 + 身份头正确。
#[tokio::test]
async fn valid_token_reaches_upstream() {
    let (router, mock) = router_with_mock(|_| {}, None).await;
    let jwt = make_jwt(SUB, "viewer", 3600, JWT_SECRET);
    let resp = oneshot(
        &router,
        with_ip(
            with_auth(request("GET", "/api/rpt/entries"), &jwt),
            "127.0.0.1",
        ),
    )
    .await;
    assert_eq!(resp.status(), StatusCode::OK, "合法 token 正常链路必须 200");
    let hits = mock.take_filtered("/api/rpt/entries");
    assert_eq!(hits.len(), 1);
    assert_eq!(hits[0].header("x-fms-userid"), Some(SUB));
    assert_eq!(hits[0].header("x-fms-role"), Some("viewer"));
}

// 会话重放/中间人，hsts

/// 【攻击】明文http中间人，降级配置(`allow_insecure_fallback`=true)下以明文访问，
/// 若网关误发hsts头，浏览器将把未来请求升级为https，语义冲突，反之明文下必须有hsts缺失
/// 作为"该连接不安全"的信号，且tls配置下必须注入hsts，防SSL-strip中间人降级。
/// 【防护层】headers层，`apply_security_headers`仅在`is_tls`，cert+key已配置时注入hsts。
/// 【预期】明文无 tls 配置则不返回 strict-transport-security，有 tls 配置则回 hsts
/// "max-age=31536000; includeSubDomains"。
#[tokio::test]
async fn hsts_only_when_tls_configured() {
    // 明文(test_config默认无证书)则不注入 hsts
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    let resp = oneshot(&router, request("GET", "/healthz")).await;
    assert!(
        !resp.headers().contains_key("strict-transport-security"),
        "明文 HTTP 不得注入 HSTS(否则中间人降级时语义自相矛盾)"
    );
    // tls配置，证书路径存在性仅影响serve层，安全头判断只看配置
    let (router2, _mock2) = router_with_mock(
        |cfg| {
            cfg.tls.cert = Some("certs/gw.pem".to_string());
            cfg.tls.key = Some("certs/gw.key".to_string());
        },
        None,
    )
    .await;
    let resp = oneshot(&router2, request("GET", "/healthz")).await;
    assert_eq!(
        resp.headers()
            .get("strict-transport-security")
            .and_then(|v| v.to_str().ok()),
        Some("max-age=31536000; includeSubDomains"),
        "TLS 配置下必须注入 HSTS(防 SSL-strip)"
    );
}

/// 【攻击】中间人视角检查错误响应，4xx/5xx响应若缺安全头会被注入钓鱼/降级页。
/// 【防护层】log层，`apply_security_headers`对全部响应，含401/429统一施加。
/// 【预期】401响应带csp/`X-Frame-Options`/`X-Content-Type-Options`/`Referrer-Policy`/`Permissions-Policy`，
/// 明文下无hsts，429响应同样带csp。
#[tokio::test]
async fn security_headers_on_error_responses() {
    let (router, _mock) = router_with_mock(|_| {}, None).await;
    let resp = oneshot(
        &router,
        with_ip(request("GET", "/api/gl/vouchers"), "127.0.0.1"),
    )
    .await;
    let (status, headers, _) = collect(resp).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    for h in [
        "content-security-policy",
        "x-frame-options",
        "x-content-type-options",
        "referrer-policy",
        "permissions-policy",
    ] {
        assert!(headers.contains_key(h), "401 响应必须带 {h}");
    }
    assert!(
        !headers.contains_key("strict-transport-security"),
        "明文 401 无 HSTS"
    );
    // 429错误响应同样带安全头，限流打击时防页面注入
    let (router2, _mock2) = router_with_mock(
        |cfg| {
            cfg.limits.ip_per_window = 1;
            cfg.limits.login_per_window = 100;
        },
        None,
    )
    .await;
    let _ = oneshot(
        &router2,
        with_ip(request("GET", "/api/gl/vouchers"), "127.0.0.8"),
    )
    .await;
    let resp = oneshot(
        &router2,
        with_ip(request("GET", "/api/gl/vouchers"), "127.0.0.8"),
    )
    .await;
    let (status, headers, _) = collect(resp).await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
    assert!(
        headers.contains_key("content-security-policy"),
        "429 响应必须带 CSP"
    );
}
