package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 预算计划创建/更新请求：创建结果恒为 DRAFT、version=0；(company_id, period, account_id,
 * dept_id, version) 唯一（uk_plan）——同键位重复创建冲突 40900；期间格式 YYYY-MM（DB 侧
 * 无 CHECK，Java 预检）；金额 > 0（CHECK 兜底）。
 */
public record BudgetPlanRequest(
        /** 所属公司（必填，须在当前用户可见范围内） */
        @NotNull(message = "公司必填")
        UUID companyId,
        /** 预算期间（YYYY-MM） */
        @NotBlank(message = "期间必填")
        @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "期间格式须为 YYYY-MM")
        String period,
        /** 预算科目（须存在且可见） */
        @NotNull(message = "预算科目必填")
        UUID accountId,
        /** 预算部门（org.departments） */
        @NotNull(message = "预算部门必填")
        UUID deptId,
        /** 预算金额（> 0） */
        @NotNull(message = "预算金额必填")
        @DecimalMin(value = "0.01", message = "预算金额必须大于 0")
        BigDecimal amount) {
}
