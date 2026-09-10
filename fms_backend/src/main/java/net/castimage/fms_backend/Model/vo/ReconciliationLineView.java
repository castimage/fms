package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对账单行视图（join 匹配发票号）：match_status UNMATCHED/MATCHED/MANUAL；
 * matchedInvoiceNo 空 = 未匹配；similarityScore 为候选建议（>0.6 自动建议，
 * 不自动匹配）。
 */
public record ReconciliationLineView(UUID id, UUID reconciliationId, LocalDate trxDate,
                                     BigDecimal amount, String summary, String matchStatus,
                                     UUID matchedInvoiceId, String matchedInvoiceNo,
                                     BigDecimal similarityScore, LocalDateTime createdAt) {
}
