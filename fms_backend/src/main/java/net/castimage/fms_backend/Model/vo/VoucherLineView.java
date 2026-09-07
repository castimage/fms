package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 凭证分录视图（详情联查科目编码/名称；MyBatis 结果映射，组件名须与列别名 camelCase 一致）。
 */
public record VoucherLineView(UUID id, UUID voucherId, Integer lineNo, UUID accountId,
                              String accountCode, String accountName, String summary,
                              BigDecimal debit, BigDecimal credit, String currency,
                              UUID deptId, UUID partnerId, UUID companyId) {
}
