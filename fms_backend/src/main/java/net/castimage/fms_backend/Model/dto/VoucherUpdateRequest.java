package net.castimage.fms_backend.Model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 凭证更新请求（仅 DRAFT 可更新；期间不可变更）：软删旧分录（UPDATE deleted_at=now()）+ 全量重插新分录，凭证头总额由行级触发器自动同步，Java 侧按最新分录重算双保险。
 */
public record VoucherUpdateRequest(
        /** 凭证日期（须落在原期间内） */
        LocalDate voucherDate, // 期间不可变更，
        /** 部门 id */
        UUID deptId,
        /** 私密凭证 */
        Boolean isPrivate,
        /** 摘要/备注 */
        String note,
        /** 分录列表（全量替换） */
        @NotEmpty(message = "凭证至少需要一条分录")
        List<@Valid VoucherLineRequest> lines) {
}
