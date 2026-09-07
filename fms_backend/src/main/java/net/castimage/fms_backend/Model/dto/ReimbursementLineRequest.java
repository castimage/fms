package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 报销明细行请求：type/amount/trxDate/summary 必填；amount > 0（DB CHECK 兜底）；
 * 行金额合计由 ExpenseServiceImpl 与头金额强制一致（防头行不一致）。
 */
public record ReimbursementLineRequest(
        /** 明细类型（餐费/差旅/办公等） */
        @NotBlank(message = "明细类型必填")
        String type,
        /** 行金额（> 0） */
        @NotNull(message = "明细金额必填")
        @DecimalMin(value = "0.01", message = "明细金额必须大于 0")
        BigDecimal amount,
        /** 发生日期 */
        @NotNull(message = "发生日期必填")
        LocalDate trxDate,
        /** 摘要 */
        @NotBlank(message = "摘要必填")
        String summary,
        /** 发票附件关联 id（可空） */
        String invoiceFileId) {
}
