package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 看板聚合视图（GET /api/rpt/dashboard）：资金日报（rpt.cash_flow_daily IMMV 近 N 日）+ 预算执行
 * top（rpt.budget_actual IMMV LEFT JOIN 最新 APPROVED budget_plans）+ 应收账龄分桶汇总（rpt.ar_aging
 * 视图按 due_date 相对查询日分桶，Java 侧完成）+ 资金汇总（cash.transactions 当日 IN/OUT 与截至
 * 当日净额）+ 待办审批（wf.tasks PENDING/CLAIMED 计数与今日到期 + due_at 升序 top N 列表）+
 * 异常告警（rpt.fn_dashboard_alerts 预算执行率阈值 / 银行账户余额安全线，JSONB 解析）。
 *
 * @param date         查询日期（缺省当日）
 * @param cashDaily    资金日报行（收入/支出/净额）
 * @param budgetTop    预算执行排行（按实际数倒序 top N）
 * @param agingBuckets 应收账龄分桶（0-30/31-60/61-90/91-180/180+）
 * @param cashSummary  资金汇总（今日收款/今日付款/可用余额）
 * @param todoSummary  待办审批汇总（未办结计数 + 今日到期数）
 * @param todos        待办任务精简行（due_at 升序 top N，title 由 Service 拼装）
 * @param alerts       异常告警（type=warning/error + 中文提示文本）
 */
public record DashboardView(LocalDate date, List<DailyCashRow> cashDaily,
                            List<BudgetTopRow> budgetTop, List<AgingBucket> agingBuckets,
                            CashSummary cashSummary, TodoSummary todoSummary,
                            List<TodoItem> todos, List<AlertItem> alerts) {

    /** 资金日报行（IMMV 单行聚合，净额由 Service 计算 income - expense） */
    public record DailyCashRow(LocalDate trxDate, BigDecimal income, BigDecimal expense, BigDecimal net) {
    }

    /** 预算执行排行行（IMMV 实际数对键最新 APPROVED 计划；无计划行 planAmount=0/ratio=0） */
    public record BudgetTopRow(String period, String deptName, String accountCode, String accountName,
                               BigDecimal planAmount, BigDecimal actualAmount, BigDecimal ratio) {
    }

    /** 应收账龄分桶（bucket 名称 0-30/31-60/61-90/91-180/180+，amount 为该桶未核销余额合计） */
    public record AgingBucket(String bucket, BigDecimal amount) {
    }

    /** 资金汇总（cash.transactions：当日 IN=今日收款、当日 OUT=今日付款、截至当日净额=可用余额；RLS 收敛） */
    public record CashSummary(BigDecimal income, BigDecimal expense, BigDecimal balance) {
    }

    /** 待办审批汇总（wf.tasks PENDING/CLAIMED 可见集计数 + 当日到期数） */
    public record TodoSummary(long pendingCount, long dueTodayCount) {
    }

    /** 看板待办任务行（Mapper 直出，due_at 升序；title 由 Service 拼装） */
    public record TodoRow(UUID taskId, String businessType, String businessId,
                          String definitionName, String status, OffsetDateTime dueAt) {
    }

    /** 看板待办任务精简行（dueAt 为 ISO-8601，前端 dayjs 本地化展示） */
    public record TodoItem(String taskId, String title, String status, String dueAt) {
    }

    /** 异常告警行（type=warning/error 对应 n-alert 类型，text 为中文提示） */
    public record AlertItem(String type, String text) {
    }
}
