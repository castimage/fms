package net.castimage.fms_backend.Service.impl;


import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.config.FmsProperties;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Mapper.RptMapper;
import net.castimage.fms_backend.Model.vo.ArAgingRow;
import net.castimage.fms_backend.Model.vo.BalanceSheetRow;
import net.castimage.fms_backend.Model.vo.CashFlowRow;
import net.castimage.fms_backend.Model.vo.DashboardView;
import net.castimage.fms_backend.Model.vo.DashboardView.AgingBucket;
import net.castimage.fms_backend.Model.vo.DashboardView.AlertItem;
import net.castimage.fms_backend.Model.vo.DashboardView.BudgetTopRow;
import net.castimage.fms_backend.Model.vo.DashboardView.CashSummary;
import net.castimage.fms_backend.Model.vo.DashboardView.DailyCashRow;
import net.castimage.fms_backend.Model.vo.DashboardView.TodoItem;
import net.castimage.fms_backend.Model.vo.DashboardView.TodoRow;
import net.castimage.fms_backend.Model.vo.DashboardView.TodoSummary;
import net.castimage.fms_backend.Model.vo.DeptExpenseRow;
import net.castimage.fms_backend.Model.vo.IncomeStatementRow;
import net.castimage.fms_backend.Model.vo.TrialBalanceRow;
import net.castimage.fms_backend.Model.vo.TrialBalanceView;
import net.castimage.fms_backend.Service.RptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 报表模块业务实现，全部报表直查 rpt 视图/immv（security_invoker=on 行级收敛，Java 不拼公司
 * 白名单，companyId 仅 ui 提示，越权值返回空集），看板聚合，资金日报 immv + 预算执行 top +
 * 应收账龄分桶在 Java 侧组合，导出为 utf-8 BOM csv（无 POI 依赖）。
 */
@Service
@Slf4j
public class RptServiceImpl implements RptService {

    /** 金额标度，NUMERIC(18,2) 对齐 */
    private static final int MONEY_SCALE = 2;

    /** 金额零值，标度 2，比较/累加基准 */
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    /** 看板资金日报窗口，近 30 日 */
    private static final int DASHBOARD_DAYS = 30;

    /** 看板预算执行 top N */
    private static final int BUDGET_TOP_N = 10;

    /** 看板待办任务列表条数，due_at 升序 top N */
    private static final int DASHBOARD_TODO_N = 5;

    /** 待办标题业务单号前缀位数，uuid 前 8 位，够区分即可 */
    private static final int TODO_BIZ_ID_PREFIX = 8;

    /** csv 文件名字时间戳 */
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 账龄分桶区间上限，天，0-30/31-60/61-90/91-180/180+ */
    private static final int[] AGING_BUCKET_UPPERS = {30, 60, 90, 180};

    private final RptMapper rptMapper;
    private final FmsProperties fmsProperties;

    /** 导出报表白名单，report 参数枚举，未知值 40001，fms.report.enabled-types 配置驱动，与前端 /api/config 下发一致 */
    private final Set<String> reportTypes;

