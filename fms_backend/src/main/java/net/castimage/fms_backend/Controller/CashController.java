package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.BankAccountRequest;
import net.castimage.fms_backend.Model.dto.CashTransactionRequest;
import net.castimage.fms_backend.Model.dto.ManualMatchRequest;
import net.castimage.fms_backend.Model.dto.StatementConfirmRequest;
import net.castimage.fms_backend.Model.dto.UndoMatchRequest;
import net.castimage.fms_backend.Model.vo.BankAccountView;
import net.castimage.fms_backend.Model.vo.BankStatementLineView;
import net.castimage.fms_backend.Model.vo.BankStatementView;
import net.castimage.fms_backend.Model.vo.CashTransactionView;
import net.castimage.fms_backend.Model.vo.DailyReportView;
import net.castimage.fms_backend.Model.vo.ReconcileRunView;
import net.castimage.fms_backend.Model.vo.StatementImportPreview;
import net.castimage.fms_backend.Service.CashService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 资金模块 REST 接口（/api/cash）：银行账户 CRUD（密文列不对外）、收付款流水、
 * 对账单导入两阶段（import 预览不落库，confirm 再事务落库）、自动+人工对账、
 * 对账运行批次（差异统计）、资金日报（含未来 7 天预测）；
 * 身份由安全链路保证，RLS 决定可见集合——列表越权空集、详情越权 data:null、写路径 40400/40900。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cash")
public class CashController {

    private final CashService cashService;


    // 银行账户

    /**
     * 银行账户分页列表（不返回密文列，accountNo 恒 null；掩码由创建请求携带）。
     */
    @GetMapping("/bank-accounts")
    public ApiResponse<PageResult<BankAccountView>> pageBankAccounts(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(cashService.pageBankAccounts(page, size, keyword));
    }

    /**
     * 银行账户详情（账号解密唯一出口：经 auth.decrypt_bank_account 返回明文 accountNo；
     * 越权则 RLS 空集，回 data:null）。
     */
    @GetMapping("/bank-accounts/{id}")
    public ApiResponse<BankAccountView> getBankAccount(@PathVariable UUID id) {
        return ApiResponse.ok(cashService.getBankAccount(id));
    }

    /**
     * 新建银行账户（经闸口 cash.fn_create_bank_account：密文+盲索引函数内落库；
     * uk_bank_account_hash 冲突 40900）。
     */
    @PostMapping("/bank-accounts")
    public ApiResponse<BankAccountView> createBankAccount(@RequestBody @Valid BankAccountRequest req) {
        return ApiResponse.ok(cashService.createBankAccount(req));
    }

    /**
     * 更新银行账户（仅非敏感列 bankName/accountType/currency/status/isActive；账号变更=新建）。
     */
    @PutMapping("/bank-accounts/{id}")
    public ApiResponse<BankAccountView> updateBankAccount(@PathVariable UUID id,
                                                          @RequestBody @Valid BankAccountRequest req) {
        return ApiResponse.ok(cashService.updateBankAccount(id, req));
    }

    /**
     * 软删银行账户（deleted_at=now()）。
     */
    @DeleteMapping("/bank-accounts/{id}")
    public ApiResponse<Void> deleteBankAccount(@PathVariable UUID id) {
        cashService.deleteBankAccount(id);
        return ApiResponse.ok(null);
    }

    // 收付款流水

