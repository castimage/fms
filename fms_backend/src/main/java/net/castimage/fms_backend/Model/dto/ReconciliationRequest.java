package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 供应商对账单创建请求（DRAFT 起步）：statement_date 为对账基准日；
 * 行导入与匹配在创建后经 lines/match/confirm 端点推进。
 */
public record ReconciliationRequest(
        /** 所属公司（必填） */
        UUID companyId,
        /** 供应商 id（必填） */
        @NotNull(message = "供应商必填")
        UUID supplierId,
        /** 对账基准日（必填） */
        @NotNull(message = "对账基准日必填")
        LocalDate statementDate) {
}
