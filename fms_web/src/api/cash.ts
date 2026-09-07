/**
 * 现金银行 api，/api/cash，17 个端点与 CashController 一一对应，
 * 响应壳已在 http.ts 解包，此处仅暴露业务数据。
 */
import { request } from './http'
import type { PageResult } from '@/types/api'
import type {
  BankAccountQuery,
  BankAccountRequest,
  BankAccountView,
  CashTransactionQuery,
  CashTransactionRequest,
  CashTransactionView,
  ManualMatchRequest,
  ReconcileRunQuery,
  ReconcileRunView,
  StatementConfirmRequest,
  StatementDetail,
  StatementImportPreview,
  StatementQuery,
  BankStatementView,
  UndoMatchRequest,
  DailyReportView,
} from '@/types/cash'

export const cashApi = {
  // 银行账户
  pageBankAccounts(q: BankAccountQuery): Promise<PageResult<BankAccountView>> {
    return request<PageResult<BankAccountView>>({ url: '/cash/bank-accounts', method: 'get', params: q })
  },
  getBankAccount(id: string): Promise<BankAccountView | null> {
    return request<BankAccountView | null>({ url: `/cash/bank-accounts/${id}`, method: 'get' })
  },
  createBankAccount(req: BankAccountRequest): Promise<BankAccountView> {
    return request<BankAccountView>({ url: '/cash/bank-accounts', method: 'post', data: req })
  },
  updateBankAccount(id: string, req: BankAccountRequest): Promise<BankAccountView> {
    return request<BankAccountView>({ url: `/cash/bank-accounts/${id}`, method: 'put', data: req })
  },
  deleteBankAccount(id: string): Promise<void> {
    return request<void>({ url: `/cash/bank-accounts/${id}`, method: 'delete' })
  },

  // 收付款流水
  pageTransactions(q: CashTransactionQuery): Promise<PageResult<CashTransactionView>> {
    return request<PageResult<CashTransactionView>>({ url: '/cash/transactions', method: 'get', params: q })
  },
  createTransaction(req: CashTransactionRequest): Promise<CashTransactionView> {
    return request<CashTransactionView>({ url: '/cash/transactions', method: 'post', data: req })
  },
  deleteTransaction(id: string): Promise<void> {
    return request<void>({ url: `/cash/transactions/${id}`, method: 'delete' })
  },

  // 对账单，两阶段导入，import 预览不落库，confirm 再事务落库
  importStatement(file: File, bankAccountId: string): Promise<StatementImportPreview> {
    const form = new FormData()
    form.append('file', file)
    form.append('bankAccountId', bankAccountId)
    return request<StatementImportPreview>({ url: '/cash/statements/import', method: 'post', data: form })
  },
  confirmStatement(req: StatementConfirmRequest): Promise<number> {
    return request<number>({ url: '/cash/statements/confirm', method: 'post', data: req })
  },
  pageStatements(q: StatementQuery): Promise<PageResult<BankStatementView>> {
    return request<PageResult<BankStatementView>>({ url: '/cash/statements', method: 'get', params: q })
  },
  getStatement(id: string): Promise<StatementDetail | null> {
    return request<StatementDetail | null>({ url: `/cash/statements/${id}`, method: 'get' })
  },

  // 对账
  autoReconcile(companyId?: string): Promise<ReconcileRunView> {
    return request<ReconcileRunView>({ url: '/cash/reconcile/auto', method: 'post', params: { companyId } })
  },
  manualMatch(req: ManualMatchRequest): Promise<void> {
    return request<void>({ url: '/cash/reconcile/manual', method: 'post', data: req })
  },
  undoMatch(req: UndoMatchRequest): Promise<void> {
    return request<void>({ url: '/cash/reconcile/undo', method: 'post', data: req })
  },
  pageReconcileRuns(q: ReconcileRunQuery): Promise<PageResult<ReconcileRunView>> {
    return request<PageResult<ReconcileRunView>>({ url: '/cash/reconcile-runs', method: 'get', params: q })
  },

  // 资金日报
  dailyReport(companyId: string, date?: string): Promise<DailyReportView> {
    return request<DailyReportView>({
      url: '/cash/daily-report',
      method: 'get',
      params: { companyId, date },
    })
  },
}
