package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 报销单列表行视图（分页/详情共用；MyBatis 结果映射，组件名与列别名 camelCase 一致）：
 * RLS 已按本人/本部门/终态（APPROVED/PAID/POSTED）收敛可见集合，companyId 仅过滤提示；
 * applicantName/deptName 由 LEFT JOIN um.users / org.departments 联查。
 */
public record ReimbursementView(UUID id, UUID companyId, UUID applicantId, String applicantName,
                                UUID deptId, String deptName, String title, BigDecimal amount,
                                String currency, String status, UUID wfInstanceId, UUID voucherId,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {
}
