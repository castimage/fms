package net.castimage.fms_backend.integration;

import net.castimage.fms_backend.Utils.UuidV7;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 集成测试基类，全上下文装配（@SpringBootTest MOCK 环境 + MockMvc）+ test profile，连测试机 fms-db 经 SSH 隧道，沙箱角色 fms_app 直连。
 *
 * 签名工具与 fms_rgw sign.rs / GatewaySignatureFilter 协议同构，
 * body_hash=sha256(body) hex，input=method|path|body_hash|t|nonce|uid|role，
 * sig=hmac-sha256(sign_secret, input) 64 位小写 hex，t 为 epoch 秒。
 *
 * 子类直接 @Autowired MockMvc 与各 Bean，写操作用例须自行保证无副作用，本套件全部为只读/拒绝路径用例，不做数据写入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    /** 与 application-test.yaml fms.security.sign-secret 一致 */
    protected static final String SIGN_SECRET = "itest-sign-secret-0123456789abcdef0123456789";

    /** 空 body 的 SHA-256（GET/无 body 请求参与签名的固定哈希，与网关同构） */
    protected static final String EMPTY_BODY_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** 测试机种子 admin 用户固定 id（seed_system ADMIN_UID，scope 为全公司/全部门） */
    protected static final String ADMIN_UID = "00000000-0000-7000-0000-000000000001";

    @Autowired
    protected MockMvc mockMvc;

    /** 防 ThreadLocal 泄漏，直调 Mapper 用例手工 set RequestContext 后必须清理 */
    @AfterEach
    void cleanThreadLocal() {
        net.castimage.fms_backend.Utils.RequestContext.clear();
    }

    /**
     * 构造 X-FMS-Signature 头值，按网关签名协议对 method|path|bodyHash|t|nonce|uid|role
     * 计算 hmac-sha256（t=当前 epoch 秒、nonce=随机 uuid）。uid/role 传空串即为匿名路径
     * 签名形态，与网关对匿名请求不注入身份的行为一致。
     *
     * @param method    http 方法，大写，如 GET
     * @param path      请求路径，如 /api/config，与请求 uri 完全一致
     * @param bodyHash  sha256(body) hex（空 body 用 EMPTY_BODY_HASH）
     * @param uid       X-FMS-UserId（采信身份，匿名传 ""）
     * @param role      X-FMS-Role 短名（匿名传 ""）
     * @return X-FMS-Signature 头值（t=&nonce=&sig=）
     */
    protected String sign(String method, String path, String bodyHash, String uid, String role) {
        long t = System.currentTimeMillis() / 1000;
        String nonce = UuidV7.next().toString();
        String input = method + "|" + path + "|" + bodyHash + "|" + t + "|" + nonce + "|" + uid + "|" + role;
        String sig = hmacSha256Hex(SIGN_SECRET, input);
        return "t=" + t + "&nonce=" + nonce + "&sig=" + sig;
    }

    /** hmac-sha256 输出 64 位小写 hex（与 SignatureProtocolTest 同实现） */
    protected static String hmacSha256Hex(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 hex（构造带 body 请求的 body_hash） */
    protected static String sha256Hex(byte[] body) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 已采信身份的合法请求头，uid=admin、role=admin + 对应签名 */
    protected org.springframework.http.HttpHeaders authHeaders(String method, String path, String bodyHash) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.set("X-FMS-UserId", ADMIN_UID);
        h.set("X-FMS-Role", "admin");
        h.set("X-FMS-Signature", sign(method, path, bodyHash, ADMIN_UID, "admin"));
        return h;
    }
}
