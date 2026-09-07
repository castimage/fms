package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 应付发票视图（join ap.suppliers 联查供应商名）：状态机
 * DRAFT/ISSUED/PARTIAL/PAID/VOID；付款余额 = total - 已核销（selectOutstanding）。
 */
public record ApInvoiceView(UUID id, UUID companyId, UUID supplierId, String supplierName,
                            String invoiceNo, LocalDate invoiceDate, LocalDate dueDate,
                            String currency, BigDecimal total, BigDecimal tax,
                            String status, UUID voucherId, Integer version,
                            LocalDateTime createdAt) {
}