    /**
     * 流水分页列表（join 银行账户名；direction/matchStatus 可选过滤；RLS 越权空集）。
     */
    @GetMapping("/transactions")
    public ApiResponse<PageResult<CashTransactionView>> pageTransactions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID bankAccountId,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String matchStatus,
            @RequestParam(required = false) UUID companyId) {
        return ApiResponse.ok(cashService.pageTransactions(page, size, bankAccountId, direction,
                matchStatus, companyId));
    }

    /**
     * 新建收付款流水（company_id 由银行账户推导；初始 UNMATCHED）。
     */
    @PostMapping("/transactions")
    public ApiResponse<CashTransactionView> createTransaction(@RequestBody @Valid CashTransactionRequest req) {
        return ApiResponse.ok(cashService.createTransaction(req));
    }

    /**
     * 软删流水（仅 UNMATCHED 可删，已配对 40900）。
     */
    @DeleteMapping("/transactions/{id}")
    public ApiResponse<Void> deleteTransaction(@PathVariable UUID id) {
        cashService.deleteTransaction(id);
        return ApiResponse.ok(null);
    }

    // 对账单导入（两阶段）

    /**
     * 导入第一阶段：UTF-8 CSV/TSV 内存解析，返回预览行（不落库）；
     * 前端核对后经 /statements/confirm 携带预览行事务落库。
     */
    @PostMapping("/statements/import")
    public ApiResponse<StatementImportPreview> importStatement(@RequestParam("file") MultipartFile file,
                                                               @RequestParam UUID bankAccountId) {
        return ApiResponse.ok(cashService.importStatement(file, bankAccountId));
    }

    /**
     * 导入第二阶段：预览行事务落库（bank_statements + 批量行，total_lines 回填），
     * 返回落库行数。
     */
    @PostMapping("/statements/confirm")
    public ApiResponse<Integer> confirmStatement(@RequestBody @Valid StatementConfirmRequest req) {
        return ApiResponse.ok(cashService.confirmStatement(req));
    }

    /**
     * 对账单分页列表（join 银行账户名）。
     */
    @GetMapping("/statements")
    public ApiResponse<PageResult<BankStatementView>> pageStatements(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID bankAccountId) {
        return ApiResponse.ok(cashService.pageStatements(page, size, bankAccountId));
    }

    /**
     * 对账单详情（头 + 行明细；越权则 data:null）。
     */
    @GetMapping("/statements/{id}")
    public ApiResponse<StatementDetail> getStatement(@PathVariable UUID id) {
        BankStatementView stmt = cashService.getStatement(id);
        if (stmt == null) {
            return ApiResponse.ok(null);
        }
        return ApiResponse.ok(new StatementDetail(stmt, cashService.statementLines(id)));
    }

    /** 对账单详情视图（头 + 行明细拼装） */
    public record StatementDetail(BankStatementView statement, List<BankStatementLineView> lines) {
    }

    // 对账

    /**
     * 自动对账（B 类 SQL：金额+日期差≤3 天+trgm 相似度≥0.9 贪心配对），
     * 返回本次运行统计（autoMatched/diffCount）。
     */
    @PostMapping("/reconcile/auto")
    public ApiResponse<ReconcileRunView> autoReconcile(@RequestParam(required = false) UUID companyId) {
        return ApiResponse.ok(cashService.autoReconcile(companyId));
    }

    /**
     * 人工对账（双向原子配对，0 行则 40900"已被消费"）。
     */
    @PostMapping("/reconcile/manual")
    public ApiResponse<Void> manualMatch(@RequestBody @Valid ManualMatchRequest req) {
        cashService.manualMatch(req);
        return ApiResponse.ok(null);
    }

    /**
     * 撤销对账（双向回置 UNMATCHED 并清相似度/配对时间）。
     */
    @PostMapping("/reconcile/undo")
    public ApiResponse<Void> undoMatch(@RequestBody @Valid UndoMatchRequest req) {
        cashService.undoMatch(req);
        return ApiResponse.ok(null);
    }

    /**
     * 对账运行批次分页（对账差异调整的量化口径：autoMatched/manualMatched/diffCount）。
     */
    @GetMapping("/reconcile-runs")
    public ApiResponse<PageResult<ReconcileRunView>> pageReconcileRuns(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId) {
        return ApiResponse.ok(cashService.pageReconcileRuns(page, size, companyId));
    }

    // 资金日报

    /**
     * 资金日报（期初/当日收付/期末 + 分账户明细 + 未来 7 天预测）。
     */
    @GetMapping("/daily-report")
    public ApiResponse<DailyReportView> dailyReport(@RequestParam UUID companyId,
                                                    @RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(cashService.dailyReport(companyId, date));
    }
}
