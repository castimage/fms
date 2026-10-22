package net.castimage.fms_backend.Utils;


import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * nonce 防重放去重表 Redis 实现（fms.session-store=redis，多实例共享存储）：
 * checkAndInsert = CachePort.setIfAbsent("fms:nonce:" + nonce, TTL=重放窗口)，
 * 由 Redis 原子 SETNX 保证并发去重正确性，任意实例写入全局可见——多实例部署下
 * 网关签名 nonce 的第二道闸语义与单实例一致。
 *
 * 与内存版的关键差异（取舍）：分布式场景无法原子地做"容量检查+写入"
 * （approximateSize 的 SCAN 与 setIfAbsent 之间必有竞窗），故舍弃 fail-closed
 * 容量闸——防内存耗尽改由 Redis 自身内存策略（maxmemory 淘汰）兜底，容量仅作
 * 水位监控口径（usageRatio 分母）。拒绝语义不变：TTL 内同 nonce 恒拒绝，仅
 * 极端内存压力下由 Redis 逐出旧键兜底，方向安全。
 */
@Slf4j
public class RedisNonceStore implements NonceStore {

    /** 共享存储访问端口（构造注入，由 WebConfig 按 fms.session-store 装配） */
    private final CachePort cache;

    /** 容量（仅水位监控分母，非 fail-closed 闸门） */
    private final int capacity;

    /** 去重窗口（重放窗口，毫秒） */
    private final long ttlMillis;

    /** 70% 档水位 WARN 节流窗口（毫秒），与内存版同口径 */
    private static final long WATER_WARN_THROTTLE_MS = 1_800_000L;

    /** 上次 70% 档水位 WARN 时刻（epoch millis，volatile 跨线程可见） */
    private volatile long lastWaterWarnMillis = 0;

    public RedisNonceStore(CachePort cache, int capacity, long ttlMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("nonce capacity must be positive: " + capacity);
        }
        this.cache = cache;
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
    }

    /** nonce 去重检查并登记：SETNX 原子去重（去重键按 CachePort 命名空间 fms:nonce: 收敛） */
    @Override
    public boolean checkAndInsert(String nonce) {
        return cache.setIfAbsent("fms:nonce:" + nonce, "1", Duration.ofMillis(ttlMillis));
    }

    /**
     * 当前窗口条目数 / 容量 的水位比率（0.0 空 ~ 1.0 满）：approximateSize 为 SCAN 近似值
     * （O(N)，仅定时水位监控低频调用），故水位为观测口径而非精确计数；分母=动态推导容量
     */
    @Override
    public double usageRatio() {
        return (double) cache.approximateSize("fms:nonce:") / capacity;
    }

    /** 空操作：Redis TTL 惰性删除 + 主动过期回收，无兜底清理需求（接口契约对齐） */
    @Override
    public void cleanup() {
        // TTL 自动过期（惰性删除 + 主动淘汰），无需兜底清理
    }

    /**
     * 定时水位监控（周期 fms.cache-cleanup-interval-ms 默认 5 分钟，与内存版同周期）：
     * 仅观测不干预——Redis 版无容量闸，高水位提示容量推导偏低或存在异常流量，
     * 需扩容 Redis 或排查攻击（水位回落后重置节流，下次越阈立即提示）
     */
    @Scheduled(initialDelayString = "${fms.cache-cleanup-interval-ms:300000}",
            fixedDelayString = "${fms.cache-cleanup-interval-ms:300000}")
    public void scheduledWaterMonitor() {
        checkWaterLevel();
    }

    private void checkWaterLevel() {
        double ratio = usageRatio();
        String pct = String.format("%.2f", ratio);
        long now = System.currentTimeMillis();
        if (ratio > 0.9) {
            // 高水位：每次周期都 WARN（提升频率，提示容量推导偏低或存在异常流量）
            log.warn("RedisNonceStore 高水位({}): 容量={} 当前≈{}（SCAN 近似；容量=显式配置或 peak_qps×window）",
                    pct, capacity, (long) (ratio * capacity));
            lastWaterWarnMillis = now;
        } else if (ratio > 0.7) {
            if (now - lastWaterWarnMillis >= WATER_WARN_THROTTLE_MS) {
                log.warn("RedisNonceStore 水位偏高({}): 容量={} 当前≈{}（容量=显式配置或 peak_qps×window）",
                        pct, capacity, (long) (ratio * capacity));
                lastWaterWarnMillis = now;
            }
        } else {
            // 水位回落：重置节流窗口，下次越阈立即提示
            lastWaterWarnMillis = 0;
        }
    }
}
