package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CachePort 内存实现契约测试，多实例共享状态的单实例基线，6 方法语义逐条验证，
 * SETNX 语义（setIfAbsent）、滑动窗口自增、ttl 过期视为不存在。
 *
 * Redis 实现须与内存版逐条对齐，见 RedisCachePortTest。
 */
class InMemoryCachePortTest {

    private static final Duration SHORT_TTL = Duration.ofMillis(50);
    private static final Duration LONG_TTL = Duration.ofSeconds(10);

    private final InMemoryCachePort cache = new InMemoryCachePort();

    @Test
    void setIfAbsentFirstWriteTrueDuplicateFalse() {
        assertTrue(cache.setIfAbsent("k", "v1", LONG_TTL));
        assertFalse(cache.setIfAbsent("k", "v2", LONG_TTL)); // 已存在，不改值不续 ttl
        assertEquals("v1", cache.get("k").orElseThrow());
    }

    @Test
    void setIfAbsentExpiredEntryReregisters() throws InterruptedException {
        cache.set("k", "old", SHORT_TTL);
        Thread.sleep(80); // ttl 过期视为不存在
        assertTrue(cache.setIfAbsent("k", "new", LONG_TTL));
        assertEquals("new", cache.get("k").orElseThrow());
    }

    @Test
    void setGetDeleteRoundTrip() {
        assertTrue(cache.get("absent").isEmpty());
        cache.set("k", "v", LONG_TTL);
        assertEquals(Optional.of("v"), cache.get("k"));
        cache.delete("k");
        assertTrue(cache.get("k").isEmpty());
        cache.delete("k"); // 不存在静默成功
    }

    @Test
    void ttlExpiryTreatsAsAbsent() throws InterruptedException {
        cache.set("k", "v", SHORT_TTL);
        Thread.sleep(80);
        assertTrue(cache.get("k").isEmpty());
    }

    /** 滑动窗口语义，每次自增刷新 ttl，窗口内连续计数，过期后归零重计 */
    @Test
    void incrementSlidingWindowCounting() throws InterruptedException {
        assertEquals(1, cache.increment("cnt", SHORT_TTL));
        assertEquals(2, cache.increment("cnt", SHORT_TTL));
        assertEquals(3, cache.increment("cnt", SHORT_TTL));
        Thread.sleep(80); // 超过 SHORT_TTL(50ms)，窗口过期
        assertEquals(1, cache.increment("cnt", SHORT_TTL)); // 过期后重新起计
    }

    @Test
    void approximateSizeCountsByPrefix() {
        cache.set("fms:nonce:a", "1", LONG_TTL);
        cache.set("fms:nonce:b", "1", LONG_TTL);
        cache.set("fms:ticket:c", "1", LONG_TTL);
        assertEquals(2, cache.approximateSize("fms:nonce:"));
        assertEquals(3, cache.approximateSize("fms:"));
    }
}
