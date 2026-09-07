package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 核销明细视图（join 发票号/开票日期）：按收款单维度查询核销历史；
 * version 供乐观锁前端回传。
 */
public record AllocationView(UUID id, UUID receiptId, UUID invoiceId, String invoiceNo,
                             LocalDate invoiceDate, BigDecimal amount, Integer version,
                             LocalDateTime createdAt) {
}
