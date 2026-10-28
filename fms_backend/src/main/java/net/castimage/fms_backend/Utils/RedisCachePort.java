package net.castimage.fms_backend.Utils;

import org.springframework.data.redis.connection.ExpirationOptions;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * CachePort Redis 实现，fms.session-store=redis，多实例共享存储，
 * 全部命令经 StringRedisTemplate 落到共享 Redis，nonce 防重放去重、2fa 票据/
 * 找回票据/totp pending、totp 失败计数、令牌黑名单任意后端实例写入全局可见。
 * 原子语义由 Redis 原生命令与 WATCH/MULTI 事务保证（SETNX/INCR/乐观事务），
 * 与内存实现逐条对齐，见 CachePort。
 *
 * 故障语义，fail-closed，安全系统不降级，任一命令执行失败异常自然上抛（RuntimeException），
 * 由调用方按 500 拒绝请求，nonce 去重宁可拒不可放，票据宁可无效不可复用，
 * redis 不可达属部署错误，compose healthcheck + 启动 fail-fast 暴露。
 */
@Slf4j
@RequiredArgsConstructor
public class RedisCachePort implements CachePort {

    /** 滑动窗口事务最大尝试次数，超限按未预期异常拒绝（fail-closed） */
    private static final int MAX_SLIDING_ATTEMPTS = 10;

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

        // 两步非严格原子，INCR 成功后 EXPIRE 失败仅意味着滚动窗口未刷新，计数已 +1，
        // 失败计数偏严不偏松，多记一次比漏记一次安全，totp 连续失败锁定方向正确，安全取舍
        redisTemplate.expire(key, ttl);
        return value == null ? 0L : value;
    }

    @Override
    public long incrementFixedWindow(String key, Duration ttl) {
        Long value = redisTemplate.opsForValue().increment(key);

        // EXPIRE NX 仅在无过期时间时设置，首次自增开启窗口，窗口内自增不续期；
        // 若首次 EXPIRE 失败，后续自增仍会补设，不会留下无过期键
        redisTemplate.execute((RedisCallback<Boolean>) conn ->
                conn.keyCommands().pExpire(key.getBytes(StandardCharsets.UTF_8), ttl.toMillis(),
                        ExpirationOptions.Condition.NX));
        return value == null ? 0L : value;
    }

    @Override
    public SlidingWindowResult checkSlidingWindow(String key, Duration window, long limit) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window 必须为正");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit 不能为负");
        }
        long windowMillis = window.toMillis();

        // WATCH 监视键后读取窗口计数，放行时 MULTI 写入并清理旧记录；
        // EXEC 返回空列表或 null 表示期间被其它实例改动，重新读取后重试
        SlidingWindowResult result = redisTemplate.execute(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> SlidingWindowResult execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                for (int attempt = 1; attempt <= MAX_SLIDING_ATTEMPTS; attempt++) {
                    ops.watch(key);
                    long now = System.currentTimeMillis();
                    double minScore = now - windowMillis + 1;
                    Long count = ops.opsForZSet().count(key, minScore, Double.MAX_VALUE);
                    long windowCount = count == null ? 0L : count;
                    if (windowCount >= limit) {
                        Double oldest = oldestScore(ops, key, minScore);
                        ops.unwatch();
                        long retry = oldest == null ? 1L
                                : Math.max(1L, oldest.longValue() + windowMillis - now);
                        return new SlidingWindowResult(false, windowCount, retry);
                    }

                    ops.multi();
                    ops.opsForZSet().removeRangeByScore(key, 0, now - windowMillis);
                    ops.opsForZSet().add(key, UuidV7.next().toString(), now);
                    ops.expire(key, Duration.ofMillis(windowMillis + 1000));
                    // Spring Data Redis 4.1 在 WATCH 冲突时 EXEC 返回空列表而非 null，
                    // 二者都按冲突处理，避免把未写入当成放行
                    List<Object> exec = ops.exec();
                    if (exec != null && !exec.isEmpty()) {
                        return new SlidingWindowResult(true, windowCount + 1, 0L);
                    }
                    sleepBeforeRetry(attempt);
                }
                log.warn("滑动窗口乐观事务冲突重试耗尽 key={}", key);
                throw new IllegalStateException("滑动窗口乐观事务冲突重试耗尽");
            }
        });
        if (result == null) {
            throw new IllegalStateException("滑动窗口乐观事务未执行");
        }
        return result;
    }

    /** 事务冲突后短暂让出，降低同 key 并发重读再次碰撞的概率 */
    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Duration.ofNanos(150_000L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("滑动窗口重试被中断", e);
        }
    }

    /** 窗口内最旧记录的分数（毫秒时间戳），无记录返回 null */
    private static Double oldestScore(RedisOperations<String, String> ops, String key, double minScore) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                ops.opsForZSet().rangeByScoreWithScores(key, minScore, Double.MAX_VALUE, 0, 1);
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }
        return tuples.iterator().next().getScore();
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
