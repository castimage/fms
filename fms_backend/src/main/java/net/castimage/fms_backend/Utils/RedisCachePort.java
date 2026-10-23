package net.castimage.fms_backend.Utils;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import lombok.RequiredArgsConstructor;
import java.time.Duration;
import java.util.Optional;

/**
 * CachePort Redis 实现，fms.session-store=redis，多实例共享存储，
 * 全部命令经 StringRedisTemplate 落到共享 Redis，nonce 防重放去重、2fa 票据/
 * 找回票据/totp pending、totp 失败计数、令牌黑名单任意后端实例写入全局可见。
 * 原子语义由 Redis 原生命令保证（SETNX/INCR/SCAN），与内存实现逐条对齐，见 CachePort。
 *
 * 故障语义，fail-closed，安全系统不降级，任一命令执行失败异常自然上抛（RuntimeException），
 * 由调用方按 500 拒绝请求，nonce 去重宁可拒不可放，票据宁可无效不可复用，
 * redis 不可达属部署错误，compose healthcheck + 启动 fail-fast 暴露。
 */
@RequiredArgsConstructor
public class RedisCachePort implements CachePort {

    private final StringRedisTemplate redisTemplate;


    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        // SETNX + EXPIRE 一步到位，Spring Data Redis 带 Duration 重载，Redis 6.2+ 原子语义
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, ttl));
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        // 覆盖写入 + ttl（黑名单/pending secret 幂等覆盖语义）
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key); // key 不存在静默成功
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long value = redisTemplate.opsForValue().increment(key);

        // 两步非严格原子，INCR 成功后 EXPIRE 失败仅意味着滑动窗口未刷新，计数已 +1，
        // 失败计数偏严不偏松，多记一次比漏记一次安全，totp 连续失败锁定方向正确，安全取舍
        redisTemplate.expire(key, ttl);
        return value == null ? 0L : value;
    }

    @Override
    public long approximateSize(String keyPrefix) {
        // SCAN 统计，O(N)，仅水位监控与容量推导低频调用，非精确计数，
        // 与内存实现"含前缀条目数"同为近似口径。Cursor 用完即关防连接泄漏
        Long count = redisTemplate.execute((RedisCallback<Long>) conn -> {
            try (Cursor<byte[]> cursor = conn.keyCommands().scan(
                    ScanOptions.scanOptions().match(keyPrefix + "*").count(1000).build())) {
                long n = 0;
                while (cursor.hasNext()) {
                    cursor.next();
                    n++;
                }
                return n;
            }
        });
        return count == null ? 0L : count;
    }
}
