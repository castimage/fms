package net.castimage.fms_backend.Utils;

import net.castimage.fms_backend.Exception.BadRequestException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 密码工具：BCrypt 哈希与校验（默认强度 10，与 SQL 种子 crypt(gen_salt('bf',10)) 兼容；仅依赖 spring-security-crypto，后端无 SecurityFilterChain），
 * 另提供 Service 层密码长度二次校验（fms.security.password-min/max-length 收敛入口）。
 */
public final class PasswordUtils {

    /** BCrypt 成本因子默认值（与 SQL 种子 gen_salt('bf',10) 对齐） */
    private static final int DEFAULT_STRENGTH = 10;

    /** 是否已按配置执行过幂等 configure（首次生效，防止测试/启动顺序竞态） */
    private static final AtomicBoolean CONFIGURED =
            new AtomicBoolean(false);

    /** 当前编码器（configure 后重建；volatile 发布，运行期只读） */
    private static volatile BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(DEFAULT_STRENGTH); // 单例复用（线程安全），

    private PasswordUtils() { // 工具类禁止实例化，
    }

    /**
     * 幂等配置 BCrypt 成本因子（fms.security.bcrypt-strength；首次调用生效，默认 10；提高强度仅影响新哈希，
     * 既有 $2a$10$ 哈希仍可校验——BCrypt 哈希自带强度元数据）。
     */
    public static void configure(int strength) {
        if (CONFIGURED.compareAndSet(false, true)) {
            ENCODER = new BCryptPasswordEncoder(Math.max(8, strength)); // 下限 8 防弱配置，
        }
    }

    /** BCrypt 哈希（默认产出 $2a$10$ 前缀） */
    public static String encode(String rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    /** BCrypt 校验（库中哈希为空恒 false，防空值绕过） */
    public static boolean matches(String rawPassword, String encodedHash) {
        if (encodedHash == null || encodedHash.isBlank()) {
            return false;
        }
        return ENCODER.matches(rawPassword, encodedHash);
    }

    /**
     * 密码长度二次校验（P9 收敛：配置收紧立即生效——min 取 max(配置,8) 防与 DTO @Size(min=8) 漂移出更弱策略；
     * 超限抛 40001，调用点位于弱密码预检之前）。
     */
    public static void checkLength(String rawPassword, int configuredMin, int configuredMax) {
        int effectiveMin = Math.max(configuredMin, 8);
        int effectiveMax = Math.max(configuredMax, effectiveMin);
        int len = rawPassword == null ? 0 : rawPassword.length();
        if (len < effectiveMin || len > effectiveMax) {
            throw new BadRequestException("密码长度须在 " + effectiveMin + "-" + effectiveMax + " 位之间");
        }
    }
}
