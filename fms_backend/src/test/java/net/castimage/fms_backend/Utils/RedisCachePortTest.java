package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ExpirationOptions;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CachePort Redis 实现命令映射测试，mock StringRedisTemplate，不连真实 Redis，
 * 验证 8 方法落到的 Redis 原生命令与内存实现语义对齐（SETNX/INCR+EXPIRE/
 * INCR+PEXPIRE NX/ZSET+WATCH/MULTI/SCAN），与 InMemoryCachePortTest 互为对照。
 */
class RedisCachePortTest {

    private static final Duration TTL = Duration.ofSeconds(10);

    private StringRedisTemplate template;
    private ValueOperations<String, String> valueOps;
    private RedisOperations<String, String> ops;
    private ZSetOperations<String, String> zset;
    private RedisCachePort cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        ops = mock(RedisOperations.class);
        zset = mock(ZSetOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        when(ops.opsForZSet()).thenReturn(zset);
        when(template.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback<?>) invocation.getArgument(0)).execute(ops));
        cache = new RedisCachePort(template);
    }

    @Test
    void setIfAbsentMapsToSetnxWithTtl() {
        when(valueOps.setIfAbsent("k", "v", TTL)).thenReturn(true);
        assertTrue(cache.setIfAbsent("k", "v", TTL));
        when(valueOps.setIfAbsent("k", "v", TTL)).thenReturn(false);
        assertFalse(cache.setIfAbsent("k", "v", TTL)); // 已存在，SETNX 失败
    }

    @Test
    void setMapsToSetex() {
        cache.set("k", "v", TTL);
        verify(valueOps).set("k", "v", TTL);
    }

    @Test
    void getMapsToGet() {
        when(valueOps.get("k")).thenReturn("v");
        assertEquals(Optional.of("v"), cache.get("k"));
        when(valueOps.get("k")).thenReturn(null);
        assertTrue(cache.get("k").isEmpty());
    }

    @Test
    void deleteMapsToDel() {
        cache.delete("k");
        verify(template).delete("k");
    }

    /** 滚动窗口，INCR 自增 + EXPIRE 刷新 ttl（EXPIRE 失败仅窗口未刷新，计数偏严不偏松） */
    @Test
    void incrementMapsToIncrAndExpire() {
        when(valueOps.increment("cnt")).thenReturn(5L);
        assertEquals(5, cache.increment("cnt", TTL));
        verify(template).expire("cnt", TTL);
    }

    /** 固定窗口，INCR 自增 + PEXPIRE NX 仅首次设 ttl，已有 ttl 不续期 */
    @Test
    void incrementFixedWindowMapsToIncrAndExpireNx() {
        when(valueOps.increment("cnt")).thenReturn(1L);
        RedisConnection connection = mock(RedisConnection.class);
        RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(template.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });

        assertEquals(1, cache.incrementFixedWindow("cnt", TTL));
        verify(keyCommands).pExpire(aryEq("cnt".getBytes(StandardCharsets.UTF_8)),
                eq(TTL.toMillis()), eq(ExpirationOptions.Condition.NX));
    }

    /** 滑动窗口放行：WATCH 后读计数，未超限则 MULTI 写入时间戳并续期 */
    @Test
    void slidingWindowAllowedMapsWatchCountAndMulti() {
        when(zset.count(eq("sw"), anyDouble(), anyDouble())).thenReturn(4L);
        when(ops.exec()).thenReturn(List.of(1L, 1L, Boolean.TRUE));

        CachePort.SlidingWindowResult result = cache.checkSlidingWindow("sw", TTL, 5);

        assertTrue(result.allowed());
        assertEquals(5, result.count());
        assertEquals(0, result.retryAfterMillis());
        verify(ops).watch("sw");
        verify(ops).multi();
        verify(zset).removeRangeByScore(eq("sw"), anyDouble(), anyDouble());
        verify(zset).add(eq("sw"), anyString(), anyDouble());
        verify(ops).expire(eq("sw"), eq(TTL.plusSeconds(1)));
    }

    /** 滑动窗口拒绝：不进入 MULTI，按最旧记录剩余时长返回重试毫秒 */
    @Test
    void slidingWindowRejectedComputesRetry() {
        long now = System.currentTimeMillis();
        when(zset.count(eq("sw"), anyDouble(), anyDouble())).thenReturn(5L);
        doReturn(Set.of(new DefaultTypedTuple<>("m", (double) (now - 3_000))))
                .when(zset).rangeByScoreWithScores(anyString(), anyDouble(), anyDouble(),
                        anyLong(), anyLong());

        CachePort.SlidingWindowResult result = cache.checkSlidingWindow("sw", TTL, 5);

        verify(zset).rangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong());
        assertFalse(result.allowed());
        assertEquals(5, result.count());
        assertTrue(result.retryAfterMillis() >= 6_000 && result.retryAfterMillis() <= 7_500,
                "retry=" + result.retryAfterMillis());
        verify(ops).unwatch();
        verify(ops, never()).multi();
    }

    /** WATCH 冲突时 Spring Data Redis 的 EXEC 返回空列表，须重读重试并最终放行 */
    @Test
    void slidingWindowRetriesOnWatchConflict() {
        when(zset.count(eq("sw"), anyDouble(), anyDouble())).thenReturn(1L);
        when(ops.exec()).thenReturn(List.of(), List.of(1L, 1L, Boolean.TRUE));

        CachePort.SlidingWindowResult result = cache.checkSlidingWindow("sw", TTL, 5);

        assertTrue(result.allowed());
        assertEquals(2, result.count());
        verify(ops, times(2)).watch("sw");
    }

    /** 连续冲突耗尽重试次数，抛异常由调用方按 500 拒绝 */
    @Test
    void slidingWindowFailsClosedWhenConflictsExhausted() {
        when(zset.count(eq("sw"), anyDouble(), anyDouble())).thenReturn(1L);
        when(ops.exec()).thenReturn(List.of());

        assertThrows(IllegalStateException.class,
                () -> cache.checkSlidingWindow("sw", TTL, 5));
        verify(ops, times(10)).watch("sw");
    }

    /** Redis 异常不捕获，保持 fail-closed */
    @Test
    void slidingWindowPropagatesRedisFailure() {
        when(zset.count(eq("sw"), anyDouble(), anyDouble())).thenThrow(
                new RedisConnectionFailureException("redis down"));

        assertThrows(RedisConnectionFailureException.class,
                () -> cache.checkSlidingWindow("sw", TTL, 5));
    }

    @Test
    void approximateSizeMapsToScan() {
        when(template.execute(any(RedisCallback.class))).thenReturn(3L);
        assertEquals(3, cache.approximateSize("fms:nonce:"));
        when(template.execute(any(RedisCallback.class))).thenReturn(null);
        assertEquals(0, cache.approximateSize("fms:"));
    }
}
