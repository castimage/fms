package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 公司创建/更新请求（创建/更新均经 org.fn_upsert_company，按 code 幂等 upsert）。
 */
public record CompanyRequest(
        /** 公司编码（唯一，字母/数字/连字符） */
        @NotBlank(message = "公司编码不能为空")
        @Size(max = 32, message = "公司编码最长 32 字符")
        String code,
        /** 公司名称 */
        @NotBlank(message = "公司名称不能为空")
        @Size(max = 128, message = "公司名称最长 128 字符")
        String name,
        /** 本位币（3 位字母，默认 CNY） */
        @Size(min = 3, max = 3, message = "币种须为 3 位字母")
        String currency,
        LocalDate fiscalYearStart, // 会计年度起始日，
        /** 状态：ACTIVE/INACTIVE */
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "状态仅支持 ACTIVE/INACTIVE")
        String status) {
}
