package net.castimage.fms_backend.Service;

import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Model.vo.ArAgingRow;
import net.castimage.fms_backend.Model.vo.BalanceSheetRow;
import net.castimage.fms_backend.Model.vo.CashFlowRow;
import net.castimage.fms_backend.Model.vo.DashboardView;
import net.castimage.fms_backend.Model.vo.DeptExpenseRow;
import net.castimage.fms_backend.Model.vo.IncomeStatementRow;
import net.castimage.fms_backend.Model.vo.TrialBalanceView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 报表模块业务接口（rpt）：全部直查 06 文件视图/IMMV（security_invoker=on 行级收敛，Java 不拼公司白名单），
 *  看板聚合 + UTF-8 BOM CSV 导出（无 POI 依赖，Excel 兼容） */
public interface RptService {

    /**
     * 试算平衡表（rpt.trial_balance 视图）：分科目期间借贷合计与余额 + 汇总与平衡标志
     * （balanced = 期末借方合计 == 期末贷方合计，恒等断链即异常）。
     */
    TrialBalanceView trialBalance(UUID companyId, String period);

    /**
     * 利润表（companyId/period 可选；RLS 收敛可见集合）。
     */
    List<IncomeStatementRow> incomeStatement(UUID companyId, String period);

    /**
     * 资产负债表（companyId/period 可选）。
     */
    List<BalanceSheetRow> balanceSheet(UUID companyId, String period);

    /**
     * 现金流量表（companyId/period 可选，period 为 YYYY-MM 月报口径）。
     */
    List<CashFlowRow> cashFlow(UUID companyId, String period);

    /**
     * 应收账龄（未核销余额明细；分桶由看板完成）。
     */
    List<ArAgingRow> arAging(UUID companyId, String period);

    /**
     * 部门费用分析（公司必填；期间/部门可选）。
     */
    List<DeptExpenseRow> deptExpense(UUID companyId, String period, UUID deptId);

    /**
     * 看板聚合：资金日报（cash_flow_daily IMMV 近 30 日）+ 预算执行 top10（budget_actual 对键
     * APPROVED 计划）+ 应收账龄分桶（ar_aging 按 due_date 相对 date 分桶 0-30/31-60/61-90/91-180/180+）。
     */
    DashboardView dashboard(UUID companyId, LocalDate date);

    /**
     * 报表导出（report 白名单 trial-balance/income-statement/balance-sheet/cash-flow/ar-aging/dept-expense）：
     * UTF-8 BOM CSV 流式写出，Content-Disposition attachment，文件名 report_yyyyMMdd.csv。
     */
    void exportCsv(String report, UUID companyId, String period, LocalDate date, HttpServletResponse response);
}
