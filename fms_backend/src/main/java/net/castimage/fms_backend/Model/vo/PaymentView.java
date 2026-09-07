package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 付款单视图（join 供应商名 + 银行账户名）：状态 PAID/ALLOCATED/REVERSED；
 * bankName 为空表示未指定付款账户。
 */
public record PaymentView(UUID id, UUID companyId, UUID supplierId, String supplierName,
                          BigDecimal amount, UUID bankAccountId, String bankName,
                          LocalDate paymentDate, String status, UUID voucherId,
                          LocalDateTime createdAt) {
}
