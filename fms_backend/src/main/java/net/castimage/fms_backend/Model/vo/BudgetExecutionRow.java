package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 预算执行对比行视图（GET /api/budget/execution）：计划最新版本（APPROVED 优先）LEFT JOIN
 * rpt.budget_actual IMMV（company×period×account×dept 直接对键）+ 已批调整净额；
 * ratio = (actual + adjusted) / plan（标度 4），overLimit = ratio ≥ 100%（DB 超支阻断阈值，
 * 超支拒绝由 budget.fn_voucher_budget_block 触发器承担）；level = BLOCK/WARN/NORMAL
 * （warn 阈值 80%）。availableAmount = plan + adjusted - actual。
 */
public record BudgetExecutionRow(UUID planId, UUID companyId, String period, UUID accountId,
                                 String accountCode, String accountName, UUID deptId, String deptName,
                                 BigDecimal planAmount, BigDecimal actualAmount,
                                 BigDecimal adjustedAmount, BigDecimal availableAmount,
                                 BigDecimal ratio, Boolean overLimit, String level) {
}
