package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 试算平衡行（GET /api/gl/trial-balance；MyBatis 结果映射，组件名须与列别名 camelCase 一致）：期初/期末余额为有符号净额（正=借方余额方向，负=贷方），汇总由 TrialBalanceView 拆分。
 */
public record TrialBalanceRow(UUID accountId, String accountCode, String accountName,
                              String direction, BigDecimal openingBalance, BigDecimal periodDebit,
                              BigDecimal periodCredit, BigDecimal closingBalance) { // 汇总行在 TrialBalanceView，
}
