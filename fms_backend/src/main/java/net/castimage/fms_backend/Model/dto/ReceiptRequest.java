package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 收款登记请求：amount 必填且 &gt; 0（DB CHECK 兜底）；status 初始 RECEIVED，
 * 核销后 Service 推进 ALLOCATED。
 */
public record ReceiptRequest(
        /** 所属公司（必填） */
        UUID companyId,
        /** 客户 id（必填，须存在） */
        @NotNull(message = "客户必填")
        UUID customerId,
        /** 收款金额（必填，&gt; 0） */
        @NotNull(message = "收款金额必填")
        @DecimalMin(value = "0.01", message = "收款金额必须大于 0")
        BigDecimal amount,
        /** 收款银行账户（可空，语义引用 cash.bank_accounts） */
        UUID bankAccountId,
        /** 收款日期（必填） */
        @NotNull(message = "收款日期必填")
        LocalDate receiptDate) {
}
