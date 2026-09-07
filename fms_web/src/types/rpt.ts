/**
 * 报表模块类型，与后端 Model/vo（DashboardView/IncomeStatementRow/BalanceSheetRow/CashFlowRow/
 * ArAgingRow/DeptExpenseRow）逐字段一致，金额 BigDecimal 转 number，试算平衡复用 types/gl。
 */
import type { PageQuery } from './api'

/** 导出报告白名单，编译期类型，运行期以 GET /api/config 下发的 fms.report.enabled-types 为准，后端未知值 40001 */
export type ReportType =
  | 'trial-balance'
  | 'income-statement'
  | 'balance-sheet'
  | 'cash-flow'
  | 'ar-aging'
  | 'dept-expense'

/** 资金日报行，immv 单行聚合，net = income - expense 服务端已算 */
export interface DailyCashRow {
  trxDate: string
  income: number
  expense: number
  net: number
}

/** 预算执行排行行，无计划行 planAmount=0/ratio=0，ratio 为百分比 0-100 */
export interface BudgetTopRow {
  period: string
  deptName: string
  accountCode: string
  accountName: string
  planAmount: number
  actualAmount: number
  ratio: number
}

/** 应收账龄分桶，bucket 名 0-30/31-60/61-90/91-180/180+，amount 为该桶未核销余额合计 */
export interface AgingBucket {
  bucket: string
  amount: number
}

/** 资金汇总，cash.transactions 当日 IN=今日收款/OUT=今日付款，balance=截至当日净额=可用余额 */
export interface CashSummary {
  income: number
  expense: number
  balance: number
}

/** 待办审批汇总，wf.tasks PENDING/CLAIMED 可见集计数 + 当日到期数 */
export interface TodoSummary {
  pendingCount: number
  dueTodayCount: number
}

/** 看板待办任务精简行，dueAt ISO-8601，前端 dayjs 本地化展示 */
export interface DashboardTodoItem {
  taskId: string
  title: string
  status: string
  dueAt: string | null
}

/** 异常告警行，type 对应 n-alert 类型，text 为后端拼装的中文提示 */
export interface DashboardAlertItem {
  type: 'warning' | 'error'
  text: string
}

/** 看板聚合，date 缺省当日，cashDaily 近 30 日，alerts 预算阈值 + 银行安全线 */
export interface DashboardView {
  date: string
  cashDaily: DailyCashRow[]
  budgetTop: BudgetTopRow[]
  agingBuckets: AgingBucket[]
  cashSummary: CashSummary
  todoSummary: TodoSummary
  todos: DashboardTodoItem[]
  alerts: DashboardAlertItem[]
}

/** 利润表行，REVENUE 贷方净额/EXPENSE 借方净额，仅 POSTED/REVERSED 凭证 */
export interface IncomeStatementRow {
  period: string
  totalRevenue: number
  totalExpense: number
  netProfit: number
}

/** 资产负债表行，恒等式 totalAsset = totalLiability + totalEquity 由 ui 校验 */
export interface BalanceSheetRow {
  period: string
  totalAsset: number
  totalLiability: number
  totalEquity: number
}

/** 现金流量表行，月粒度，period 为 YYYY-MM */
export interface CashFlowRow {
  period: string
  cashIn: number
  cashOut: number
  netFlow: number
}

/** 应收账龄行，openBalance = total - 累计已核销额 */
export interface ArAgingRow {
  customerId: string
  customerCode: string
  customerName: string
  period: string
  invoiceId: string
  invoiceNo: string
  invoiceDate: string
  dueDate: string
  currency: string
  total: number
  paidAmount: number
  openBalance: number
}

/** 部门费用行，公司必填，期间/部门可选 */
export interface DeptExpenseRow {
  period: string
  deptId: string
  deptName: string
  accountId: string
  accountCode: string
  accountName: string
  amount: number
}

/** 报表查询，companyId/period 可选，rls 收敛可见集合 */
export interface RptQuery extends PageQuery {
  companyId?: string
  period?: string
}
