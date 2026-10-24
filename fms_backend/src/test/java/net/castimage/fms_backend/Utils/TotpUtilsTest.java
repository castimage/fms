package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Mac;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * totp 工具测试，密钥/otpauth uri 格式、非法输入 fail-closed（一律 false 不抛异常，
 * 不泄露"密钥不符"还是"格式非法"）、备用恢复码格式与字符集。
 */
class TotpUtilsTest {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    @Test
    void generateSecretIs32CharBase32() {
        String secret = TotpUtils.generateSecret();
        assertEquals(32, secret.length()); // 20 字节对应 32 个 base32 字符，无填充
        for (char c : secret.toCharArray()) {
            assertTrue(BASE32_ALPHABET.indexOf(c) >= 0, "非法 base32 字符: " + c);
        }
    }

    @Test
    void otpauthUrlStructureComplete() {
        String secret = TotpUtils.generateSecret();
        String url = TotpUtils.otpauthUrl("FMS Corp", "alice smith", secret);
        assertTrue(url.startsWith("otpauth://totp/"));
        assertTrue(url.contains("secret=" + secret));
        assertTrue(url.contains("issuer=FMS%20Corp"));
        assertTrue(url.contains("algorithm=SHA256&period=30&digits=6"));
        assertTrue(url.contains("%20")); // issuer/account 中空格 url 编码
    }

    @Test
    void invalidInputsAlwaysFalse() {
        String secret = TotpUtils.generateSecret();
        assertFalse(TotpUtils.verify(null, "123456", 0));
        assertFalse(TotpUtils.verify("", "123456", 0));
        assertFalse(TotpUtils.verify(secret, null, 0));
        assertFalse(TotpUtils.verify(secret, "", 0));
        assertFalse(TotpUtils.verify(secret, "12345", 0));     // 5 位
        assertFalse(TotpUtils.verify(secret, "12345a", 0));    // 含非数字
        assertFalse(TotpUtils.verify("A!B!C!D!E!F!G!H!I!J!", "123456", 0)); // 非法 base32
    }

    @Test
    void caseAndPaddingTolerantBase32Decodes() {
        String secret = TotpUtils.generateSecret();
        // 小写与填充符容忍，不抛异常，结果布尔，仅验证解码路径可用
        TotpUtils.verify(secret.toLowerCase(), "123456", 0);
        TotpUtils.verify(secret + "=", "123456", 0);
    }

    @Test
    void backupCodesCountLengthAndFormat() {
        List<String> codes = TotpUtils.generateBackupCodes(10, 8);
        assertEquals(10, codes.size());
        Set<String> unique = new HashSet<>(codes);
        assertEquals(10, unique.size()); // 互不相同
        for (String code : codes) {
            assertEquals(9, code.length());            // XXXX-XXXX
            assertEquals(1, code.chars().filter(c -> c == '-').count());
            assertTrue(code.matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"),
                    "备用码含易混淆字符: " + code);
        }
    }

    @Test
    void backupCodesCountParameterized() {
        List<String> codes = TotpUtils.generateBackupCodes(5, 10);
        assertEquals(5, codes.size());
        assertEquals(12, codes.getFirst().length()); // XXXX-XXXX-XX
    }

    @Test
    void verifyInteropsWithStandardTotp() {
        // 独立参考实现算码，校验委托产物与独立参考实现一致
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        long now = System.currentTimeMillis() / 1000L;
        String ref = referenceTotp(secret, now);
        assertTrue(TotpUtils.verify(secret, ref, 1), "窗口 ±1 内参考码应通过");
        assertFalse(TotpUtils.verify(secret, "000000", 0), "错误码应拒绝");
        assertFalse(TotpUtils.verify("A!B!C!D!E!F!G!H!I!J!", ref, 1), "非法 base32 应返回 false");
    }

    private static String referenceTotp(String secretBase32, long epochSec) {
        byte[] key = new org.apache.commons.codec.binary.Base32().decode(secretBase32);
        long counter = epochSec / 30L;
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xFF);
            counter >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal(msg);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
