package net.castimage.fms_backend.Utils;

/**
 * nonce 防重放去重表抽象（与 fms_rgw nonce.rs 同构）：TTL=重放窗口，同 nonce 窗口内
 * 重放拒绝（第二道闸）。双实现按 fms.session-store 切换：
 *
 * {@link InMemoryNonceStore}（缺省，单实例）：ConcurrentHashMap，容量超限
 * fail-closed 拒绝（防内存耗尽），@Scheduled 兜底清理 + 水位监控；
 * {@link RedisNonceStore}（fms.session-store=redis，多实例）：CachePort 共享存储，
 * nonce 全局可见；TTL 自然过期，无精确容量闸（分布式无法原子全局计数，属取舍）。
 *
 * 方法语义由 GatewaySignatureFilter（时间窗+签名校验之后、采信身份之前调用）依赖，
 * 两实现行为必须一致。容量推导逻辑为接口静态方法（见 {@link #effectiveCapacity}）。
 */
public interface NonceStore {

    /**
     * nonce 去重检查并登记：首次见到放行；TTL 内同 nonce 为重放拒绝；过期条目以新窗口重新登记
     */
    boolean checkAndInsert(String nonce);

    /**
     * 当前窗口条目数 / 容量 的水位比率（0.0 空 ~ 1.0 满；供定时水位监控与单测断言）
     */
    double usageRatio();

    /**
     * 移除过期条目（内存实现=惰性清理入口；Redis 实现=空操作，TTL 自动过期）
     */
    void cleanup();

    /**
     * 有效容量推导：显式 nonce-capacity（>0）优先（兼容既有配置）；否则按期望峰值 QPS ×
     * 重放窗口动态计算（默认 1000 × 300 = 300000）。6 万 QPS 瞬时请求可击穿
     * 10000 固定容量导致合法请求被 fail-closed 拒绝，动态容量按峰值预期推导消除缺口
     *
     * @param nonceCapacity   显式配置容量（0 = 未配置，走动态推导）
     * @param noncePeakQps    期望峰值 QPS（默认 1000）
     * @param replayWindowSecs 防重放窗口（秒，默认 300）
     * @return 有效容量（恒 > 0；推导结果封顶 Integer.MAX_VALUE）
     */
    static int effectiveCapacity(int nonceCapacity, int noncePeakQps, long replayWindowSecs) {
        if (nonceCapacity > 0) {
            return nonceCapacity;
        }
        return (int) Math.min((long) noncePeakQps * replayWindowSecs, Integer.MAX_VALUE);
    }
}
