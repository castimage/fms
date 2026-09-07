package net.castimage.fms_backend.Utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;

/** totp 工具，RFC 6238 自实现，hmac-SHA1 / 6 位 / 30 秒步长，含 RFC 4648 Base32 与 otpauth uri，免 otp-java 外部依赖 */
public final class TotpUtils {

    /** totp 步长，秒 */
    private static final long STEP_SECONDS = 30L;

    /** 验证码位数 */
    private static final int DIGITS = 6;

    /** 密钥字节数，hmac-SHA1 推荐 20 字节 = 160bit */
    private static final int SECRET_BYTES = 20;

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpUtils() {
    }

    /** 生成随机 totp 密钥，Base32 无填充编码 */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES]; // 20 字节 = hmac-SHA1 推荐密钥长，
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** 生成 otpauth uri（2fa 扫码/手动录入用） */
    public static String otpauthUrl(String issuer, String accountName, String secretBase32) {
        return "otpauth://totp/" + encodePath(issuer + ":" + accountName)
                + "?secret=" + secretBase32
                + "&issuer=" + encodePath(issuer)
                + "&period=" + STEP_SECONDS + "&digits=" + DIGITS;
    }

    /**
     * totp 校验，按当前时间步进，STEP_SECONDS=30s/步在 [-window, +window] 窗口内逐步
     * 重算动态码并与 code 比较，任一步命中即通过，window 用于容忍身份验证器与服务器间的
     * 时钟漂移。任何输入不合法，secret 空白、code 非 6 位数字、Base32 解码失败一律返回
     * false 而不抛异常，调用方统一按验证失败处理，逐位比较走 MessageDigest.isEqual 恒定
     * 时间，防时序侧信道。
     *
     * <p>语义要点，窗口以当前时间为中心双向覆盖，clock skew 常见于手机验证器快/慢
     * 若干步，window=0 表示仅当前步严格校验，成功不消耗任何状态，无一次性语义，
     * 同一 code 在窗口内可重复通过，如需防重放，调用方应结合会话票据或一次性消费
     * 记录。失败路径与非法输入输出一致（false），不泄露是"密钥不符"还是"格式非法"。
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

        byte[] key;
        try {
            key = base32Decode(secretBase32);
        } catch (IllegalArgumentException e) {
            return false;
        }

        long counter = System.currentTimeMillis() / 1000L / STEP_SECONDS;
        byte[] input = code.getBytes(StandardCharsets.US_ASCII);
        for (int i = -window; i <= window; i++) {
            if (MessageDigest.isEqual(generate(key, counter + i), input)) {
                return true;
            }
        }
        return false;
    }

    /** 生成一次性备用码，2fa 恢复，去易混淆字符集，4 位一组短横线分隔 */
    public static List<String> generateBackupCodes(int count, int length) {
        // 字符集已剔除 0/O/1/I 等易混淆项，
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < length; j++) {
                if (j > 0 && j % 4 == 0) {
                    sb.append('-');
                }
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    /**
     * RFC 6238 单步动态码生成，totp 核心算法，counter 按大端序 8 字节编码后做
     * hmac-SHA1，取摘要末字节低 4 位作为动态偏移（dynamic truncation），从偏移处取
     * 4 字节、首位清零防符号位，模 10^DIGITS 得到 0~999999 的动态码，补零到 6 位后
     * 以 ASCII 字节返回，供 MessageDigest.isEqual 恒定时间比较。
     *
     * <p>算法要点，动态截断偏移取自 hmac 摘要最后一个字节的低 4 位（0~15），从该
     * 偏移连续取 4 字节组装 31 位无符号整数，首字节 & 0x7F 清符号位，避免负数取模
     * 语义歧义，模 10^6 得 0~999999，格式化补零到 6 位，与主流身份验证器（Google
     * Authenticator 等）的 HOTP 截断完全一致，保证跨实现互认。返回 ASCII 字节而非
     * String 是为让调用方直接走 MessageDigest.isEqual 恒定时间比较，避免 String.equals
     * 短路泄露时序。
     *
     * @param key     解码后的共享密钥字节，hmac-SHA1 推荐 20 字节
     * @param counter 时间步进计数，epoch 秒 / STEP_SECONDS
     * @return 6 位动态码的 ASCII 字节数组
     */
    private static byte[] generate(byte[] key, long counter) {
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xFF);
            counter >>= 8;
        }
        byte[] hash = hmacSha1(key, msg);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = binary % (int) Math.pow(10, DIGITS);
        // 动态格式串走 String.formatted（DIGITS 参数化），
        return ("%0" + DIGITS + "d").formatted(otp).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] hmacSha1(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 不可用", e);
        }
    }

    /** RFC 4648 Base32 编码，无填充 */
    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    /** RFC 4648 Base32 解码，容忍大小写与填充符 */
    private static byte[] base32Decode(String input) {
        String s = input.toUpperCase().replace("=", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            int v = BASE32_ALPHABET.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("非法 Base32 字符: " + c);
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** otpauth uri 路径/参数编码 */
    private static String encodePath(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
