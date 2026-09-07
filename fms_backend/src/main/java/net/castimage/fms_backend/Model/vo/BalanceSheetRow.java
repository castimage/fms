package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;

/**
 * 资产负债表行（GET /api/rpt/balance-sheet；MyBatis 结果映射）：数据源 rpt.balance_sheet 视图
 * （security_invoker=on）——ASSET 借方净额计资产、LIABILITY/EQUITY 贷方净额计负债与权益，
 * 仅统计 POSTED/REVERSED 凭证，按（公司×期间）聚合；恒等式 totalAsset = totalLiability + totalEquity 由 UI 校验。
 */
public record BalanceSheetRow(String period, BigDecimal totalAsset,
                              BigDecimal totalLiability, BigDecimal totalEquity) { // 借贷恒等式断链即异常，
}
