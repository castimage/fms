package net.castimage.fms_backend.Service.impl;

import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Mapper.AlertMapper;
import net.castimage.fms_backend.Service.impl.AlertService;
import net.castimage.fms_backend.Utils.CachePort;
import net.castimage.fms_backend.Utils.InMemoryCachePort;
import net.castimage.fms_backend.config.FmsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全告警服务测试，暴力破解聚合，超阈值告警 + 同 ip 冷却去重、审计链断链告警，标记期内去重与
 * 恢复后重置、主从复制异常告警，缺失/降级/延迟超阈值 + 冷却去重、nonce 水位 WARN 阈值。
 *
 * 去重标记落 CachePort，故缓存用真实内存实现而非 mock：冷却期抑制、恢复清标记、以及
 * 他实例已抢占标记时本实例不再重复发信，都由实现语义直接判定，预置标记即模拟多实例并发。
 */
class AlertServiceTest {

    /** 去重标记键，与 AlertService 内常量一致 */
    private static final String KEY_LOGIN = "fms:alert:login:";

    private static final String KEY_AUDIT = "fms:alert:audit-chain";

    private static final String KEY_REPLICA = "fms:alert:replica";

    private AlertMapper alertMapper;
    private CachePort cachePort;
    private FmsProperties props;
    private AlertService service;

    @BeforeEach
    void setUp() {
        alertMapper = mock(AlertMapper.class);
        cachePort = new InMemoryCachePort();
        props = new FmsProperties();
        service = new AlertService(alertMapper, cachePort, props);
    }

    /** 主从监控启用配置（replica-expected > 0） */
    private void enableReplicaMonitor(int expected, long thresholdMs) {
        props.getAlert().setReplicaExpected(expected);
        props.getAlert().setReplicaLagThresholdMs(thresholdMs);
    }

