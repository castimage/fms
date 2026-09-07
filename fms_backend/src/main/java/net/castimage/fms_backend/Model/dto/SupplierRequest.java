package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 供应商创建/更新请求：code 公司内唯一（uk_ap_supplier_code 兜底 23505 映射 40900）；
 * status 白名单由 DB CHECK 终审。
 */
public record SupplierRequest(
        /** 所属公司（必填） */
        UUID companyId,
        /** 供应商编码（创建必填） */
        @NotBlank(message = "供应商编码必填")
        @Size(max = 32, message = "供应商编码最长 32 字符")
        String code,
        /** 供应商名称（trgm 检索列） */
        @NotBlank(message = "供应商名称必填")
        @Size(max = 128, message = "供应商名称最长 128 字符")
        String name,
        /** 税号 */
        @Size(max = 32, message = "税号最长 32 字符")
        String taxNo,
        /** 信用额度（创建缺省 0） */
        @DecimalMin(value = "0", message = "信用额度不能为负")
        BigDecimal creditLimit,
        /** 状态：ACTIVE/INACTIVE/BLOCKED（创建缺省 ACTIVE） */
        @Pattern(regexp = "ACTIVE|INACTIVE|BLOCKED", message = "供应商状态须为 ACTIVE/INACTIVE/BLOCKED")
        String status) {
}
