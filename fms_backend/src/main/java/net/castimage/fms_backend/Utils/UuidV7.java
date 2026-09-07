package net.castimage.fms_backend.Utils;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** UUIDv7 生成工具（时间有序：unix_ms 48bit + 随机 74bit，RFC 9562，与 PG 18 原生 uuidv7() 语义等价）；DEFAULT 列无法经 getGeneratedKeys 回传，故运行期 id 由 Java 侧显式生成 */
public final class UuidV7 {

    /** 版本位（version 7） */
    private static final long VERSION_BITS = 0x7000L;

    /** RFC 4122 变体位（10xx） */
    private static final long VARIANT_BITS = 0x8000000000000000L;

    private UuidV7() {
    }

    /** 生成 UUIDv7（线程安全，时间单调有序） */
    public static UUID next() {
        // 毫秒时间戳作高 48 位，保证时间有序，
        long ms = System.currentTimeMillis();
        // 随机 74 位由 ThreadLocalRandom 填充，
        byte[] rand = new byte[10];
        ThreadLocalRandom.current().nextBytes(rand);

        long hi = (ms << 16) | ((rand[0] & 0xFFL) << 8) | (rand[1] & 0xFFL);
        hi &= ~0xF000L;
        hi |= VERSION_BITS;

        long lo = 0;
        for (int i = 2; i < rand.length; i++) {
            lo = (lo << 8) | (rand[i] & 0xFFL);
        }
        lo &= ~0xC000000000000000L;
        lo |= VARIANT_BITS;
        return new UUID(hi, lo);
    }
}
