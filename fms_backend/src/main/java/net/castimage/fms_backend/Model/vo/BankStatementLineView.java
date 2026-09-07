package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 银行对账单行视图（对账单详情/预览行）：matchedTrxId 双向互指流水。
 */
public record BankStatementLineView(UUID id, UUID statementId, UUID companyId, LocalDate trxDate,
                                    BigDecimal amount, String summary, String direction,
                                    String matchStatus, UUID matchedTrxId,
                                    BigDecimal similarityScore, LocalDateTime createdAt) {
}
