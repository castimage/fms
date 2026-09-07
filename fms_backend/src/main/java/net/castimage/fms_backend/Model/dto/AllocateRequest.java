package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 应收核销请求（POST /api/ar/receipts/{id}/allocate 请求体）：receiptId 以路径
 * 为准（请求体同字段被 Controller 覆盖）；invoiceId+amount 构成单笔核销，同对
 * (receipt_id, invoice_id) 由 uk_ar_alloc 唯一约束防重复核销（23505 映射 40900）。
 */
public record AllocateRequest(
        /** 收款单 id（以路径为准） */
        UUID receiptId,
        /** 发票 id（必填，须 ISSUED/PARTIAL） */
        @NotNull(message = "发票必填")
        UUID invoiceId,
        /** 本次核销金额（必填，&gt; 0，累计不得超过发票总额） */
        @NotNull(message = "核销金额必填")
        @DecimalMin(value = "0.01", message = "核销金额必须大于 0")
        BigDecimal amount) {
}
