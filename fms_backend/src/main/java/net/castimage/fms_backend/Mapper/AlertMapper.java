package net.castimage.fms_backend.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/**
 * 安全告警函数 Mapper（init/notify_broadcast.sql 4) 段，全部 SECURITY DEFINER 只读/通知类，
 * EXECUTE 授 fms_app——AlertService 定时调度以直连通道调用（无会话身份）。
 */
public interface AlertMapper {

    /** 暴力破解检测：窗口内同 IP 登录失败聚合（阈值/窗口参数化），返回 [{ip,count}] jsonb 数组文本 */
    @Select("SELECT notify.fn_alert_login_failures(#{threshold}, #{windowMin})::text")
    String loginFailures(@Param("threshold") int threshold, @Param("windowMin") int windowMin);

    /** 审计哈希链状态：首断链点（NULL=完好） */
    @Select("SELECT notify.fn_alert_audit_chain()")
    UUID auditChainBrokenAt();

    /** 安全告警站内信发 admin（SECURITY 类型） */
    @Select("SELECT notify.fn_alert_notify_admin(#{title}, #{content})")
    void notifyAdmin(@Param("title") String title, @Param("content") String content);

    /** 主从复制状态聚合（SECURITY DEFINER，pg_stat_replication 属主可见）：返回 {"replicas":n,"max_lag_ms":n,"degraded":bool} jsonb 文本 */
    @Select("SELECT notify.fn_alert_replica_lag()::text")
    String replicaLag();
}
