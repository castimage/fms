package net.castimage.fms_backend.Utils;

import net.castimage.fms_backend.Exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码工具测试，bcrypt 哈希/校验、空哈希防绕过、长度二次校验边界
 * （与 sql 种子 gen_salt('bf',10) 同强度，哈希可直接由 db crypt 校验互认）。
 */
class PasswordUtilsTest {

    @Test
    void encodeProducesBcryptHashAndVerifies() {
        String hash = PasswordUtils.encode("Kx#9mQ2vLpZ4sD!2026");
        assertTrue(hash.startsWith("$2a$10$"), "默认强度 10 产出 $2a$10$ 前缀");
        assertTrue(PasswordUtils.matches("Kx#9mQ2vLpZ4sD!2026", hash));
        assertFalse(PasswordUtils.matches("wrong-password", hash));
    }

    @Test
    void emptyOrNullHashAlwaysFalse() {
        assertFalse(PasswordUtils.matches("anything", null));
        assertFalse(PasswordUtils.matches("anything", ""));
        assertFalse(PasswordUtils.matches("anything", "   "));
    }

    @Test
    void lengthCheckBoundaries() {
        PasswordUtils.checkLength("12345678", 8, 64);      // 恰好下界通过
        PasswordUtils.checkLength("1234567890", 8, 64);    // 中间值通过
        assertThrows(BadRequestException.class, () -> PasswordUtils.checkLength("1234567", 8, 64));
        assertThrows(BadRequestException.class, () -> PasswordUtils.checkLength("12345678901234567890123456789012345678901234567890123456789012345", 8, 64));
        assertThrows(BadRequestException.class, () -> PasswordUtils.checkLength(null, 8, 64));
    }

    /** 配置收紧立即生效，min 取 max(配置,8) 防弱配置漂移 */
    @Test
    void tightenedConfigRaisesMinBound() {
        assertThrows(BadRequestException.class, () -> PasswordUtils.checkLength("12345678", 12, 64));
        PasswordUtils.checkLength("123456789012", 12, 64);
    }
}
