package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 登录日志行视图（GET /api/admin/login-logs 分页；MyBatis 结果映射）：um.login_log 按月分区
 * 父表只读查询，ip inet 经 ::text 映射 String；RLS 策略收敛可见行（本人 / fms_auditor /
 * audit:read:all 权限点，admin 经 RBAC 放行全量）。
 */
public record LoginLogView(UUID id, UUID userId, String loginName, String ip,
                           String userAgent, Boolean success, String failReason,
                           LocalDateTime lockedUntil, LocalDateTime occurredAt) {
}
