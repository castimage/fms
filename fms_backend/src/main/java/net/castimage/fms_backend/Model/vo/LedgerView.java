package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 总账明细行（GET /api/gl/ledger；MyBatis 结果映射，组件名须与列别名 camelCase 一致）：仅统计 POSTED/REVERSED 凭证（原凭证金额与反向凭证负金额相互抵消），期初余额 = 期间前累计发生净额。
 */
public record LedgerView(UUID accountId, String accountCode, String accountName,
                         String direction, BigDecimal openingBalance, BigDecimal periodDebit,
                         BigDecimal periodCredit, BigDecimal closingBalance) { // 均为有符号净额，
}
