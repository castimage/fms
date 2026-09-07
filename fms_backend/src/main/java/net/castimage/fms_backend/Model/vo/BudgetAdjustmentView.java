package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 预算调整单视图：join 父预算计划带出期间（period）；行级公司范围经父计划子查询 RLS 收敛；
 * amount 正=追加/负=调减；status DRAFT/APPROVED/REJECTED（RLS update USING status='DRAFT'）。
 */
public record BudgetAdjustmentView(UUID id, UUID companyId, UUID planId, String period,
                                   BigDecimal amount, String reason, String status,
                                   UUID adjustedBy, LocalDateTime adjustedAt,
                                   LocalDateTime createdAt) {
}
