package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 收付款流水视图（分页列表，join 银行账户名）：match_status 三态 + 互指 id +
 * 相似度/配对时间，对账差异在 reconcile-runs 中按公司口径汇总。
 */
public record CashTransactionView(UUID id, UUID companyId, UUID bankAccountId, String bankName,
                                  String direction, BigDecimal amount, String partner,
                                  String summary, LocalDate trxDate, String matchStatus,
                                  UUID matchedLineId, BigDecimal similarityScore,
                                  LocalDateTime reconciledAt, LocalDateTime createdAt) {
}
