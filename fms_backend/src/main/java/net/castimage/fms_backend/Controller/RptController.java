package net.castimage.fms_backend.Controller;

import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.vo.ArAgingRow;
import net.castimage.fms_backend.Model.vo.BalanceSheetRow;
import net.castimage.fms_backend.Model.vo.CashFlowRow;
import net.castimage.fms_backend.Model.vo.DashboardView;
import net.castimage.fms_backend.Model.vo.DeptExpenseRow;
import net.castimage.fms_backend.Model.vo.IncomeStatementRow;
import net.castimage.fms_backend.Model.vo.TrialBalanceView;
import net.castimage.fms_backend.Service.RptService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 报表模块 REST 接口（/api/rpt）：试算平衡/利润表/资产负债表/现金流量表/应收账龄/部门费用分析/
 * 看板聚合 + CSV 导出。身份由安全链路保证，报表 SQL 直查 06 视图（security_invoker=on），
 * RLS 决定可见集合——companyId 仅 UI 提示，越权值返回空集；fms_report 可读全部报表。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rpt")
public class RptController {

    private final RptService rptService;


    /**
     * 试算平衡表（公司+期间必填；balanced = 期末借==贷，恒等断链即异常）。
     */
    @GetMapping("/trial-balance")
    public ApiResponse<TrialBalanceView> trialBalance(@RequestParam UUID companyId,
                                                      @RequestParam String period) {
        return ApiResponse.ok(rptService.trialBalance(companyId, period));
    }

    /**
     * 利润表（companyId/period 可选，RLS 收敛）。
     */
    @GetMapping("/income-statement")
    public ApiResponse<List<IncomeStatementRow>> incomeStatement(@RequestParam(required = false) UUID companyId,
                                                                 @RequestParam(required = false) String period) {
        return ApiResponse.ok(rptService.incomeStatement(companyId, period));
    }

    /**
     * 资产负债表（companyId/period 可选）。
     */
    @GetMapping("/balance-sheet")
    public ApiResponse<List<BalanceSheetRow>> balanceSheet(@RequestParam(required = false) UUID companyId,
                                                           @RequestParam(required = false) String period) {
        return ApiResponse.ok(rptService.balanceSheet(companyId, period));
    }

    /**
     * 现金流量表（companyId/period 可选，period 为 YYYY-MM）。
     */
    @GetMapping("/cash-flow")
    public ApiResponse<List<CashFlowRow>> cashFlow(@RequestParam(required = false) UUID companyId,
                                                   @RequestParam(required = false) String period) {
        return ApiResponse.ok(rptService.cashFlow(companyId, period));
    }

    /**
     * 应收账龄明细（未核销余额；companyId/period 可选）。
     */
    @GetMapping("/ar-aging")
    public ApiResponse<List<ArAgingRow>> arAging(@RequestParam(required = false) UUID companyId,
                                                 @RequestParam(required = false) String period) {
        return ApiResponse.ok(rptService.arAging(companyId, period));
    }

    /**
     * 部门费用分析（公司必填；期间/部门可选）。
     */
    @GetMapping("/dept-expense")
    public ApiResponse<List<DeptExpenseRow>> deptExpense(@RequestParam UUID companyId,
                                                         @RequestParam(required = false) String period,
                                                         @RequestParam(required = false) UUID deptId) {
        return ApiResponse.ok(rptService.deptExpense(companyId, period, deptId));
    }

    /**
     * 看板聚合（资金日报近 30 日 + 预算执行 top10 + 应收账龄分桶；date 缺省当日）。
     */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardView> dashboard(@RequestParam UUID companyId,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(rptService.dashboard(companyId, date));
    }

    /**
     * CSV 导出（report 白名单 trial-balance/income-statement/balance-sheet/cash-flow/ar-aging/dept-expense）：
     * UTF-8 BOM + Content-Disposition attachment，文件名 report_yyyyMMdd.csv。
     */
    @GetMapping("/export/excel")
    public void exportCsv(@RequestParam String report,
                          @RequestParam(required = false) UUID companyId,
                          @RequestParam(required = false) String period,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          HttpServletResponse response) {
        rptService.exportCsv(report, companyId, period, date, response);
    }
}
