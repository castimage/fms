package net.castimage.fms_backend.integration;

import net.castimage.fms_backend.Utils.RedisCachePort;
import net.castimage.fms_backend.Utils.UuidV7;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 滑动窗口 Redis 实现真实并发测试，需设置 FMS_TEST_REDIS_URL（如
 * redis://:密码@127.0.0.1:6379/0），未设置时跳过。验证 WATCH/MULTI 事务在
 * 同一 key 并发下不超额放行、不因冲突耗尽重试，测试键用后删除。
 */
class RedisSlidingWindowIT {

    @Test
    void concurrentChecksNeverExceedLimit() throws Exception {
        String url = System.getenv("FMS_TEST_REDIS_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未设置 FMS_TEST_REDIS_URL，跳过真实 Redis 测试");

        LettuceConnectionFactory factory = connectionFactory(url);
        factory.afterPropertiesSet();
        factory.start();
        try {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            RedisCachePort cache = new RedisCachePort(template);

            String key = "fms:test:ratelimit:it:" + UuidV7.next();
            int threads = 8;
            int perThread = 5;
            int limit = 5;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<int[]>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<int[]> task = () -> {
                    start.await();
                    int[] counts = new int[3];
                    for (int j = 0; j < perThread; j++) {
                        try {
                            boolean allowed = cache.checkSlidingWindow(
                                    key, java.time.Duration.ofSeconds(30), limit).allowed();
                            counts[allowed ? 0 : 1]++;
                        } catch (RuntimeException e) {
                            counts[2]++;
                        }
                    }
                    return counts;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            int allowed = 0;
            int rejected = 0;
            int errors = 0;
            for (Future<int[]> future : futures) {
                int[] counts = future.get(60, TimeUnit.SECONDS);
                allowed += counts[0];
                rejected += counts[1];
                errors += counts[2];
            }
            pool.shutdownNow();

            assertEquals(limit, allowed, "窗口内放行数应恰为 limit");
            assertEquals(threads * perThread - limit, rejected);
            assertEquals(0, errors, "事务冲突不应耗尽重试");
            assertEquals(limit, template.opsForZSet().zCard(key));
            template.delete(key);
        } finally {
            factory.destroy();
        }
    }

    private static LettuceConnectionFactory connectionFactory(String url) {
        URI uri = URI.create(url);
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            String password = userInfo.startsWith(":") ? userInfo.substring(1) : userInfo;
            config.setPassword(RedisPassword.of(password));
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            config.setDatabase(Integer.parseInt(path.substring(1)));
        }
        return new LettuceConnectionFactory(config);
    }
}
