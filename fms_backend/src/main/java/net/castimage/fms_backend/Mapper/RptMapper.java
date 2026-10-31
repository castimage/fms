package net.castimage.fms_backend.Mapper;

import net.castimage.fms_backend.Model.vo.ArAgingRow;
import net.castimage.fms_backend.Model.vo.BalanceSheetRow;
import net.castimage.fms_backend.Model.vo.CashFlowRow;
import net.castimage.fms_backend.Model.vo.DeptExpenseRow;
import net.castimage.fms_backend.Model.vo.DashboardView;
import net.castimage.fms_backend.Model.vo.IncomeStatementRow;
import net.castimage.fms_backend.Model.vo.TrialBalanceRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 报表 Mapper（rpt 域）：非 BaseMapper，纯 B 类手写查询接口（JOIN/聚合/视图引用）。
 * 全部报表 SQL 直查 rpt 视图（rpt.trial_balance/income_statement/balance_sheet/cash_flow/ar_aging，
 * security_invoker=on 行级按调用者 GUC 收敛）与 IMMV（rpt.budget_actual/dept_expense/cash_flow_daily，
 * pg_ivm automatic 提交即一致）；IMMV 无行级安全，服务层先经 can_access_org 校验目标公司。
 */
public interface RptMapper {

    /**
     * 公司范围校验，IMMV 无 RLS，报表入口须先按会话范围确认目标公司可访问。
     */
    @Select("SELECT auth.can_access_org(#{companyId}::uuid)")
    boolean canAccessOrg(@Param("companyId") UUID companyId);

    /**
     * 试算平衡（rpt.trial_balance 视图）：按（公司×期间）已过账/已冲销凭证分录聚合，科目级
     * 借贷合计与有符号余额（正=借方方向）；direction/opening_balance 由 SQL 补齐（视图无期初
     * 口径，本期发生额视图为期间内，期初置 0——与 gl 既有 trial-balance 视图的累计口径不同，注释声明）。
     */
    @Select("""
            SELECT t.account_id,
                   t.code      AS account_code,
                   t.name      AS account_name,
                   CASE WHEN t.balance >= 0 THEN 'DEBIT' ELSE 'CREDIT' END AS direction,
                   0::numeric(18,2) AS opening_balance,
                   t.total_debit     AS period_debit,
                   t.total_credit    AS period_credit,
                   t.balance         AS closing_balance
              FROM rpt.trial_balance t
             WHERE t.company_id = #{companyId}::uuid
               AND t.period = #{period}
             ORDER BY t.code
            """)
    List<TrialBalanceRow> selectTrialBalance(@Param("companyId") UUID companyId,
                                             @Param("period") String period);

    /**
     * 利润表（rpt.income_statement 视图）：REVENUE 贷方净额计收入、EXPENSE 借方净额计费用，
     * 期间过滤（视图按 company×period 聚合，company_id 可选仅提示，RLS 收敛）。
     * period 显式 jdbcType=VARCHAR：视图 period 列为 char(7)（bpchar），参数缺省时未绑定类型
     * 的 NULL 与 '' 文本比较类型推断冲突（42P18），与 cash-flow/ar-aging 同款写法收敛。
     */
    @Select("""
            SELECT t.period, t.total_revenue, t.total_expense, t.net_profit
              FROM rpt.income_statement t
             WHERE (#{companyId}::uuid IS NULL OR t.company_id = #{companyId}::uuid)
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR t.period = #{period, jdbcType=VARCHAR})
             ORDER BY t.period DESC
            """)
    List<IncomeStatementRow> selectIncomeStatement(@Param("companyId") UUID companyId,
                                                   @Param("period") String period);

    /**
     * 资产负债表（rpt.balance_sheet 视图）：ASSET 借方净额、LIABILITY/EQUITY 贷方净额，按（公司×期间）。
     * period 显式 jdbcType=VARCHAR（与 selectIncomeStatement 一致，避免 42P18）。
     */
    @Select("""
            SELECT t.period, t.total_asset, t.total_liability, t.total_equity
              FROM rpt.balance_sheet t
             WHERE (#{companyId}::uuid IS NULL OR t.company_id = #{companyId}::uuid)
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR t.period = #{period, jdbcType=VARCHAR})
             ORDER BY t.period DESC
            """)
    List<BalanceSheetRow> selectBalanceSheet(@Param("companyId") UUID companyId,
                                             @Param("period") String period);

