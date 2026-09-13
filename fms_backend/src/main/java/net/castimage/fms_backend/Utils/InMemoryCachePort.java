package net.castimage.fms_backend.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CachePort 内存实现，fms.session-store 缺省=in-memory，保持单实例既有行为，
 * ConcurrentHashMap + 过期时刻（epoch millis），key 命名空间与 Redis 实现一一对应，fms:nonce: 等前缀，防跨实现漂移，见 CachePort 契约。过期条目惰性清理，读路径只判过期不删除，避免写放大，@Scheduled 兜底清扫回收长尾。
 *
 * 线程安全性，所有写路径走 CHM 原子方法（putIfAbsent/compute/computeIfPresent），
 * 并发下不丢写、不覆盖并发先行的新窗口，与 Redis 原子命令（SETNX/INCR/LPOP）语义对齐。
 */
public class InMemoryCachePort implements CachePort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCachePort.class);

    /** 条目，值 + 过期时刻，epoch millis，超过即视为不存在 */
    private record Entry(String value, long expireAtMillis) {
    }

    /** 全量存储（key 到条目），按 CachePort 命名空间前缀区分业务域 */
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        long now = System.currentTimeMillis();
        long expireAt = now + ttl.toMillis();
        Entry prev = store.putIfAbsent(key, new Entry(value, expireAt));
        if (prev == null) {
            return true;
        }
        if (prev.expireAtMillis() > now) {
            // ttl 内已存在，不改值、不续 ttl（nonce 重放拒绝语义，等价 Redis SETNX 失败）
            return false;
        }

        // 已过期，视为不存在，原子重登记新窗口，compute 兜住 putIfAbsent 与重登记之间的
        // 并发窗，并发先行写入新窗口时保留其值，且本次未实际写入者返回 false，与 Redis SETNX 逐条对齐，同 nonce 并发恒至多放行一个
        boolean[] wrote = new boolean[1];
        store.compute(key, (k, existing) -> {
            if (existing != null && existing.expireAtMillis() > now) {
                return existing; // 并发先行写入新窗口，本次未实际写入，放行权归先行者
            }
            wrote[0] = true;
            return new Entry(value, expireAt);
        });
        return wrote[0];
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        // 覆盖写入 + ttl（黑名单/pending secret 幂等覆盖语义）
        store.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = store.get(key);
        if (entry == null || entry.expireAtMillis() <= System.currentTimeMillis()) {
            return Optional.empty(); // 过期条目视为不存在
        }
        return Optional.of(entry.value());
    }

    @Override
    public void delete(String key) {
        store.remove(key); // 不存在静默成功
    }

    @Override
    public long increment(String key, Duration ttl) {
        long now = System.currentTimeMillis();
        long expireAt = now + ttl.toMillis();
        long[] holder = new long[1];

        // compute 原子自增，过期/不存在按 0 起计，首写 1，每次调用刷新过期时刻，滑动窗口
        store.compute(key, (k, existing) -> {
            long base = 0;
            if (existing != null && existing.expireAtMillis() > now) {
                base = Long.parseLong(existing.value());
            }
            holder[0] = base + 1;
            return new Entry(Long.toString(holder[0]), expireAt);
        });
        return holder[0];
    }

    @Override
    public long approximateSize(String keyPrefix) {
        // 含前缀条目数，水位监控与容量推导口径，非精确计数的场景不存在于内存实现，全表可见
        long count = 0;
        for (String key : store.keySet()) {
            if (key.startsWith(keyPrefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 定时兜底清理，周期 fms.cache-cleanup-interval-ms 默认 5 分钟，与 NonceStore 同周期，
     * 读路径只判过期不删，无写放大，过期条目靠本周期清扫回收，防长尾堆积
     */
    @Scheduled(initialDelayString = "${fms.cache-cleanup-interval-ms:300000}",
            fixedDelayString = "${fms.cache-cleanup-interval-ms:300000}")
    public void scheduledCleanup() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<String, Entry>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expireAtMillis() <= now) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("InMemoryCachePort 定时清理过期条目: {}", removed);
        }
    }
}
