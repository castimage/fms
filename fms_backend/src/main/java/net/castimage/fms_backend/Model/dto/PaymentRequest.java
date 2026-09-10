package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 付款登记请求：登记即付款（status=PAID）；amount 必填且 > 0（DB CHECK 兜底）；
 * 核销后 Service 推进 ALLOCATED。
 */
public record PaymentRequest(
        /** 所属公司（必填） */
        UUID companyId,
        /** 供应商 id（必填，须存在） */
        @NotNull(message = "供应商必填")
        UUID supplierId,
        /** 付款金额（必填，> 0） */
        @NotNull(message = "付款金额必填")
        @DecimalMin(value = "0.01", message = "付款金额必须大于 0")
        BigDecimal amount,
        /** 付款银行账户（可空，语义引用 cash.bank_accounts） */
        UUID bankAccountId,
        /** 付款日期（必填） */
        @NotNull(message = "付款日期必填")
        LocalDate paymentDate) {
}
