package net.castimage.fms_backend.Utils;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存端口（CachePort）：多实例共享状态抽象——in-memory | redis 双实现，
 * fms.session-store 配置切换（默认 in-memory 保持单实例行为；redis 为多实例
 * 共享存储：nonce 防重放去重、2FA 票据/找回票据/TOTP pending、TOTP 失败计数、
 * 令牌黑名单全部收敛于此，任意后端实例写入对全部实例可见）。
 * 2FA 备用恢复码由 um.backup_codes + auth.* 闸口承载，不走本端口。
 *
 * 语义契约（两种实现必须逐条一致，AuthServiceImpl/NonceStore 依赖）：
 *
 * {@link #setIfAbsent}：原子"不存在才写入"并设 TTL；已存在返回 false 不改值——
 * nonce 去重（第二道闸）与票据登记共用；
 * {@link #set}：覆盖写入 + TTL（黑名单/pending secret）；
 * {@link #get} / {@link #delete}：按 key 读/删（key 不存在恒 Optional.empty / 静默成功）；
 * {@link #increment}：原子自增并返回自增后值；每次调用刷新 TTL（TOTP 连续失败计数
 * 滚动窗口语义，fail 时续期，成功时由调用方删除）；
 * {@link #incrementFixedWindow}：原子自增并返回自增后值；首次写入设 TTL，后续自增
 * 不续期，TTL 到期整体归零（固定窗口限流语义，窗口从首次请求起算）；
 * {@link #checkSlidingWindow}：按时间戳精确判定滑动窗口，窗口内已达 limit 则拒绝且
 * 不记录本次，放行才写入时间戳（滑动窗口限流语义，任意窗口内不超过 limit）；
 * {@link #approximateSize}：近似条目数（内存=全表 size；redis=SCAN 统计，仅水位监控
 * 与容量推导用，非精确计数）。
 *
 * key 命名空间（redis 键与内存键一一对应，防跨实现漂移）：
 * fms:nonce:、fms:ticket:、fms:recovery:、fms:pending:、fms:totpfail:、fms:blacklist:、
 * fms:alert:、fms:ratelimit:sw:（滑动窗口限流，固定窗口计数键为 fms:ratelimit:）
 *
 * 安全告警去重标记也用 setIfAbsent 抢占：多实例同跑定时告警，抢到标记的实例才发站内信，
 * 恢复时 delete 标记，故标记须与其他键同样具备原子写入与 TTL 语义。
 *
 * 故障语义（fail-closed，安全系统不降级）：redis 实现任一命令执行失败抛
 * RuntimeException，由调用方按 500 拒绝请求——nonce 去重宁可拒不可放，票据宁可
 * 无效不可复用；redis 不可达属部署错误，compose healthcheck + 启动 fail-fast 暴露。
 */
public interface CachePort {

    /** 原子"不存在才写入"并设 TTL；已存在返回 false（不改值、不续 TTL） */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /** 覆盖写入并设 TTL（幂等，恒成功；黑名单/pending 等覆盖语义） */
    void set(String key, String value, Duration ttl);

    /** 按 key 读取；不存在返回 Optional.empty（过期条目视为不存在） */
    Optional<String> get(String key);

    /** 删除 key（不存在静默成功） */
    void delete(String key);

    /** 原子自增（返回自增后值）；首次写入设 TTL，此后每次调用刷新 TTL（滚动窗口语义） */
    long increment(String key, Duration ttl);

    /** 原子自增（返回自增后值）；首次写入设 TTL，后续自增不续期，TTL 到期整体归零（固定窗口） */
    long incrementFixedWindow(String key, Duration ttl);

    /**
     * 精确滑动窗口限流检查：以时间戳记录窗口内每次放行，先剔除早于 now-window 的记录，
     * 窗口内已放行数达到 limit 则拒绝且不记录本次，否则记录本次时间戳并放行。
     * 同一 key 的 window 须保持一致。
     *
     * 返回 allowed 时 count 为写入后计数，拒绝时 count 为当前计数（不含本次）；
     * retryAfterMillis 拒绝时为最旧记录 + window - now（最少 1），放行时为 0。
     * 两种实现语义逐条一致；redis 实现以 WATCH/MULTI 保证判定与写入原子，
     * 事务冲突重试 10 次后抛异常，由调用方按 500 拒绝（fail-closed）。
     *
     * @param key    限流键，调用方负责命名空间
     * @param window 滑动窗口时长，须为正
     * @param limit  窗口内允许上限，可为 0（全部拒绝）
     * @return 判定结果
     */
    SlidingWindowResult checkSlidingWindow(String key, Duration window, long limit);

    /** 滑动窗口判定结果，字段含义见 {@link #checkSlidingWindow} */
    record SlidingWindowResult(boolean allowed, long count, long retryAfterMillis) {
    }

    /**
     * 近似条目数（prefix 过滤；水位监控与容量推导用，非精确计数）：
     * 内存实现=含前缀条目 size；redis 实现=SCAN 统计（O(N)，调用频率须低）
     */
    long approximateSize(String keyPrefix);
}
