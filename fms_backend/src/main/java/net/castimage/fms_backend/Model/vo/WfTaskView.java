package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工作流任务视图（待办/实例下任务/详情共用）：join 实例（业务类型/单号）+ 流程定义名 +
 * 处理人登录名；待办列表 SQL 显式过滤 assignee=当前用户 AND status IN (PENDING,CLAIMED)
 * （RLS is_self 双路径兜底）；due_at 为催办截止时间。
 */
public record WfTaskView(UUID id, UUID companyId, UUID instanceId, String businessType,
                         UUID businessId, String definitionName, String nodeCode,
                         UUID assigneeId, String assigneeName, String taskType, String status,
                         LocalDateTime dueAt, LocalDateTime claimAt, Integer version,
                         String remark, LocalDateTime createdAt) {
}
