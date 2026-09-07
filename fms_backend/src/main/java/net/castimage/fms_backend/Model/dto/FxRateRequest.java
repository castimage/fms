package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 期末汇率维护请求（PUT/POST /api/gl/fx-rates）：同公司×币种×期间 upsert（ON CONFLICT
 * DO UPDATE，软删行自动复活）；rate 须为正数（DB CHECK rate > 0 终审）。
 */
public record FxRateRequest(
        /** 所属公司（必填） */
        @NotNull(message = "公司必填")
        UUID companyId,

        /** 币种（3 位 ISO 代码，必填） */
        @NotBlank(message = "币种必填")
        @Size(min = 3, max = 3, message = "币种须为 3 位 ISO 代码")
        String currency,

        /** 期间（YYYY-MM，必填） */
        @NotBlank(message = "期间必填")
        @Pattern(regexp = "\\d{4}-\\d{2}", message = "期间格式须为 YYYY-MM")
        String period,

        /** 期末汇率（1 单位外币折本币，> 0） */
        @NotNull(message = "汇率必填")
        @DecimalMin(value = "0.000001", message = "汇率必须大于 0")
        BigDecimal rate) {
}
