package net.castimage.fms_backend.Service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.Mapper.AlertMapper;
import net.castimage.fms_backend.Utils.CachePort;
import net.castimage.fms_backend.Utils.SandboxRoleHint;
import net.castimage.fms_backend.config.FmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import java.time.Duration;
import java.util.UUID;

/**
 * 安全告警检测，定时调度，以 fms_app 直连通道经 SECURITY DEFINER 函数检测，无会话身份，
 *
 * 暴力破解聚合，窗口内同 ip 登录失败超阈值则发 admin 站内信，同 ip 30 分钟内去重，
 * 审计链篡改，链断，broken_at 非空则发 admin 站内信，标记期内不重复，
 * 主从复制，期望从库数缺失/复制状态降级/延迟超阈值则发 admin 站内信，冷却期去重，
 * nonce 水位，Redis 版非精确容量闸，按近似条目数/期望容量比率 WARN，每周期，不刷屏。
 *
 * 三类去重标记均落 CachePort，多实例经 setIfAbsent 抢占，同一轮异常只有一个实例发站内信，
 * 标记随恢复删除，恢复后再次异常即重新告警。
 *
 * 告警是检测而非阻断，阻断由登录锁定/限流/签名协议承担。
 */
@Component
@RequiredArgsConstructor
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** 暴力破解告警去重窗口，同 ip 30 分钟内不重复告警 */
    private static final Duration LOGIN_ALERT_COOLDOWN = Duration.ofMinutes(30);

    /** 主从复制告警冷却窗口，异常持续时 10 分钟最多告警一次，恢复即清标记 */
    private static final Duration REPLICA_ALERT_COOLDOWN = Duration.ofMinutes(10);

    /** 审计链告警去重标记有效期，链持续断裂时每天最多告警一次，链恢复即清标记 */
    private static final Duration AUDIT_ALERT_TTL = Duration.ofHours(24);

    /** 去重标记键，落共享缓存，多实例经 setIfAbsent 抢占，只一个实例发出站内信 */
    private static final String KEY_LOGIN_ALERT_PREFIX = "fms:alert:login:";

    private static final String KEY_AUDIT_ALERT = "fms:alert:audit-chain";

    private static final String KEY_REPLICA_ALERT = "fms:alert:replica";

    private final AlertMapper alertMapper;
    private final CachePort cachePort;
    private final FmsProperties fmsProperties;


    /** 周期检测，默认 5 分钟，与 cache-cleanup 同周期语义 */
    @Scheduled(fixedDelayString = "${fms.alert.interval-ms:300000}")
    public void checkAlerts() {
        // 调度线程无会话身份也无端点标记，声明后台语义供拦截器复位连接会话态回到空壳角色，
        // 连接上的会话级角色与 guc 均来自前序请求，不复位则告警函数对残留职责角色无 EXECUTE
        SandboxRoleHint.set(SandboxRoleHint.Hint.BACKGROUND_APP);
        try {
            FmsProperties.Alert cfg = fmsProperties.getAlert();
            try {
                checkLoginFailures(cfg);
            } catch (Exception e) {
                log.warn("安全告警-暴力破解检测失败: {}", e.getMessage());
            }

            try {
                checkAuditChain();
            } catch (Exception e) {
                log.warn("安全告警-审计链检测失败: {}", e.getMessage());
            }

            try {
                checkReplicaLag(cfg);
            } catch (Exception e) {
                log.warn("安全告警-主从复制检测失败: {}", e.getMessage());
            }

            checkNonceWatermark(cfg);
        } finally {
            SandboxRoleHint.clear();
        }
    }

    /** 窗口内同 ip 登录失败聚合，超阈值则发 admin 站内信，同 ip 30 分钟去重，标记共享故多实例只发一次 */
    void checkLoginFailures(FmsProperties.Alert cfg) {
        String json = alertMapper.loginFailures(cfg.getLoginFailThreshold(), cfg.getLoginFailWindowMin());
        JSONArray rows = JSON.parseArray(json);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int alerted = 0;
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String ip = row.getString("ip");
            long count = row.getLongValue("count");
            // 抢占该 ip 的去重标记，抢到才告警，未抢到即窗口内已有实例告警过
            if (!cachePort.setIfAbsent(KEY_LOGIN_ALERT_PREFIX + ip,
                    String.valueOf(System.currentTimeMillis()), LOGIN_ALERT_COOLDOWN)) {
                continue;
            }
            alertMapper.notifyAdmin("疑似暴力破解：IP " + ip,
                    "近 " + cfg.getLoginFailWindowMin() + " 分钟登录失败 " + count
                            + " 次（阈值 " + cfg.getLoginFailThreshold() + "）。请核查登录日志。");
            log.warn("安全告警：IP {} 近 {} 分钟登录失败 {} 次（疑似暴力破解）", ip,
                    cfg.getLoginFailWindowMin(), count);
            alerted++;
        }
        if (alerted > 0) {
            log.info("安全告警：暴力破解告警已发送 admin 站内信（IP 数 {}）", alerted);
        }
    }

    /** 审计哈希链完整性，断链则发 admin 站内信，标记期内不重复，链恢复即清标记供下次断链重新告警 */
    void checkAuditChain() {
        UUID brokenAt = alertMapper.auditChainBrokenAt();
        if (brokenAt == null) {
            cachePort.delete(KEY_AUDIT_ALERT); // 链恢复，清标记，下次断链重新告警
            return;
        }
        if (!cachePort.setIfAbsent(KEY_AUDIT_ALERT, brokenAt.toString(), AUDIT_ALERT_TTL)) {
            return; // 标记期内已告警过，含同断链点重复与其它实例已告警
        }
        alertMapper.notifyAdmin("审计哈希链断裂（可能被篡改）",
                "审计链首断链点 " + brokenAt + "。请立即执行 audit.audit_log_chain_repair() 排查。");
        log.error("安全告警：审计哈希链断裂，断链点 {}", brokenAt);
    }

    /**
     * 主从复制健康，fms.alert.replica-expected > 0 才启用，经 SECURITY DEFINER 聚合
     * {replicas, max_lag_ms, degraded}，实际从库数 < 期望，从库断开/未启动、存在
     * 非 streaming 状态、或最大 replay 延迟超阈值，报表读陈旧数据则发 admin 站内信，
     * 冷却标记落共享缓存故多实例只发一次，恢复正常即清标记供下次异常重新告警。
     */
    void checkReplicaLag(FmsProperties.Alert cfg) {
        if (cfg.getReplicaExpected() <= 0) {
            return; // 未启用主从监控，单机形态默认
        }
        String json = alertMapper.replicaLag();
        JSONObject row = JSON.parseObject(json);
        if (row == null) {
            return;
        }
        int replicas = row.getIntValue("replicas");
        long maxLagMs = row.getLongValue("max_lag_ms");
        boolean degraded = row.getBooleanValue("degraded");
        boolean healthy = replicas >= cfg.getReplicaExpected() && !degraded
                && maxLagMs <= cfg.getReplicaLagThresholdMs();
        if (healthy) {
            cachePort.delete(KEY_REPLICA_ALERT); // 恢复，清冷却标记，下次异常重新告警
            return;
        }
        if (!cachePort.setIfAbsent(KEY_REPLICA_ALERT, String.valueOf(System.currentTimeMillis()),
                REPLICA_ALERT_COOLDOWN)) {
            return; // 冷却期内不重复告警，异常持续刷屏抑制
        }
        String detail;
        if (replicas < cfg.getReplicaExpected()) {
            detail = "从库缺失：实际 " + replicas + " / 期望 " + cfg.getReplicaExpected();
        } else if (degraded) {
            detail = "复制状态降级（存在非 streaming 的 walsender）";
        } else {
            detail = "最大延迟 " + maxLagMs + "ms 超阈值 " + cfg.getReplicaLagThresholdMs() + "ms（报表可能读陈旧数据）";
        }
        alertMapper.notifyAdmin("主从复制异常：" + detail, "请检查 fms-db-replica 流复制状态（pg_stat_replication）。");
        log.error("安全告警：主从复制异常——{}", detail);
    }

    /** Redis 版 nonce 水位，近似条目数超过期望容量比率则 WARN，每周期日志，不站内信防刷屏 */
    void checkNonceWatermark(FmsProperties.Alert cfg) {
        long capacity = effectiveNonceCapacity();
        if (capacity <= 0) {
            return;
        }
        long size = cachePort.approximateSize("fms:nonce:");
        long threshold = (long) (capacity * cfg.getNonceWatermarkRatio());
        if (size > threshold) {
            log.warn("安全监控：nonce 水位 {}/{} 超阈值 {}（期望容量 {}/{}%），请评估扩容",
                    size, capacity, threshold, cfg.getNonceWatermarkRatio(), 100);
        }
    }

    /** 期望 nonce 容量，显式配置优先，否则按峰值 qps × 防重放窗口动态推导，与 NonceStore 同口径 */
    private long effectiveNonceCapacity() {
        FmsProperties.Security security = fmsProperties.getSecurity();
        if (security.getNonceCapacity() > 0) {
            return security.getNonceCapacity();
        }
        return (long) security.getNoncePeakQps() * security.getReplayWindowSecs();
    }
}
