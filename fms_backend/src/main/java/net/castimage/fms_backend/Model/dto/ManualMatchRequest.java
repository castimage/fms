package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 人工对账请求：将收付款流水与对账单行双向互指配对（均 WHERE match_status='UNMATCHED'
 * 原子守卫，0 行则 40900"已被消费"）；companyId 用于预检双方同公司（RLS 兜底）。
 */
public record ManualMatchRequest(
        /** 收付款流水 id */
        @NotNull(message = "流水 id 必填")
        UUID trxId,
        /** 对账单行 id */
        @NotNull(message = "对账单行 id 必填")
        UUID lineId,
        /** 公司 id（预检一致用） */
        @NotNull(message = "公司 id 必填")
        UUID companyId) {
}