    /**
     * 现金流量（rpt.cash_flow 视图）：按（公司×月）聚合资金流入/流出/净额；期间列为
     * date_trunc 月截断日期，比较转 'YYYY-MM' 文本口径。
     */
    @Select("""
            SELECT to_char(t.period, 'YYYY-MM') AS period, t.cash_in, t.cash_out, t.net_flow
              FROM rpt.cash_flow t
             WHERE (#{companyId}::uuid IS NULL OR t.company_id = #{companyId}::uuid)
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR to_char(t.period, 'YYYY-MM') = #{period, jdbcType=VARCHAR})
             ORDER BY t.period DESC
            """)
    List<CashFlowRow> selectCashFlow(@Param("companyId") UUID companyId,
                                     @Param("period") String period);

    /**
     * 应收账龄（rpt.ar_aging 视图 LEFT JOIN ar.customers 联查名称）：ISSUED/PARTIAL 发票
     * 未核销余额（total - ar_aging_paid IMMV）；期间（发票月）与 companyId 可选过滤，账龄分桶由 Service 完成。
     */
    @Select("""
            SELECT a.customer_id, c.code AS customer_code, c.name AS customer_name,
                   to_char(a.period, 'YYYY-MM') AS period,
                   a.invoice_id, a.invoice_no, a.invoice_date, a.due_date, a.currency,
                   a.total, a.paid_amount, a.open_balance
              FROM rpt.ar_aging a
              LEFT JOIN ar.customers c ON c.id = a.customer_id AND c.deleted_at IS NULL
             WHERE (#{companyId}::uuid IS NULL OR a.company_id = #{companyId}::uuid)
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR to_char(a.period, 'YYYY-MM') = #{period, jdbcType=VARCHAR})
             ORDER BY a.due_date
            """)
    List<ArAgingRow> selectArAging(@Param("companyId") UUID companyId,
                                   @Param("period") String period);

    /**
     * 部门费用（rpt.dept_expense IMMV LEFT JOIN 部门/科目联查名称）：费用凭证分录按
     * （公司×期间×部门×科目）SUM(debit-credit)，可选期间/部门过滤。
     */
    @Select("""
            SELECT d.period, d.dept_id, dep.name AS dept_name,
                   d.account_id, ca.code AS account_code, ca.name AS account_name,
                   d.amount
              FROM rpt.v_dept_expense d
              LEFT JOIN org.departments dep ON dep.id = d.dept_id AND dep.deleted_at IS NULL
              LEFT JOIN gl.chart_accounts ca ON ca.id = d.account_id
             WHERE d.company_id = #{companyId}::uuid
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR d.period = #{period, jdbcType=VARCHAR})
               AND (#{deptId}::uuid IS NULL OR d.dept_id = #{deptId}::uuid)
             ORDER BY d.period DESC, dep.name, ca.code
            """)
    List<DeptExpenseRow> selectDeptExpense(@Param("companyId") UUID companyId,
                                           @Param("period") String period,
                                           @Param("deptId") UUID deptId);

    /**
     * 看板-资金日报（rpt.cash_flow_daily IMMV）：按（公司×日）聚合收入/支出，近 N 日窗口过滤；
     * 净额 net = income - expense 由 SQL 内联计算——DailyCashRow 构造器 4 参（trxDate,income,
     * expense,net）与 4 列严格对齐，供资金日报与趋势图。
     */
    @Select("""
            SELECT t.trx_date, t.income, t.expense, (t.income - t.expense) AS net
              FROM rpt.v_cash_flow_daily t
             WHERE t.company_id = #{companyId}::uuid
               AND t.trx_date BETWEEN #{from} AND #{to}
             ORDER BY t.trx_date
            """)
    List<DashboardView.DailyCashRow> selectDashboardDailyCash(@Param("companyId") UUID companyId,
                                                              @Param("from") LocalDate from,
                                                              @Param("to") LocalDate to);

    /**
     * 看板-预算执行 top（rpt.budget_actual IMMV 对键 LEFT JOIN 计划金额安全视图）：
     * 计划侧走 budget.v_plan_amount（fms_admin 属主、全公司口径），避免实际数公司口径
     * 与计划按部门 rls 收敛造成同一行在不同观看者下计划额与执行率不一致；
     * 无计划行 plan_amount 与 ratio 返回 NULL，由前端显示为无计划，而非伪装成 0。
     * 对键用 IS NOT DISTINCT FROM（IMMV 中 dept_id 可为 NULL，普通等值连接会漏配）。
     */
    @Select("""
            SELECT a.period, dep.name AS dept_name,
                   ca.code AS account_code, ca.name AS account_name,
                   bp.amount AS plan_amount,
                   a.actual_amount,
                   CASE WHEN bp.amount IS NOT NULL AND bp.amount > 0
                        THEN round((a.actual_amount / bp.amount) * 100, 2) ELSE NULL END AS ratio
              FROM rpt.v_budget_actual a
              LEFT JOIN budget.v_plan_amount bp ON bp.company_id = a.company_id
                   AND bp.period = a.period AND bp.account_id = a.account_id
                   AND bp.dept_id IS NOT DISTINCT FROM a.dept_id
              LEFT JOIN org.departments dep ON dep.id = a.dept_id AND dep.deleted_at IS NULL
              LEFT JOIN gl.chart_accounts ca ON ca.id = a.account_id
             WHERE a.company_id = #{companyId}::uuid
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR a.period = #{period, jdbcType=VARCHAR})
             ORDER BY a.actual_amount DESC
             LIMIT #{limit}
            """)
    List<DashboardView.BudgetTopRow> selectDashboardBudgetTop(@Param("companyId") UUID companyId,
                                                              @Param("period") String period,
                                                              @Param("limit") int limit);

