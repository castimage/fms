package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 对账撤销请求：双向回置 UNMATCHED 并清 similarity_score/reconciled_at
 * （guard：仅当互指关系与请求一致才生效，0 行则 40900）。
 */
public record UndoMatchRequest(
        /** 收付款流水 id */
        @NotNull(message = "流水 id 必填")
        UUID trxId,
        /** 对账单行 id */
        @NotNull(message = "对账单行 id 必填")
        UUID lineId) {
}
