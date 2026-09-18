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
 * {@link #increment}：原子自增并返回自增后值；首次写入设 TTL（TTL 内过期自动归零——
 * TOTP 连续失败计数滑动窗口语义，fail 时每次刷新 TTL 即滚动窗口）；
 * {@link #approximateSize}：近似条目数（内存=全表 size；redis=SCAN 统计，仅水位监控
 * 与容量推导用，非精确计数）。
 *
 * key 命名空间（redis 键与内存键一一对应，防跨实现漂移）：
 * fms:nonce:、fms:ticket:、fms:recovery:、fms:pending:、fms:totpfail:、fms:blacklist:
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

    /** 原子自增（返回自增后值）；首次写入设 TTL，此后每次调用刷新 TTL（滑动窗口语义） */
    long increment(String key, Duration ttl);

    /**
     * 近似条目数（prefix 过滤；水位监控与容量推导用，非精确计数）：
     * 内存实现=含前缀条目 size；redis 实现=SCAN 统计（O(N)，调用频率须低）
     */
    long approximateSize(String keyPrefix);
}
