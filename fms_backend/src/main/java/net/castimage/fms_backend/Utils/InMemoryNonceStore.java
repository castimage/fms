package net.castimage.fms_backend.Utils;


import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * nonce 防重放去重表内存实现，单实例缺省，ConcurrentHashMap，与 fms_rgw nonce.rs
 * 同构，ttl=重放窗口，容量超限 fail-closed 拒绝，防内存耗尽，多实例部署需切
 * {@link RedisNonceStore}（fms.session-store=redis）。
 */
@Slf4j
public class InMemoryNonceStore implements NonceStore {

    /** nonce 对应过期时刻（epoch millis），过期条目惰性清理 */
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    /** 去重窗口，重放窗口，毫秒 */
    private final long ttlMillis;

    /** 容量上限（fail-closed） */
    private final int capacity;

    /** 70% 档水位 WARN 节流窗口，毫秒，>70% 至多每窗口提示一次防刷屏，>90% 高水位每次清扫都提示 */
    private static final long WATER_WARN_THROTTLE_MS = 1_800_000L;

    /** 上次 70% 档水位 WARN 时刻，epoch millis，volatile 跨线程可见 */
    private volatile long lastWaterWarnMillis = 0;

    public InMemoryNonceStore(int capacity, long ttlMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("nonce capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
    }

    /** nonce 去重检查并登记，首次见到放行，ttl 内同 nonce 为重放拒绝，过期条目以新窗口重新登记 */
    @Override
    public boolean checkAndInsert(String nonce) {
        long now = System.currentTimeMillis();
        long expireAt = now + ttlMillis;
        Long prev = nonces.putIfAbsent(nonce, expireAt);
        if (prev != null) {
            if (prev > now) {
                // ttl 内同 nonce 原样重放，第二道闸拒绝，
                return false;
            }

            // 已过期，以新窗口重登记，CAS 原子替换，并发先行更新则取最新窗口，
            // 仅实际写入新窗口者放行，并发先行更新时本次返回 false（与 Redis SETNX
            // 逐条对齐，同 nonce 并发恒至多放行一个）
            boolean[] wrote = new boolean[1];
            nonces.computeIfPresent(nonce, (k, v) -> {
                if (v > now) {
                    return v; // 并发先行写入新窗口，本次未实际写入
                }
                wrote[0] = true;
                return expireAt;
            });
            return wrote[0];
        }

        // 容量上限 fail-closed，先清理过期条目，仍超限则撤销本次登记并拒绝
        if (nonces.size() > capacity) {
            cleanup(now);
            if (nonces.size() > capacity) {
                nonces.remove(nonce);
                log.warn("NonceStore 容量超限({}), 拒绝新 nonce(fail-closed)", capacity);
                return false;
            }
        }
        return true;
    }

    /** 移除过期条目，惰性清理入口，checkAndInsert 内亦会调用 */
    @Override
    public void cleanup() { // 惰性清理入口，
        cleanup(System.currentTimeMillis());
    }

    /**
     * 当前窗口条目数 / 容量 的水位比率，0.0 空 ~ 1.0 满，供定时水位监控与单测断言
     */
    @Override
    public double usageRatio() {
        return (double) nonces.size() / capacity;
    }

    /**
     * 水位检查，供测试直接调用，>70% WARN，日志含容量/当前数/比率，70% 档节流防刷屏，
     * >90% 高水位提升 WARN 频率，每次周期清扫都提示，水位回落后重置节流，下次越阈立即提示
     */
    void checkWaterLevel() {
        double ratio = usageRatio();
        String pct = String.format("%.2f", ratio);
        long now = System.currentTimeMillis();
        if (ratio > 0.9) {
            // 高水位，每次清扫都 WARN，提升频率，提示容量推导偏低或存在异常流量
            log.warn("NonceStore 高水位({}): 容量={} 当前={}（容量=显式配置或 peak_qps×window）",
                    pct, capacity, nonces.size());
            lastWaterWarnMillis = now;
        } else if (ratio > 0.7) {
            if (now - lastWaterWarnMillis >= WATER_WARN_THROTTLE_MS) {
                log.warn("NonceStore 水位偏高({}): 容量={} 当前={}（容量=显式配置或 peak_qps×window）",
                        pct, capacity, nonces.size());
                lastWaterWarnMillis = now;
            }
        } else {
            // 水位回落，重置节流窗口，下次越阈立即提示
            lastWaterWarnMillis = 0;
        }
    }

    /**
     * 定时兜底清理 + 水位监控，周期 fms.cache-cleanup-interval-ms 默认 5 分钟，与
     * AuthServiceImpl 内存票据/黑名单清扫同周期，先清过期条目再测水位。
     * 容量按显式配置或 peak_qps × window 推导，见 effectiveCapacity，
     * 水位为动态容量的观测窗口，接近上限说明容量推导偏低或存在异常流量，需扩容或排查
     */
    @Scheduled(initialDelayString = "${fms.cache-cleanup-interval-ms:300000}",
            fixedDelayString = "${fms.cache-cleanup-interval-ms:300000}")
    public void scheduledCleanup() {
        cleanup();
        checkWaterLevel();
    }

    private void cleanup(long now) {
        Iterator<Map.Entry<String, Long>> it = nonces.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
            }
        }
    }
}
