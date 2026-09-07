package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 坏账计提视图（join 发票号）：状态 DRAFT/CONFIRMED/REVERSED；确认后禁改禁删。
 */
public record BadDebtView(UUID id, UUID companyId, UUID invoiceId, String invoiceNo,
                          String period, BigDecimal amount, String status,
                          LocalDateTime createdAt) {
}
