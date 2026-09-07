package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 对账行人工匹配请求（POST /api/ap/reconciliations/{id}/match）：匹配经
 * updateMatchStatus 原子守卫（WHERE match_status='UNMATCHED'，0 行则 40900 防双配）；
 * matched_invoice_id 仅回填不核销（核销走 payment_allocations，与对账解耦）。
 */
public record MatchLineRequest(
        /** 对账单行 id（必填） */
        @NotNull(message = "对账单行必填")
        UUID lineId,
        /** 发票 id（必填，须 ISSUED/PARTIAL） */
        @NotNull(message = "发票必填")
        UUID invoiceId) {
}
