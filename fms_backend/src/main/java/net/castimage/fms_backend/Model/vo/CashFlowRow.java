package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;

/**
 * 现金流量表行（GET /api/rpt/cash-flow；MyBatis 结果映射）：数据源 rpt.cash_flow 视图
 * （security_invoker=on）——按（公司×月）聚合 cash.transactions 收入/支出/净额，期间列
 * 由 date_trunc 月截断转为 'YYYY-MM' 文本。
 */
public record CashFlowRow(String period, BigDecimal cashIn,
                          BigDecimal cashOut, BigDecimal netFlow) { // netFlow 即资金日报口径，
}
