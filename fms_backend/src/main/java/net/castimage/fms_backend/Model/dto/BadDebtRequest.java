package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 坏账计提请求：按 (invoice_id, period) 查重防重复计提（selectByInvoicePeriod 命中则
 * 40900）；发票 PAID 后禁止计提（Service 预检）。
 */
public record BadDebtRequest(
        /** 发票 id（必填，须 ISSUED/PARTIAL 且未计提） */
        @NotNull(message = "发票必填")
        UUID invoiceId,
        /** 计提期间（YYYY-MM） */
        @NotBlank(message = "计提期间必填")
        @Pattern(regexp = "\\d{4}-\\d{2}", message = "计提期间格式须为 YYYY-MM")
        String period,
        /** 计提金额（&gt;= 0） */
        @NotNull(message = "计提金额必填")
        @DecimalMin(value = "0", message = "计提金额不能为负")
        BigDecimal amount) {
}
