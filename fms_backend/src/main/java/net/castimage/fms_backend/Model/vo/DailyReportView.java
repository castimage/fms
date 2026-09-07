package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 资金日报视图：opening/closing 余额 + 当日收付净额 + 分账户明细（来自 PG 函数
 * cash.fn_daily_report 的 jsonb，fastjson2 解析）+ 未来 7 天资金预测
 * （ar 应收到期未核销 - ap 应付到期未核销，B 类 SQL 按日聚合）。
 */
public record DailyReportView(LocalDate date, BigDecimal openingBalance, BigDecimal income,
                              BigDecimal expense, BigDecimal net, BigDecimal closingBalance,
                              List<AccountDailyRow> byAccount,
                              List<DailyForecastRow> forecast) {
}
