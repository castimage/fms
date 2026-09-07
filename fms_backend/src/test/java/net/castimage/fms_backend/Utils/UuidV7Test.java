package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UUIDv7 生成工具测试，版本位/变体位/RFC 4122 格式、时间单调有序，与 pg 18 原生
 * uuidv7 语义对齐，时间有序保证 id 排序即写入序。
 */
class UuidV7Test {

    @Test
    void versionAndVariantBitsCorrect() {
        UUID id = UuidV7.next();
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void standardFormat36Chars() {
        String s = UuidV7.next().toString();
        assertEquals(36, s.length());
        assertEquals(8, s.indexOf('-'));
        assertEquals(13, s.indexOf('-', 9));
    }

    /** 时间有序，高 48 位时间戳单调不减，同毫秒内 lo 随机位无序，compareTo 不适用 */
    @Test
    void timestampMonotonicNonDecreasing() {
        long prevTs = UuidV7.next().getMostSignificantBits() >>> 16;
        for (int i = 0; i < 1000; i++) {
            long nextTs = UuidV7.next().getMostSignificantBits() >>> 16;
            assertTrue(nextTs >= prevTs, "uuidv7 时间戳必须单调不减，i=" + i);
            prevTs = nextTs;
        }
    }

    /** 高 48 位时间戳与当前毫秒时间对齐（±5s 容差） */
    @Test
    void timestampAlignedWithCurrentTime() {
        long now = System.currentTimeMillis();
        long ts = UuidV7.next().getMostSignificantBits() >>> 16;
        assertTrue(Math.abs(ts - now) < 5000, "时间戳偏离当前时间: ts=" + ts + " now=" + now);
    }

    @Test
    void consecutiveIdsDistinct() {
        UUID a = UuidV7.next();
        UUID b = UuidV7.next();
        UUID c = UuidV7.next();
        assertTrue(!a.equals(b) && !b.equals(c) && !a.equals(c));
    }
}
