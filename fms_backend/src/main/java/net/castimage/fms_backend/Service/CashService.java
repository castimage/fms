package net.castimage.fms_backend.Service;

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
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 资金模块业务接口（银行账户/收付款流水/对账单导入/自动+人工对账/资金日报）：
 * 账号密文仅经闸口函数写入、仅经 auth.decrypt_bank_account 读出（列表不选密文列）；
 * 自动对账为 B 类 SQL（金额+日期差≤3 天+pg_trgm 相似度 ≥0.9 自动配对）并落 reconcile_runs 差异统计。
 */
public interface CashService {

    /** 银行账户分页列表（不返回密文；accountNo 恒 null） */
    PageResult<BankAccountView> pageBankAccounts(long page, long size, String keyword);

    /** 银行账户详情（经 auth.decrypt_bank_account 解密返回 accountNo，唯一解密出口） */
    BankAccountView getBankAccount(UUID id);

    /** 新建银行账户（经 cash.fn_create_bank_account 闸口，密文+盲索引函数内落库） */
    BankAccountView createBankAccount(BankAccountRequest req);

    /** 更新银行账户（仅非敏感列；账号变更=新建，禁改密文列） */
    BankAccountView updateBankAccount(UUID id, BankAccountRequest req);

    /** 软删银行账户（deleted_at=now()；RLS 无 DELETE 策略，一律软删） */
    void deleteBankAccount(UUID id);

    /** 流水分页列表（join 银行账户名；direction/matchStatus 可选过滤） */
    PageResult<CashTransactionView> pageTransactions(long page, long size, UUID bankAccountId,
                                                     String direction, String matchStatus, UUID companyId);

    /** 新建收付款流水（company_id 由银行账户推导；初始 UNMATCHED） */
    CashTransactionView createTransaction(CashTransactionRequest req);

    /** 软删流水（仅 UNMATCHED 可删，已配对流水 40900） */
    void deleteTransaction(UUID id);

    /** 对账单导入第一阶段：UTF-8 CSV/TSV 内存解析，返回预览行（不落库） */
    StatementImportPreview importStatement(MultipartFile file, UUID bankAccountId);

    /** 对账单导入第二阶段：预览行事务落库（bank_statements + 批量行，total_lines 回填） */
    int confirmStatement(StatementConfirmRequest req);

    /** 对账单分页列表 */
    PageResult<BankStatementView> pageStatements(long page, long size, UUID bankAccountId);

    /** 对账单详情（头 + 行明细；RLS 空集返回 null） */
    BankStatementView getStatement(UUID id);

    /** 对账单行明细（详情接口数据源，头+行拼装） */
    List<BankStatementLineView> statementLines(UUID statementId);

    /** 自动对账（B 类 SQL：金额+日期差≤3 天+trgm 相似度≥0.9 贪心配对），落运行统计并返回 */
    ReconcileRunView autoReconcile(UUID companyId);

    /** 人工对账（双向原子配对，0 行则 40900"已被消费"） */
    void manualMatch(ManualMatchRequest req);

    /** 撤销对账（双向回置 UNMATCHED 并清相似度/配对时间） */
    void undoMatch(UndoMatchRequest req);

    /** 对账运行批次分页列表（差异计数口径：autoMatched/manualMatched/diffCount） */
    PageResult<ReconcileRunView> pageReconcileRuns(long page, long size, UUID companyId);

    /** 资金日报（fn_daily_report jsonb 解析 + 未来 7 天预测 B 类 SQL） */
    DailyReportView dailyReport(UUID companyId, LocalDate date);
}
