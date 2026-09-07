package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;

/**
 * 利润表行（GET /api/rpt/income-statement；MyBatis 结果映射，组件名与列别名 camelCase 一致）：
 * 数据源 rpt.income_statement 视图（security_invoker=on，行级按调用者 GUC 收敛）——REVENUE 类科目
 * 贷方净额计收入、EXPENSE 类科目借方净额计费用，仅统计 POSTED/REVERSED 凭证，按（公司×期间）聚合。
 */
public record IncomeStatementRow(String period, BigDecimal totalRevenue,
                                 BigDecimal totalExpense, BigDecimal netProfit) { // 视图已算好净额，
}
