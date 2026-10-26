package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CachePort 内存实现契约测试，多实例共享状态的单实例基线，8 方法语义逐条验证，
 * SETNX 语义（setIfAbsent）、滚动窗口自增、固定窗口自增、精确滑动窗口、
 * ttl 过期视为不存在。
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

    /** 滚动 ttl 语义，每次自增刷新 ttl，窗口内连续计数，过期后归零重计 */
    @Test
    void incrementRollingTtlCounting() throws InterruptedException {
        assertEquals(1, cache.increment("cnt", SHORT_TTL));
        assertEquals(2, cache.increment("cnt", SHORT_TTL));
        assertEquals(3, cache.increment("cnt", SHORT_TTL));
        Thread.sleep(80); // 超过 SHORT_TTL(50ms)，窗口过期
        assertEquals(1, cache.increment("cnt", SHORT_TTL)); // 过期后重新起计
    }

    /** 固定窗口语义，首次自增设 ttl，窗口内自增不续期，ttl 到期整体归零 */
    @Test
    void incrementFixedWindowDoesNotRefreshTtl() throws InterruptedException {
        Duration ttl = Duration.ofMillis(600);
        assertEquals(1, cache.incrementFixedWindow("fixed", ttl));
        Thread.sleep(200);
        assertEquals(2, cache.incrementFixedWindow("fixed", ttl)); // 窗口内自增，不续期
        Thread.sleep(500); // 累计 700ms，超过首次设置的 600ms
        assertEquals(1, cache.incrementFixedWindow("fixed", ttl)); // 新窗口重新起计
    }

    /** 滑动窗口在 limit 内放行，超出即拒绝且给出重试毫秒 */
    @Test
    void slidingWindowAllowsUpToLimitAndRejectsNext() {
        Duration window = Duration.ofSeconds(2);
        for (int i = 1; i <= 3; i++) {
            CachePort.SlidingWindowResult r = cache.checkSlidingWindow("sw", window, 3);
            assertTrue(r.allowed());
            assertEquals(i, r.count());
        }
        CachePort.SlidingWindowResult rejected = cache.checkSlidingWindow("sw", window, 3);
        assertFalse(rejected.allowed());
        assertEquals(3, rejected.count());
        assertTrue(rejected.retryAfterMillis() >= 1 && rejected.retryAfterMillis() <= 2000);
    }

    /** 被拒请求不写入窗口，持续拒绝不会把计数推高 */
    @Test
    void slidingWindowRejectedRequestsNotRecorded() {
        Duration window = Duration.ofSeconds(2);
        assertTrue(cache.checkSlidingWindow("sw", window, 2).allowed());
        assertTrue(cache.checkSlidingWindow("sw", window, 2).allowed());
        for (int i = 0; i < 5; i++) {
            CachePort.SlidingWindowResult r = cache.checkSlidingWindow("sw", window, 2);
            assertFalse(r.allowed());
            assertEquals(2, r.count());
        }
    }

    /** 窗口外的旧记录被剔除，安静一个窗口后重新放行 */
    @Test
    void slidingWindowExpiresOldEntries() throws InterruptedException {
        Duration window = Duration.ofMillis(800);
        assertTrue(cache.checkSlidingWindow("sw", window, 2).allowed());
        assertTrue(cache.checkSlidingWindow("sw", window, 2).allowed());
        assertFalse(cache.checkSlidingWindow("sw", window, 2).allowed());
        Thread.sleep(1000);
        CachePort.SlidingWindowResult first = cache.checkSlidingWindow("sw", window, 2);
        assertTrue(first.allowed());
        assertEquals(1, first.count());
    }

    /** 精确滑动窗口：窗口内补发不会因固定窗口换窗而突刺放行 */
    @Test
    void slidingWindowHasNoFixedBoundaryBurst() throws InterruptedException {
        Duration window = Duration.ofSeconds(2);
        for (int i = 0; i < 10; i++) {
            assertTrue(cache.checkSlidingWindow("sw", window, 10).allowed());
        }
        Thread.sleep(1000); // 仍在同一窗口内
        for (int i = 0; i < 10; i++) {
            assertFalse(cache.checkSlidingWindow("sw", window, 10).allowed(), "窗口内不得再放行");
        }
        Thread.sleep(1200); // 累计 2200ms，首批记录全部出窗
        for (int i = 0; i < 10; i++) {
            assertTrue(cache.checkSlidingWindow("sw", window, 10).allowed());
        }
        assertFalse(cache.checkSlidingWindow("sw", window, 10).allowed());
    }

    /** limit=0 视为全部拒绝，避免配置错误导致无上限放行 */
    @Test
    void slidingWindowZeroLimitRejectsAll() {
        CachePort.SlidingWindowResult r = cache.checkSlidingWindow("sw", Duration.ofSeconds(2), 0);
        assertFalse(r.allowed());
        assertEquals(0, r.count());
        assertEquals(1, r.retryAfterMillis());
    }

    @Test
    void approximateSizeCountsByPrefix() {
        cache.set("fms:nonce:a", "1", LONG_TTL);
        cache.set("fms:nonce:b", "1", LONG_TTL);
        cache.set("fms:ticket:c", "1", LONG_TTL);
        assertEquals(2, cache.approximateSize("fms:nonce:"));
        assertEquals(3, cache.approximateSize("fms:"));
    }

    /** 滑动窗口键纳入水位统计，delete 同时清除 */
    @Test
    void approximateSizeIncludesSlidingKeysAndDelete() {
        cache.set("fms:nonce:a", "1", LONG_TTL);
        assertTrue(cache.checkSlidingWindow("fms:ratelimit:sw:login:1.2.3.4",
                Duration.ofSeconds(10), 5).allowed());
        assertEquals(1, cache.approximateSize("fms:ratelimit:sw:"));
        assertEquals(2, cache.approximateSize("fms:"));
        cache.delete("fms:ratelimit:sw:login:1.2.3.4");
        assertEquals(0, cache.approximateSize("fms:ratelimit:sw:"));
    }

    /** 定时清理移除过期滑动窗口，避免空闲键长期滞留 */
    @Test
    void cleanupRemovesIdleSlidingWindows() throws InterruptedException {
        assertTrue(cache.checkSlidingWindow("fms:ratelimit:sw:login:1.2.3.4",
                Duration.ofMillis(50), 5).allowed());
        Thread.sleep(80);
        cache.scheduledCleanup();
        assertEquals(0, cache.approximateSize("fms:"));
    }
}