    @Test
    void noAlertWhenNoIpExceedsThreshold() {
        when(alertMapper.loginFailures(20, 15)).thenReturn("[]");
        service.checkAlerts();
        verify(alertMapper, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void alertWhenIpExceedsThreshold() {
        when(alertMapper.loginFailures(20, 15)).thenReturn(
                "[{\"ip\":\"1.2.3.4\",\"count\":25},{\"ip\":\"5.6.7.8\",\"count\":21}]");
        service.checkAlerts();
        verify(alertMapper, times(2)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void sameIpCooldownSuppressesRepeatAlert() {
        when(alertMapper.loginFailures(20, 15)).thenReturn("[{\"ip\":\"1.2.3.4\",\"count\":25}]");
        service.checkAlerts();
        service.checkAlerts(); // 第二次:同 ip 30 分钟冷却期内则不重复
        verify(alertMapper, times(1)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void loginAlertSuppressedWhenAnotherInstanceClaimedFirst() {
        // 他实例已抢占该 ip 标记即模拟多实例并发同轮检测，本实例不得重复发信
        cachePort.setIfAbsent(KEY_LOGIN + "1.2.3.4", "claimed", Duration.ofMinutes(30));
        when(alertMapper.loginFailures(20, 15)).thenReturn("[{\"ip\":\"1.2.3.4\",\"count\":25}]");
        service.checkAlerts();
        verify(alertMapper, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void intactAuditChainNoAlert() {
        when(alertMapper.auditChainBrokenAt()).thenReturn(null);
        service.checkAuditChain();
        verify(alertMapper, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void brokenAuditChainAlertsOncePerBrokenPoint() {
        UUID broken = UuidV7.next();
        when(alertMapper.auditChainBrokenAt()).thenReturn(broken);
        service.checkAuditChain();
        service.checkAuditChain(); // 同断链点:不重复
        verify(alertMapper, times(1)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void auditAlertSuppressedWhenAnotherInstanceClaimedFirst() {
        cachePort.setIfAbsent(KEY_AUDIT, UuidV7.next().toString(), Duration.ofHours(24));
        when(alertMapper.auditChainBrokenAt()).thenReturn(UuidV7.next());
        service.checkAuditChain();
        verify(alertMapper, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void distinctBreakPointWithinMarkerTtlIsSuppressed() {
        // 标记期内换一处断链点也不重复告警，首次告警已带断链点告知链被破坏，此后 24 小时最多一条
        when(alertMapper.auditChainBrokenAt()).thenReturn(UuidV7.next(), UuidV7.next());
        service.checkAuditChain();
        service.checkAuditChain();
        verify(alertMapper, times(1)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void recoveredAuditChainCanAlertAgainOnNewBreak() {
        UUID broken1 = UuidV7.next();
        UUID broken2 = UuidV7.next();
        when(alertMapper.auditChainBrokenAt()).thenReturn(broken1, null, broken2);
        service.checkAuditChain();
        service.checkAuditChain(); // 恢复
        service.checkAuditChain(); // 新断链点:再次告警
        verify(alertMapper, times(2)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void recoveredAuditChainAlertsAgainOnSameBrokenPoint() {
        UUID broken = UuidV7.next();
        when(alertMapper.auditChainBrokenAt()).thenReturn(broken, null, broken);
        service.checkAuditChain();
        service.checkAuditChain(); // 恢复，清标记
        service.checkAuditChain(); // 同一断链点复发:标记已清故重新告警
        verify(alertMapper, times(2)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void nonceWatermarkOverThresholdWarnsOnly() {
        // 期望容量 10 则阈值 7，写入 8 条 nonce 条目即超阈值，水位仅 WARN 日志不发站内信
        props.getSecurity().setNonceCapacity(10);
        for (int i = 0; i < 8; i++) {
            cachePort.set("fms:nonce:" + i, "x", Duration.ofMinutes(1));
        }
        service.checkNonceWatermark(props.getAlert());
        verify(alertMapper, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void nonceWatermarkBelowThresholdNoAction() {
        service.checkNonceWatermark(props.getAlert());
        verify(alertMapper, never()).notifyAdmin(anyString(), anyString());
    }

    // 主从复制监控，replica-expected > 0 才启用

    @Test
    void replicaLagDisabledByDefault() {
        // 默认 replica-expected=0，单机形态:不调用检测函数、不告警
        service.checkAlerts();
        verify(alertMapper, never()).replicaLag();
        verify(alertMapper, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void replicaLagHealthyNoAlert() {
        enableReplicaMonitor(1, 5000);
        when(alertMapper.replicaLag()).thenReturn("{\"replicas\":1,\"max_lag_ms\":200,\"degraded\":false}");
        service.checkReplicaLag(props.getAlert());
        verify(alertMapper, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void replicaMissingAlertsWhenBelowExpected() {
        enableReplicaMonitor(1, 5000);
        when(alertMapper.replicaLag()).thenReturn("{\"replicas\":0,\"max_lag_ms\":0,\"degraded\":false}");
        service.checkReplicaLag(props.getAlert());
        verify(alertMapper, times(1)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void replicaLagOverThresholdAlerts() {
        enableReplicaMonitor(1, 5000);
        when(alertMapper.replicaLag()).thenReturn("{\"replicas\":1,\"max_lag_ms\":30000,\"degraded\":false}");
        service.checkReplicaLag(props.getAlert());
        verify(alertMapper, times(1)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void replicaDegradedStateAlerts() {
        enableReplicaMonitor(1, 5000);
        when(alertMapper.replicaLag()).thenReturn("{\"replicas\":1,\"max_lag_ms\":0,\"degraded\":true}");
        service.checkReplicaLag(props.getAlert());
        verify(alertMapper, times(1)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void replicaAlertSuppressedWhenAnotherInstanceClaimedFirst() {
        enableReplicaMonitor(1, 5000);
        cachePort.setIfAbsent(KEY_REPLICA, "claimed", Duration.ofMinutes(10));
        when(alertMapper.replicaLag()).thenReturn("{\"replicas\":0,\"max_lag_ms\":0,\"degraded\":false}");
        service.checkReplicaLag(props.getAlert());
        verify(alertMapper, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void replicaAlertCooldownSuppressesRepeatAndRecoveryResets() {
        enableReplicaMonitor(1, 5000);
        when(alertMapper.replicaLag()).thenReturn(
                "{\"replicas\":0,\"max_lag_ms\":0,\"degraded\":false}",
                "{\"replicas\":0,\"max_lag_ms\":0,\"degraded\":false}",
                "{\"replicas\":1,\"max_lag_ms\":100,\"degraded\":false}",
                "{\"replicas\":0,\"max_lag_ms\":0,\"degraded\":false}");
        service.checkReplicaLag(props.getAlert());
        service.checkReplicaLag(props.getAlert()); // 冷却期:不重复
        service.checkReplicaLag(props.getAlert()); // 恢复:清标记
        service.checkReplicaLag(props.getAlert()); // 再次异常:重新告警
        verify(alertMapper, times(2)).notifyAdmin(anyString(), anyString());
    }
}
