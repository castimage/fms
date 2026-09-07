package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 应付发票创建/更新请求：invoice_no 公司内唯一；开票后（ISSUED+）发票不可变，
 * 更新仅 DRAFT 生效；供应商须存在且公司一致。
 */
public record ApInvoiceRequest(
        /** 所属公司（必填） */
        UUID companyId,
        /** 供应商 id（必填） */
        @NotNull(message = "供应商必填")
        UUID supplierId,
        /** 发票号（创建必填，公司内唯一） */
        @NotBlank(message = "发票号必填")
        @Size(max = 32, message = "发票号最长 32 字符")
        String invoiceNo,
        /** 开票日期（必填） */
        @NotNull(message = "开票日期必填")
        LocalDate invoiceDate,
        /** 到期日（应付到期日/资金预测口径，可空） */
        LocalDate dueDate,
        /** 币种（CHAR(3)，缺省 CNY） */
        @Size(max = 3, message = "币种为 3 位 ISO 代码")
        String currency,
        /** 含税总额（必填，&gt; 0） */
        @NotNull(message = "发票总额必填")
        @DecimalMin(value = "0.01", message = "发票总额必须大于 0")
        BigDecimal total,
        /** 税额（缺省 0） */
        @DecimalMin(value = "0", message = "税额不能为负")
        BigDecimal tax) {
}
