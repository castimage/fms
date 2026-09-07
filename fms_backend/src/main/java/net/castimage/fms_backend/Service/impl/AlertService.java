package net.castimage.fms_backend.Service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.Mapper.AlertMapper;
import net.castimage.fms_backend.Utils.CachePort;
import net.castimage.fms_backend.config.FmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 安全告警检测，定时调度，以 fms_app 直连通道经 SECURITY DEFINER 函数检测，无会话身份，
 * <ul>
 *   <li>暴力破解聚合，窗口内同 ip 登录失败超阈值则发 admin 站内信，同 ip 30 分钟内去重，</li>
 *   <li>审计链篡改，链断，broken_at 非空则发 admin 站内信，同断链点去重，</li>
 *   <li>主从复制，期望从库数缺失/复制状态降级/延迟超阈值则发 admin 站内信，冷却去重，</li>
 *   <li>nonce 水位，Redis 版非精确容量闸，按近似条目数/期望容量比率 WARN，每周期，不刷屏。</li>
 * </ul>
 * 告警是检测而非阻断，阻断由登录锁定/限流/签名协议承担。
 */
@Component
@RequiredArgsConstructor
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** 暴力破解告警去重窗口，同 ip 30 分钟内不重复告警 */
    private static final long LOGIN_ALERT_COOLDOWN_MS = 30 * 60 * 1000L;

    /** 主从复制告警冷却窗口，异常持续时 10 分钟最多告警一次，恢复后重置 */
    private static final long REPLICA_ALERT_COOLDOWN_MS = 10 * 60 * 1000L;

    /** 审计链告警去重，同断链点不重复告警 */
    private static final UUID NO_BROKEN = null;

    private final AlertMapper alertMapper;
    private final CachePort cachePort;
    private final FmsProperties fmsProperties;

    /** 同 ip 上次告警时间，暴力破解去重 */
    private final ConcurrentHashMap<String, Long> lastLoginAlert = new ConcurrentHashMap<>();

    /** 上次告警的断链点，审计链去重 */
    private volatile UUID lastBrokenAt;

    /** 上次主从复制告警时间，冷却去重，正常状态重置为 0 */
    private volatile long lastReplicaAlertAt;


    /** 周期检测，默认 5 分钟，与 cache-cleanup 同周期语义 */
    @Scheduled(fixedDelayString = "${fms.alert.interval-ms:300000}")
    public void checkAlerts() {
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
    }

    /** 窗口内同 ip 登录失败聚合，超阈值则发 admin 站内信，30 分钟去重 */
    void checkLoginFailures(FmsProperties.Alert cfg) {
        String json = alertMapper.loginFailures(cfg.getLoginFailThreshold(), cfg.getLoginFailWindowMin());
        JSONArray rows = JSON.parseArray(json);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean alerted = false;
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String ip = row.getString("ip");
            long count = row.getLongValue("count");
            Long last = lastLoginAlert.get(ip);
            if (last == null || now - last > LOGIN_ALERT_COOLDOWN_MS) {
                lastLoginAlert.put(ip, now);
                alertMapper.notifyAdmin("疑似暴力破解：IP " + ip,
                        "近 " + cfg.getLoginFailWindowMin() + " 分钟登录失败 " + count
                                + " 次（阈值 " + cfg.getLoginFailThreshold() + "）。请核查登录日志。");
                log.warn("安全告警：IP {} 近 {} 分钟登录失败 {} 次（疑似暴力破解）", ip,
                        cfg.getLoginFailWindowMin(), count);
                alerted = true;
            }
        }
        if (alerted) {
            log.info("安全告警：暴力破解告警已发送 admin 站内信（IP 数 {}）", rows.size());
        }
    }

    /** 审计哈希链完整性，断链则发 admin 站内信，同断链点去重，恢复后重置 */
    void checkAuditChain() {
        UUID brokenAt = alertMapper.auditChainBrokenAt();
        if (brokenAt == null) {
            lastBrokenAt = NO_BROKEN; // 链恢复，重置去重，下次断链重新告警
            return;
        }
        if (!brokenAt.equals(lastBrokenAt)) {
            lastBrokenAt = brokenAt;
            alertMapper.notifyAdmin("审计哈希链断裂（可能被篡改）",
                    "审计链首断链点 " + brokenAt + "。请立即执行 audit.audit_log_chain_repair() 排查。");
            log.error("安全告警：审计哈希链断裂，断链点 {}", brokenAt);
        }
    }

    /**
     * 主从复制健康，fms.alert.replica-expected > 0 才启用，经 SECURITY DEFINER 聚合
     * {replicas, max_lag_ms, degraded}，实际从库数 < 期望，从库断开/未启动、存在
     * 非 streaming 状态、或最大 replay 延迟超阈值，报表读陈旧数据则发 admin 站内信，
     * 10 分钟冷却去重，恢复，无任何异常重置冷却。
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
            lastReplicaAlertAt = 0; // 恢复，重置冷却，下次异常重新告警
            return;
        }
        long now = System.currentTimeMillis();
        if (lastReplicaAlertAt != 0 && now - lastReplicaAlertAt < REPLICA_ALERT_COOLDOWN_MS) {
            return; // 冷却期内不重复告警，异常持续刷屏抑制
        }
        lastReplicaAlertAt = now;
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
