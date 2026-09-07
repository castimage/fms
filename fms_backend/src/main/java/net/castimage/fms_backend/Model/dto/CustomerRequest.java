package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 客户创建/更新请求：code 公司内唯一（uk_ar_customer_code 兜底 23505 映射 40900）；
 * status 白名单由 DB CHECK 终审；更新时 code 可改（不涉及树结构）。
 */
public record CustomerRequest(
        /** 所属公司（必填） */
        UUID companyId,
        /** 客户编码（创建必填） */
        @NotBlank(message = "客户编码必填")
        @Size(max = 32, message = "客户编码最长 32 字符")
        String code,
        /** 客户名称（trgm 检索列） */
        @NotBlank(message = "客户名称必填")
        @Size(max = 128, message = "客户名称最长 128 字符")
        String name,
        /** 税号 */
        @Size(max = 32, message = "税号最长 32 字符")
        String taxNo,
        /** 信用额度（创建缺省 0） */
        @DecimalMin(value = "0", message = "信用额度不能为负")
        BigDecimal creditLimit,
        /** 状态：ACTIVE/INACTIVE/BLOCKED（创建缺省 ACTIVE） */
        @Pattern(regexp = "ACTIVE|INACTIVE|BLOCKED", message = "客户状态须为 ACTIVE/INACTIVE/BLOCKED")
        String status) {
}
