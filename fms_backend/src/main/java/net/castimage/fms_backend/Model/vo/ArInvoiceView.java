package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 应收发票视图（join ar.customers 联查客户名）：含 status 状态机
 * （DRAFT/ISSUED/PARTIAL/PAID/VOID）与乐观锁 version；核销余额 = total - 已核销
 * （见 selectOutstandingByCustomer）。
 */
public record ArInvoiceView(UUID id, UUID companyId, UUID customerId, String customerName,
                            String invoiceNo, LocalDate invoiceDate, LocalDate dueDate,
                            String currency, BigDecimal total, BigDecimal tax,
                            String status, UUID voucherId, Integer version,
                            LocalDateTime createdAt) {
}
