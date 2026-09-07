package net.castimage.fms_backend.Model.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 供应商对账单头视图（join 供应商名）：状态 DRAFT/CONFIRMED/VOID；行明细经
 * GET /api/ap/reconciliations/{id} 返回（selectDetail 头 + 行）。
 */
public record ReconciliationView(UUID id, UUID companyId, UUID supplierId, String supplierName,
                                 LocalDate statementDate, String status,
                                 LocalDateTime createdAt) {
}
