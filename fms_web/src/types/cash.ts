/**
 * 现金银行模块类型，与后端 CashController 的 vo/dto 逐字段一致，
 * 金额为 BigDecimal，json number 承接，展示统一走 MoneyText。
 */
import type { PageQuery } from './api'

export type CashDirection = 'IN' | 'OUT'
export type MatchStatus = 'UNMATCHED' | 'MATCHED'
export type BankAccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED'
export type BankAccountType = 'CORPORATE' | 'PERSONAL'

/** 银行账户视图，列表 sql 不选密文列，accountNo 恒 null，仅详情接口解密返回 */
export interface BankAccountView {
  id: string
  companyId: string
  bankName: string
  accountNo: string | null
  accountNoMasked: string | null
  accountType: BankAccountType
  currency: string
  isActive: boolean
  status: BankAccountStatus
  createdAt: string
  updatedAt: string
}

/** 银行账户创建/更新请求，accountNo 仅创建时生效，更新忽略，掩码不落库 */
export interface BankAccountRequest {
  bankName: string
  accountNo: string
  accountNoMasked?: string
  accountType?: BankAccountType
  currency?: string
  status?: BankAccountStatus
  isActive?: boolean
}

/** 收付款流水视图，join 银行账户名 */
export interface CashTransactionView {
  id: string
  companyId: string
  bankAccountId: string
  bankName: string
  direction: CashDirection
  amount: number
  partner: string | null
  summary: string | null
  trxDate: string
  matchStatus: MatchStatus
  matchedLineId: string | null
  similarityScore: number | null
  reconciledAt: string | null
  createdAt: string
}

/** 收付款流水创建请求，company 由银行账户推导，trxDate 缺省当日，初始 UNMATCHED */
export interface CashTransactionRequest {
  bankAccountId: string
  direction: CashDirection
  amount: number
  partner?: string
  summary?: string
  trxDate?: string
}

/** 对账单导入预览行，import 内存解析产物，不落库 */
export interface StatementLinePreview {
  trxDate: string
  amount: number
  summary: string
  direction: CashDirection
}

/** 对账单导入预览结果，两阶段第一步 */
export interface StatementImportPreview {
  bankAccountId: string
  filename: string
  totalLines: number
  rows: StatementLinePreview[]
}

/** 对账单确认落库请求，携带预览行，事务落库并回填 total_lines */
export interface StatementConfirmRequest {
  bankAccountId: string
  filename: string
  rows: StatementLinePreview[]
}

/** 银行对账单视图，分页列表，join 银行账户名 */
export interface BankStatementView {
  id: string
  companyId: string
  bankAccountId: string
  bankName: string
  filename: string
  importDate: string
  status: string
  totalLines: number | null
  createdAt: string
}

/** 银行对账单行视图，matchedTrxId 双向互指流水 */
export interface BankStatementLineView {
  id: string
  statementId: string
  companyId: string
  trxDate: string
  amount: number
  summary: string | null
  direction: CashDirection
  matchStatus: MatchStatus
  matchedTrxId: string | null
  similarityScore: number | null
  createdAt: string
}

/** 对账单详情，头 + 行明细 */
export interface StatementDetail {
  statement: BankStatementView
  lines: BankStatementLineView[]
}

/** 对账运行批次视图，autoMatched/manualMatched/diffCount 为差异量化口径 */
export interface ReconcileRunView {
  id: string
  companyId: string
  period: string | null
  runAt: string
  autoMatched: number
  manualMatched: number
  diffCount: number
  createdAt: string
}

/** 人工对账请求，双向原子配对，0 行则 40900 已被消费 */
export interface ManualMatchRequest {
  trxId: string
  lineId: string
  companyId: string
}

/** 对账撤销请求，双向回置 UNMATCHED */
export interface UndoMatchRequest {
  trxId: string
  lineId: string
}

/** 资金日报-分账户收付行 */
export interface AccountDailyRow {
  bankAccountId: string
  income: number
  expense: number
}

/** 资金日报-未来 7 天预测行，应收到期 - 应付到期 */
export interface DailyForecastRow {
  date: string
  arDue: number
  apDue: number
  netDue: number
}

/** 资金日报视图，期初/收付/期末 + 分账户 + 未来 7 天预测 */
export interface DailyReportView {
  date: string
  openingBalance: number
  income: number
  expense: number
  net: number
  closingBalance: number
  byAccount: AccountDailyRow[]
  forecast: DailyForecastRow[]
}

/** 银行账户分页查询，keyword 匹配开户行/掩码 */
export interface BankAccountQuery extends PageQuery {
  keyword?: string
}

/** 流水分页查询，direction/matchStatus 可选过滤 */
export interface CashTransactionQuery extends PageQuery {
  bankAccountId?: string
  direction?: CashDirection
  matchStatus?: MatchStatus
  companyId?: string
}

/** 对账单分页查询 */
export interface StatementQuery extends PageQuery {
  bankAccountId?: string
}

/** 对账批次分页查询 */
export interface ReconcileRunQuery extends PageQuery {
  companyId?: string
}
