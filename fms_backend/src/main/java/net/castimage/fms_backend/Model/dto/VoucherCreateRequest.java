package net.castimage.fms_backend.Model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 凭证创建请求（结果恒为 DRAFT 草稿）：Java 预检（期间 OPEN/日期-期间一致/分录平衡/末级科目）后 INSERT 头 + 分录；DB 终审（trg_period_voucher_guard 已结账拦截、trg_voucher_line_leaf 末级科目、行级平衡与总额同步、RLS WITH CHECK 公司范围）。
 */
public record VoucherCreateRequest(
        /** 所属公司 */
        @NotNull(message = "公司必填")
        UUID companyId,
        /** 会计期间（YYYY-MM） */
        @NotBlank(message = "期间必填")
        @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "期间格式须为 YYYY-MM")
        String period,
        /** 凭证日期（缺省当前日期；须落在期间内） */
        LocalDate voucherDate,
        /** 部门 id（RLS 行级收敛；null 表示公司范围可见） */
        UUID deptId, // null 即公司范围可见，
        Boolean isPrivate, // 缺省 false，
        /** 摘要/备注 */
        String note,
        /** 分录列表（非空；借贷合计须平衡——DB 触发器终审） */
        @NotEmpty(message = "凭证至少需要一条分录")
        List<@Valid VoucherLineRequest> lines) {
}
