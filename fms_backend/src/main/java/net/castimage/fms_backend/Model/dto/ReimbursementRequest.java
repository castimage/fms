package net.castimage.fms_backend.Model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 报销单创建/更新请求（头 + 明细行同事务）：result 恒为 DRAFT 草稿；头 amount 必须等于
 * 明细行合计（Service 强制，DB 无对应 CHECK——一致性由 Java 层保证）；RLS INSERT
 * WITH CHECK (can_access_org(company_id) AND is_self(applicant_id)) 双闸，applicant 恒为
 * 当前用户（请求不传）。
 */
public record ReimbursementRequest(
        /** 所属公司（必填，须在当前用户可见范围内——RLS WITH CHECK 兜底） */
        @NotNull(message = "公司必填")
        UUID companyId,
        /** 报销标题 */
        @NotBlank(message = "标题必填")
        String title,
        /** 报销总额（必填，须 = 明细行合计） */
        @NotNull(message = "报销总额必填")
        @DecimalMin(value = "0.01", message = "报销总额必须大于 0")
        BigDecimal amount,
        /** 币种（CHAR(3)，缺省 CNY） */
        String currency,
        /** 报销部门（RLS can_access_dept 行级收敛） */
        @NotNull(message = "部门必填")
        UUID deptId,
        /** 明细行列表（非空；合计须与头金额一致——Service 强制） */
        @NotEmpty(message = "报销单至少需要一条明细")
        List<@Valid ReimbursementLineRequest> lines) {
}
