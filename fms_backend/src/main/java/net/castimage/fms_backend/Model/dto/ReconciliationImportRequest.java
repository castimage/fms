package net.castimage.fms_backend.Model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 对账单行导入请求（POST /api/ap/reconciliations/{id}/lines）：一次性批量导入，
 * 全部落库为 UNMATCHED；导入时 Service 对每行做候选匹配建议（金额相等 + 日期差
 * ≤3 天 + similarity 降序，top1 > 0.6 回写 similarity_score 供 UI 展示建议，
 * 不自动匹配）。
 */
public record ReconciliationImportRequest(
        /** 对账单行列表（非空） */
        @NotEmpty(message = "对账单行不能为空")
        List<@Valid ReconciliationLineRequest> lines) {

    /**
     * 单行：交易日期 + 金额 + 摘要（候选匹配 similarity 数据源）。
     */
    public record ReconciliationLineRequest(
            /** 交易日期（必填） */
            @NotNull(message = "交易日期必填")
            LocalDate trxDate,
            /** 金额（必填，> 0） */
            @NotNull(message = "金额必填")
            BigDecimal amount,
            /** 摘要（候选匹配数据源） */
            String summary) {
    }
}