    /**
     * 看板-资金汇总（cash.transactions 直查，RLS can_access_org 行级收敛）：当日 IN 合计=今日收款、
     * 当日 OUT 合计=今日付款、截至当日全部净额（IN-OUT 累计）=可用余额——与 cash.fn_daily_report
     * 的 closing_balance 口径一致（构造器 3 参 income,expense,balance 与 3 列严格对齐）。
     */
    @Select("""
            SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'IN' AND trx_date = #{date}), 0)::numeric(18,2) AS income,
                   COALESCE(SUM(amount) FILTER (WHERE direction = 'OUT' AND trx_date = #{date}), 0)::numeric(18,2) AS expense,
                   COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END), 0)::numeric(18,2) AS balance
              FROM cash.transactions
             WHERE company_id = #{companyId}::uuid
               AND trx_date <= #{date}
               AND deleted_at IS NULL
            """)
    DashboardView.CashSummary selectDashboardCashSummary(@Param("companyId") UUID companyId,
                                                         @Param("date") LocalDate date);

    /**
     * 看板-待办审批汇总（wf.tasks 直查，RLS is_self OR can_access_org 双路径收敛）：PENDING/CLAIMED
     * 可见任务计数 + 其中当日到期（due_at 落在 Asia/Shanghai 日界内——DB 会话时区可能为 UTC，
     * 与 application.yaml jackson.time-zone 同源，业务"今日"以北京时间为准）计数；
     * companyId 仅 UI 提示过滤。
     */
    @Select("""
            SELECT COUNT(*) AS pending_count,
                   COUNT(*) FILTER (WHERE due_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai')
                                                              AT TIME ZONE 'Asia/Shanghai'
                                     AND due_at < (date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai')
                                                   + interval '1 day') AT TIME ZONE 'Asia/Shanghai') AS due_today_count
              FROM wf.tasks
             WHERE status IN ('PENDING', 'CLAIMED')
               AND deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR company_id = #{companyId}::uuid)
            """)
    DashboardView.TodoSummary selectDashboardTodoSummary(@Param("companyId") UUID companyId);

    /**
     * 看板-待办任务行（wf.tasks LEFT JOIN 实例/定义——实例 RLS 对他人 RUNNING 实例不可见，
     * 左连接保证任务行不丢、与待办计数同口径，名称缺失时 Service 拼装兜底标题；
     * PENDING/CLAIMED 可见集 due_at 升序 top N）。
     */
    @Select("""
            SELECT t.id::uuid AS task_id, i.business_type, i.business_id,
                   d.name AS definition_name, t.status, t.due_at
              FROM wf.tasks t
              LEFT JOIN wf.instances i ON i.id = t.instance_id AND i.deleted_at IS NULL
              LEFT JOIN wf.definitions d ON d.id = i.definition_id AND d.deleted_at IS NULL
             WHERE t.status IN ('PENDING', 'CLAIMED')
               AND t.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR t.company_id = #{companyId}::uuid)
             ORDER BY t.due_at, t.created_at, t.id
             LIMIT #{limit}
            """)
    List<DashboardView.TodoRow> selectDashboardTodos(@Param("companyId") UUID companyId,
                                                     @Param("limit") int limit);

    /**
     * 看板-异常告警（调 PG SECURITY DEFINER 函数 rpt.fn_dashboard_alerts：函数内读 um.params
     * 阈值 + 校验公司范围 + 扫描 bank_accounts 密文表，返回 jsonb 数组字符串
     * [{type,kind,period,dept_name,account_name,ratio,plan_amount,actual_amount,threshold} |
     * {type,kind,bank_name,balance,safety_line}]，Service 侧 fastjson2 解析拼装中文文案）。
     */
    @Select("SELECT rpt.fn_dashboard_alerts(#{companyId}::uuid)")
    String selectDashboardAlerts(@Param("companyId") UUID companyId);
}
