package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 预算计划视图（分页/详情共用）：join gl.chart_accounts（科目编码/名称）+ org.departments
 * （部门名）；RLS 已按 can_access_org +（can_access_dept(dept_id) OR is_self(created_by)）
 * 收敛可见集合；version 为计划版本号（调整以新版本叠加）。
 */
public record BudgetPlanView(UUID id, UUID companyId, String period, UUID accountId,
                             String accountCode, String accountName, UUID deptId, String deptName,
                             BigDecimal amount, Integer version, String status,
                             UUID approvedBy, LocalDateTime approvedAt, UUID createdBy,
                             LocalDateTime createdAt) {
}
