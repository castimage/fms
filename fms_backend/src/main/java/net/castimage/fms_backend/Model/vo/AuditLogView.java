package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审计日志行视图（GET /api/audit/logs 分页；MyBatis 结果映射，组件名与列别名 camelCase 一致）：
 * RLS 仅 fms_auditor 可读（策略 auth.has_role('fms_auditor') OR current_user='fms_admin'），
 * 其他职责角色查询 42501 映射 40300；oldValues/newValues JSONB 透传 String（fastjson2 解析）。
 */
public record AuditLogView(UUID id, UUID actorId, String action, String tableName,
                           UUID recordId, String oldValues, String newValues,
                           String ip, String requestId, LocalDateTime occurredAt,
                           String prevHash, String rowHash) { // 哈希链两列随行返回，供完整性展示，
}
