package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 应用日志行视图（GET /api/admin/app-logs 分页；MyBatis 结果映射）：context JSONB 经 ::text
 * 映射 String；RLS current_uid() IS NOT NULL + 授权矩阵控制可见角色。
 */
public record AppLogView(UUID id, String level, String logger, String message,
                         String context, LocalDateTime occurredAt) { // 按天分区父表查询，
}
