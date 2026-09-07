package net.castimage.fms_backend.Model.vo;

import java.util.List;

/**
 * 供应商对账单详情视图（GET /api/ap/reconciliations/{id}）：头 + 行明细；
 * 行明细含匹配状态与候选相似度建议。
 */
public record ReconciliationDetailView(ReconciliationView header, List<ReconciliationLineView> lines) {
}
