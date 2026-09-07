package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 预算调整单请求：amount 正=追加/负=调减（不可为 0，DB CHECK 兜底）；审批后并入执行基数；
 * 调整单创建结果恒为 DRAFT，走 approve/reject 状态机。
 */
public record BudgetAdjustmentRequest(
        /** 关联预算计划（须存在） */
        @NotNull(message = "预算计划必填")
        UUID planId,
        /** 调整金额（正=追加 负=调减；<> 0） */
        @NotNull(message = "调整金额必填")
        BigDecimal amount,
        /** 调整原因 */
        @NotBlank(message = "调整原因必填")
        String reason) {
}
