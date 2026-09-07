package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CachePort Redis 实现命令映射测试，mock StringRedisTemplate，不连真实 Redis，
 * 验证 6 方法落到的 Redis 原生命令与内存实现语义对齐（SETNX/INCR+EXPIRE/SCAN），
 * 与 InMemoryCachePortTest 互为对照。
 */
class RedisCachePortTest {

    private static final Duration TTL = Duration.ofSeconds(10);

    private StringRedisTemplate template;
    private ValueOperations<String, String> valueOps;
    private RedisCachePort cache;

    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
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

    /** 滑动窗口，INCR 自增 + EXPIRE 刷新 ttl（EXPIRE 失败仅窗口未刷新，计数偏严不偏松） */
    @Test
    void incrementMapsToIncrAndExpire() {
        when(valueOps.increment("cnt")).thenReturn(5L);
        assertEquals(5, cache.increment("cnt", TTL));
        verify(template).expire("cnt", TTL);
    }

    @Test
    void approximateSizeMapsToScan() {
        when(template.execute(any(RedisCallback.class))).thenReturn(3L);
        assertEquals(3, cache.approximateSize("fms:nonce:"));
        when(template.execute(any(RedisCallback.class))).thenReturn(null);
        assertEquals(0, cache.approximateSize("fms:"));
    }
}
