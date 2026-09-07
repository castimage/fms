package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 付款核销单项（POST /api/ap/payments/{id}/allocate 请求体元素）：paymentId 由
 * 路径提供，invoiceId + amount 构成单笔核销；同对 (payment_id, invoice_id) 由
 * uk_ap_alloc 唯一约束防重复核销（23505 映射 40900）；一次可批量核销多张发票，
 * 金额合计不得超过付款单金额（Service 预检）。
 */
public record PaymentAllocateRequest(
        /** 发票 id（必填，须 ISSUED/PARTIAL） */
        @NotNull(message = "发票必填")
        UUID invoiceId,
        /** 本次核销金额（必填，&gt; 0） */
        @NotNull(message = "核销金额必填")
        @DecimalMin(value = "0.01", message = "核销金额必须大于 0")
        BigDecimal amount) {
}
