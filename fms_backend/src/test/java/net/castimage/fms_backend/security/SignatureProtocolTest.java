package net.castimage.fms_backend.security;

import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Utils.GatewaySignatureFilter;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关签名协议单元测试，
 *
 *   hmac-sha256 基元对 RFC 4231 已知向量，证明 javax.crypto 用法正确，
 *   空 body 哈希常量 = SHA-256("")（与 fms_rgw compute_body_hash 一致），
 *   反射调用生产类 GatewaySignatureFilter 的私有 computeSignature，验证
 *       {@code method|path|body_hash|t|nonce|uid|role} 输入串与 '|'=0x7C 无转义、null 身份段落空串，
 *   parseSignature 对 t=&nonce=&sig= 三字段的接受/拒绝行为。
 */
class SignatureProtocolTest {

    /** RFC 4231 Test Case 2，key="Jefe"，data="what do ya want for nothing?" */
    private static final String RFC4231_KEY = "Jefe";
    private static final String RFC4231_DATA = "what do ya want for nothing?";
    private static final String RFC4231_EXPECTED =
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";

    /** SHA-256("") 的 hex（fms_rgw compute_body_hash 空 body 定义） */
    private static final String EMPTY_BODY_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** 签名密钥（≥32 字符，满足过滤器 fail-fast 校验） */
    private static final String SECRET = "unit-test-sign-secret-0123456789abcdef-42";

    // ---------------- hmac-sha256 基元 vs RFC 4231 ----------------

    @Test
    void hmacSha256MatchesRfc4231KnownVector() throws Exception {
        assertEquals(RFC4231_EXPECTED, hmacSha256Hex(RFC4231_KEY, RFC4231_DATA));
    }

    @Test
    void emptyBodyHashMatchesSpecConstant() throws Exception {
        Method sha256Hex = GatewaySignatureFilter.class.getDeclaredMethod("sha256Hex", byte[].class);
        sha256Hex.setAccessible(true);
        String actual = (String) sha256Hex.invoke(null, new Object[]{new byte[0]});
        assertEquals(EMPTY_BODY_HASH, actual);
    }

    // ---------------- 生产类签名计算，反射 ----------------

    @Test
    void computeSignatureMatchesSpecInputComposition() throws Exception {
        GatewaySignatureFilter filter = new GatewaySignatureFilter(SECRET, 300, 10_000, 1000, false);
        Method compute = GatewaySignatureFilter.class.getDeclaredMethod(
                "computeSignature", String.class, String.class, String.class,
                long.class, String.class, String.class, String.class);
        compute.setAccessible(true);

        String method = "POST";
        String path = "/api/auth/login";
        String bodyHash = EMPTY_BODY_HASH;
        long t = 1_700_000_000L;
        String nonce = UuidV7.next().toString();
        String uid = UuidV7.next().toString();
        String role = "operator";

        String actual = (String) compute.invoke(filter, method, path, bodyHash, t, nonce, uid, role);
        String expectedInput = method + "|" + path + "|" + bodyHash + "|" + t + "|" + nonce + "|" + uid + "|" + role;
        assertEquals(hmacSha256Hex(SECRET, expectedInput), actual);
        // 64 位小写 hex
        assertEquals(64, actual.length());
        assertTrue(actual.matches("[0-9a-f]{64}"));
    }

    @Test
    void computeSignatureUsesEmptySegmentsForNullIdentity() throws Exception {
        GatewaySignatureFilter filter = new GatewaySignatureFilter(SECRET, 300, 10_000, 1000, false);
        Method compute = GatewaySignatureFilter.class.getDeclaredMethod(
                "computeSignature", String.class, String.class, String.class,
                long.class, String.class, String.class, String.class);
        compute.setAccessible(true);

        String actual = (String) compute.invoke(filter, "GET", "/api/x", EMPTY_BODY_HASH,
                100L, "n1", null, null);
        // 匿名路径 uid/role 落空串，与网关 sign.rs 行为一致
        assertEquals(hmacSha256Hex(SECRET, "GET|/api/x|" + EMPTY_BODY_HASH + "|100|n1||"), actual);
    }

    // ---------------- X-FMS-Signature 头解析 ----------------

    @Test
    void parseSignatureAcceptsValidHeader() throws Exception {
        Method parse = GatewaySignatureFilter.class.getDeclaredMethod("parseSignature", String.class);
        parse.setAccessible(true);
        String nonce = UuidV7.next().toString();
        String sig = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Object parsed = parse.invoke(null, "t=1700000000&nonce=" + nonce + "&sig=" + sig);
        String toString = parsed.toString();
        assertTrue(toString.contains("t=1700000000"), toString);
        assertTrue(toString.contains("nonce=" + nonce), toString);
        assertTrue(toString.contains("sig=" + sig), toString);
    }

    @Test
    void parseSignatureRejectsMalformedHeaders() throws Exception {
        Method parse = GatewaySignatureFilter.class.getDeclaredMethod("parseSignature", String.class);
        parse.setAccessible(true);
        String sig = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String nonce = UuidV7.next().toString();
        // 缺字段 / 非法 t / 未知字段 / nonce 非 uuid / sig 非 64 位小写 hex
        assertThrows(Exception.class, () -> invokeParse(parse, "nonce=" + nonce + "&sig=" + sig));
        assertThrows(Exception.class, () -> invokeParse(parse, "t=abc&nonce=" + nonce + "&sig=" + sig));
        assertThrows(Exception.class, () -> invokeParse(parse, "t=1700000000&nonce=" + nonce + "&sig=" + sig + "&x=1"));
        assertThrows(Exception.class, () -> invokeParse(parse, "t=1700000000&nonce=not-a-uuid&sig=" + sig));
        assertThrows(Exception.class, () -> invokeParse(parse, "t=1700000000&nonce=" + nonce + "&sig=ABC"));
    }

    private static void invokeParse(Method parse, String header) throws Exception {
        parse.invoke(null, header);
    }

    // ---------------- 独立 hmac-sha256，测试内同构实现 ----------------

    private static String hmacSha256Hex(String secret, String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }
}