    public RptServiceImpl(RptMapper rptMapper, FmsProperties fmsProperties) {
        this.rptMapper = rptMapper;
        this.fmsProperties = fmsProperties;
        this.reportTypes = Set.copyOf(fmsProperties.getReport().getEnabledTypes());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public TrialBalanceView trialBalance(UUID companyId, String period) {
        if (companyId == null) {
            throw new BadRequestException("companyId 必填");
        }
        String p = normalizePeriod(period);
        List<TrialBalanceRow> rows = rptMapper.selectTrialBalance(companyId, p);

        // 按符号拆借/贷两列，正=借、负=贷，视图余额为有符号净额，与 gl 既有试算同口径
        BigDecimal openingDebit = ZERO, openingCredit = ZERO;
        BigDecimal periodDebit = ZERO, periodCredit = ZERO;
        BigDecimal closingDebit = ZERO, closingCredit = ZERO;
        for (TrialBalanceRow row : rows) {
            BigDecimal cb = money(row.closingBalance());
            if (cb.signum() > 0) {
                closingDebit = closingDebit.add(cb);
            } else {
                closingCredit = closingCredit.add(cb.abs());
            }
            periodDebit = periodDebit.add(money(row.periodDebit()));
            periodCredit = periodCredit.add(money(row.periodCredit()));
        }
        // 视图无期初口径，期间内发生额，期初合计恒 0，注释声明与累计口径的差异
        return new TrialBalanceView(p, rows,
                openingDebit, openingCredit, periodDebit, periodCredit,
                closingDebit, closingCredit, closingDebit.compareTo(closingCredit) == 0);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<IncomeStatementRow> incomeStatement(UUID companyId, String period) {
        return rptMapper.selectIncomeStatement(companyId, trim(period));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<BalanceSheetRow> balanceSheet(UUID companyId, String period) {
        return rptMapper.selectBalanceSheet(companyId, trim(period));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<CashFlowRow> cashFlow(UUID companyId, String period) {
        String p = trim(period);
        if (p != null && !p.isEmpty()) {
            validatePeriod(p);
        }
        return rptMapper.selectCashFlow(companyId, p);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<ArAgingRow> arAging(UUID companyId, String period) {
        String p = trim(period);
        if (p != null && !p.isEmpty()) {
            validatePeriod(p);
        }
        return rptMapper.selectArAging(companyId, p);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<DeptExpenseRow> deptExpense(UUID companyId, String period, UUID deptId) {
        if (companyId == null) {
            throw new BadRequestException("companyId 必填");
        }
        String p = trim(period);
        if (p != null && !p.isEmpty()) {
            validatePeriod(p);
        }
        return rptMapper.selectDeptExpense(companyId, p, deptId);
    }

    /**
     * {@inheritDoc}
     *
     * 看板聚合七路数据，资金日报，rpt.cash_flow_daily immv 近 30 日，net=income-expense，
     * 预算执行 top（rpt.budget_actual immv 对键最新 APPROVED budget_plans，实际数倒序 top10），

     * 应收账龄分桶，rpt.ar_aging 视图全部未核销行按 due_date 相对 date 分桶合计 open_balance，

     * 资金汇总，cash.transactions 当日 IN/OUT + 截至当日净额，rls 收敛，

     * 待办审批，wf.tasks PENDING/CLAIMED 计数 + 当日到期 + due_at 升序 top N 列表，

     * 异常告警，rpt.fn_dashboard_alerts SECURITY DEFINER，预算执行率阈值 + 银行账户余额安全线，

     * 阈值语义与 budget.fn_cron_budget_warning 同源，jsonb 解析后拼装中文文案。
     * 全部读路径 readOnly 事务，rls 按调用者收敛。
     */
    @Override
    @Transactional(readOnly = true)
    public DashboardView dashboard(UUID companyId, LocalDate date) {
        if (companyId == null) {
            throw new BadRequestException("companyId 必填");
        }
        LocalDate day = date == null ? LocalDate.now() : date;

        // 资金日报，近 30 日窗口，immv 按日聚合，提交即一致
        List<DailyCashRow> cashDaily = rptMapper.selectDashboardDailyCash(
                companyId, day.minusDays(DASHBOARD_DAYS - 1), day).stream()
                .map(r -> new DailyCashRow(r.trxDate(), money(r.income()), money(r.expense()),
                        money(r.income()).subtract(money(r.expense()))))
                .toList();

        // 预算执行 top，当月对键 APPROVED 计划，无计划行 ratio=0
        List<BudgetTopRow> budgetTop = rptMapper.selectDashboardBudgetTop(
                companyId, day.toString().substring(0, 7), BUDGET_TOP_N);

        // 应收账龄分桶，按 due_date 相对查询日分桶，0-30/31-60/61-90/91-180/180+
        List<AgingBucket> agingBuckets = bucketAging(companyId, day);

        // 资金汇总，今日收款/今日付款/可用余额，直查 cash.transactions，与 fn_daily_report 同日口径
        CashSummary cashSummary = rptMapper.selectDashboardCashSummary(companyId, day);

        // 待办审批，PENDING/CLAIMED 计数 + 今日到期 + 精简列表，due_at 升序 top N
        TodoSummary todoSummary = rptMapper.selectDashboardTodoSummary(companyId);
        List<TodoItem> todos = rptMapper.selectDashboardTodos(companyId, DASHBOARD_TODO_N).stream()
                .map(this::toTodoItem)
                .toList();

        // 异常告警，预算执行率阈值 + 银行账户余额安全线，SECURITY DEFINER 函数内读 um.params 阈值
        List<AlertItem> alerts = parseDashboardAlerts(rptMapper.selectDashboardAlerts(companyId));
        return new DashboardView(day, cashDaily, budgetTop, agingBuckets,
                cashSummary, todoSummary, todos, alerts);
    }

    /**
     * {@inheritDoc}
     *
     * 导出白名单校验后按报表类型取数，utf-8 BOM（\uFEFF）流式写 csv，表头 + 数据行 +
     * flush（不 close 输出流，由 Servlet 容器接管），文件名 report_yyyyMMdd.csv，
     * Content-Disposition attachment。csv 字段按 RFC 4180 转义，含逗号/引号/换行时引号包裹、引号加倍。
     */
    @Override
    @Transactional(readOnly = true)
    public void exportCsv(String report, UUID companyId, String period, LocalDate date, HttpServletResponse response) {
        String type = trim(report);
        if (type == null || !reportTypes.contains(type)) {
            throw new BadRequestException("报表类型非法：" + report + "（白名单 " + String.join("/", fmsProperties.getReport().getEnabledTypes()) + "）");
        }
        LocalDate day = date == null ? LocalDate.now() : date;
        try {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=" + type + "_" + day.format(FILE_DATE) + ".csv");
            PrintWriter writer = response.getWriter();
            writer.write('\uFEFF'); // utf-8 BOM，Excel 打开 csv 不乱码，
            CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
            switch (type) {
                case "trial-balance" -> exportTrialBalance(printer, companyId, period);
                case "income-statement" -> exportIncomeStatement(printer, companyId, period);
                case "balance-sheet" -> exportBalanceSheet(printer, companyId, period);
                case "cash-flow" -> exportCashFlow(printer, companyId, period);
                case "ar-aging" -> exportArAging(printer, companyId, period);
                case "dept-expense" -> exportDeptExpense(printer, companyId, period);
                default -> throw new BadRequestException("报表类型非法");
            }
            printer.flush();
            writer.flush();
        } catch (IOException e) {
            throw new net.castimage.fms_backend.Exception.InternalServerException("报表导出失败");
        }
    }

    // csv 导出，每类型表头 + 行，字段 RFC 4180 转义

    private void exportTrialBalance(CSVPrinter p, UUID companyId, String period) throws IOException {
        p.printRecord("科目编码", "科目名称", "方向", "期初余额", "本期借方", "本期贷方", "期末余额");
        for (TrialBalanceRow r : rptMapper.selectTrialBalance(companyId, normalizePeriod(period))) {
            p.printRecord(r.accountCode(), r.accountName(), r.direction(), ZERO,
                    r.periodDebit(), r.periodCredit(), r.closingBalance());
        }
    }

    private void exportIncomeStatement(CSVPrinter p, UUID companyId, String period) throws IOException {
        p.printRecord("期间", "收入", "费用", "净利润");
        for (IncomeStatementRow r : rptMapper.selectIncomeStatement(companyId, trim(period))) {
            p.printRecord(r.period(), r.totalRevenue(), r.totalExpense(), r.netProfit());
        }
    }

    private void exportBalanceSheet(CSVPrinter p, UUID companyId, String period) throws IOException {
        p.printRecord("期间", "资产", "负债", "权益");
        for (BalanceSheetRow r : rptMapper.selectBalanceSheet(companyId, trim(period))) {
            p.printRecord(r.period(), r.totalAsset(), r.totalLiability(), r.totalEquity());
        }
    }

    private void exportCashFlow(CSVPrinter p, UUID companyId, String period) throws IOException {
        p.printRecord("期间", "现金流入", "现金流出", "净流量");
        for (CashFlowRow r : rptMapper.selectCashFlow(companyId, trim(period))) {
            p.printRecord(r.period(), r.cashIn(), r.cashOut(), r.netFlow());
        }
    }

    private void exportArAging(CSVPrinter p, UUID companyId, String period) throws IOException {
        p.printRecord("客户编码", "客户名称", "发票号", "发票日期", "到期日", "币种",
                "发票总额", "已核销", "未核销余额");
        for (ArAgingRow r : rptMapper.selectArAging(companyId, trim(period))) {
            p.printRecord(r.customerCode(), r.customerName(), r.invoiceNo(), r.invoiceDate(),
                    r.dueDate(), r.currency(), r.total(), r.paidAmount(), r.openBalance());
        }
    }

    private void exportDeptExpense(CSVPrinter p, UUID companyId, String period) throws IOException {
        p.printRecord("期间", "部门", "科目编码", "科目名称", "金额");
        for (DeptExpenseRow r : rptMapper.selectDeptExpense(companyId, trim(period), null)) {
            p.printRecord(r.period(), r.deptName(), r.accountCode(), r.accountName(), r.amount());
        }
    }

    // 私有方法

    /**
     * 看板待办任务行转精简项，标题 = 流程名，业务类型 #单号前 8 位待审批，
     * 实例对他人 RUNNING 不可见时名称缺失则兜底"待办任务待审批"，左连接行不丢，
     * dueAt 原样 ISO-8601 透传，前端 dayjs 本地化展示，与待办页 formatDateTime 同源。
     */
    private TodoItem toTodoItem(TodoRow r) {
        String def = r.definitionName();
        String title;
        if (def != null && !def.isBlank()) {
            String biz = r.businessId() == null ? ""
                    : r.businessId().toString().substring(0,
                            Math.min(TODO_BIZ_ID_PREFIX, r.businessId().toString().length()));
            title = String.format("%s（%s #%s）待审批", def,
                    r.businessType() == null ? "-" : r.businessType(), biz);
        } else {
            title = "待办任务待审批";
        }
        return new TodoItem(r.taskId().toString(), title, r.status(),
                r.dueAt() == null ? null : r.dueAt().toString());
    }

    /**
     * 异常告警 jsonb 解析，rpt.fn_dashboard_alerts 返回数组字符串，kind=budget 拼
     * 预算执行率超阈值文案，ratio 为百分比、threshold 为 0-1 比率，kind=bank 拼
     * 银行账户余额低于安全线文案，金额统一 money 标度 2。
     */
    private List<AlertItem> parseDashboardAlerts(String json) {
        List<AlertItem> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JSONArray arr = JSON.parseArray(json);
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if ("budget".equals(o.getString("kind"))) {
                out.add(new AlertItem("warning", String.format("%s %s（%s）预算执行率 %.2f%%，超过预警线 %.0f%%",
                        o.getString("period"), o.getString("dept_name"), o.getString("account_name"),
                        o.getBigDecimal("ratio"),
                        o.getBigDecimal("threshold").multiply(BigDecimal.valueOf(100)))));
            } else {
                out.add(new AlertItem("error", String.format("银行账户 %s 余额 %s 低于安全线 %s",
                        o.getString("bank_name"), money(o.getBigDecimal("balance")),
                        money(o.getBigDecimal("safety_line")))));
            }
        }
        return out;
    }

    /**
     * 应收账龄分桶，按 due_date 相对查询日天数分桶，0-30/31-60/61-90/91-180/180+，
     * 每桶合计 open_balance，已过期行按实际超期天数落桶，未到期，负数落 0-30 桶。
     */
    private List<AgingBucket> bucketAging(UUID companyId, LocalDate day) {
        List<AgingBucket> buckets = new ArrayList<>();
        for (int i = 0; i <= AGING_BUCKET_UPPERS.length; i++) {
            buckets.add(new AgingBucket(bucketName(i), ZERO));
        }
        for (ArAgingRow r : rptMapper.selectArAging(companyId, null)) {
            long age = r.dueDate() == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(r.dueDate(), day));
            int idx = 0;
            while (idx < AGING_BUCKET_UPPERS.length && age > AGING_BUCKET_UPPERS[idx]) {
                idx++;
            }
            AgingBucket b = buckets.get(idx);
            buckets.set(idx, new AgingBucket(b.bucket(), b.amount().add(money(r.openBalance()))));
        }
        return buckets;
    }

    /** 分桶名，0-30/31-60/61-90/91-180/180+ */
    private String bucketName(int idx) {
        if (idx == 0) {
            return "0-30";
        }
        if (idx == AGING_BUCKET_UPPERS.length) {
            return "180+";
        }
        return (AGING_BUCKET_UPPERS[idx - 1] + 1) + "-" + AGING_BUCKET_UPPERS[idx];
    }

    /** 期间校验（YYYY-MM） */
    private void validatePeriod(String period) {
        if (!period.matches("\\d{4}-\\d{2}")) {
            throw new BadRequestException("期间格式非法：" + period + "（须为 YYYY-MM）");
        }
        int month = Integer.parseInt(period.substring(5, 7));
        if (month < 1 || month > 12) {
            throw new BadRequestException("期间月份非法：" + period);
        }
    }

    /** 期间必填校验 + 规范化 */
    private String normalizePeriod(String period) {
        String p = trim(period);
        if (p == null || p.isEmpty()) {
            throw new BadRequestException("期间必填（YYYY-MM）");
        }
        validatePeriod(p);
        return p;
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** 金额规范化（null 则按 0，标度 2） */
    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
