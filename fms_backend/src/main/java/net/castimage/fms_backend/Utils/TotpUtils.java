package net.castimage.fms_backend.Utils;

import com.bastiaanjansen.otp.HMACAlgorithm;
import com.bastiaanjansen.otp.TOTPGenerator;
import org.apache.commons.codec.binary.Base32;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** totp 工具，RFC 6238 动态码生成/校验委托 otp-java，Base32 编解码用 commons-codec，
 *  另含 otpauth uri 与一次性备用码，安全算法不复刻自实现 */
public final class TotpUtils {

    /** totp 步长，秒 */
    private static final long STEP_SECONDS = 30L;

    /** 验证码位数 */
    private static final int DIGITS = 6;

    /** 密钥字节数，hmac-SHA1 推荐 20 字节 = 160bit */
    private static final int SECRET_BYTES = 20;

    /** RFC 4648 Base32，默认补 '='，20 字节恰为 32 字符整组无需补 */
    private static final Base32 BASE32 = new Base32();

    /** 密钥与备用码随机源 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpUtils() {
    }

    /** 生成随机 totp 密钥，Base32 无填充编码（20 字节恰好 32 字符整组） */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES]; // 20 字节 = hmac-SHA1 推荐密钥长，
        RANDOM.nextBytes(bytes);
        return BASE32.encodeToString(bytes);
    }

    /** 生成 otpauth uri（2fa 扫码/手动录入用） */
    public static String otpauthUrl(String issuer, String accountName, String secretBase32) {
        return "otpauth://totp/" + encodePath(issuer + ":" + accountName)
                + "?secret=" + secretBase32
                + "&issuer=" + encodePath(issuer)
                + "&period=" + STEP_SECONDS + "&digits=" + DIGITS;
    }

    /**
     * totp 校验，委托 otp-java（SHA1 / 6 位 / 30 秒步长），window 为时钟漂移容忍步数，
     * 校验在 [-window, +window] 内逐步比对。任何输入不合法，secret 空白、code 非 6 位
     * 数字、Base32 解码失败一律返回 false 而不抛异常，调用方统一按验证失败处理，
     * 不泄露"密钥不符"还是"格式非法"。window=0 表示仅当前步严格校验，成功不消耗任何
     * 状态，无一次性语义，同一 code 在窗口内可重复通过，如需防重放调用方应结合会话
     * 票据或一次性消费记录。
     *
     * @param secretBase32 共享密钥，RFC 4648 Base32 编码，容忍大小写与填充符
     * @param code         用户输入的 6 位验证码
     * @param window       时钟偏移容忍步数（±window，每步 30 秒，0 表示仅当前步）
     * @return 窗口内任一步重算结果与 code 相等才为 true
     */
    public static boolean verify(String secretBase32, String code, int window) {
        if (secretBase32 == null || secretBase32.isBlank()
                || code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return false;
        }

        // 仅做输入合法性闸口，非法 base32 一律返回 false，不抛异常，
        // otp-java Builder 期望 base32 编码文本且内部会再解码，故生成器必须用 secret 文本构建
        byte[] key;
        try {
            key = BASE32.decode(secretBase32);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (key.length == 0) {
            return false;
        }

        TOTPGenerator generator = new TOTPGenerator.Builder(secretBase32)
                .withHOTPGenerator(h -> h.withAlgorithm(HMACAlgorithm.SHA1).withPasswordLength(DIGITS))
                .withPeriod(Duration.ofSeconds(STEP_SECONDS))
                .build();
        return generator.verify(code, window);
    }

    /** 生成一次性备用码，2fa 恢复，去易混淆字符集，4 位一组短横线分隔 */
    public static List<String> generateBackupCodes(int count, int length) {
        // 字符集已剔除 0/O/1/I 等易混淆项，
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < length; j++) {
                if (j > 0 && j % 4 == 0) {
                    sb.append('-');
                }
                sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    /** otpauth uri 路径/参数编码 */
    private static String encodePath(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
