package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 收款单视图（join 客户名 + 银行账户名）：状态 RECEIVED/ALLOCATED/REVERSED；
 * bankName 为空表示未指定收款账户。
 */
public record ReceiptView(UUID id, UUID companyId, UUID customerId, String customerName,
                          BigDecimal amount, UUID bankAccountId, String bankName,
                          LocalDate receiptDate, String status, UUID voucherId,
                          LocalDateTime createdAt) {
}
