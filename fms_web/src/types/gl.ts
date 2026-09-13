/**
 * 总账模块类型，与后端 Model/vo（AccountView/VoucherListView/VoucherDetailView/VoucherLineView/
 * LedgerView/TrialBalanceView/TrialBalanceRow）及 dto（AccountRequest/VoucherCreateRequest/...）逐字段一致。
 * 金额为后端 BigDecimal，json number/string 皆可，前端统一经 utils/money.ts 的"分"整数路径计算。
 */
import type { PageQuery } from './api'

export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE'
export type BalanceDirection = 'DEBIT' | 'CREDIT'
export type AccountStatus = 'ACTIVE' | 'DISABLED'
export type VoucherStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'POSTED' | 'REVERSED' | 'REJECTED'

/** 科目视图，扁平数组按 path ltree 自然序，前端建树 */
export interface AccountView {
  id: string
  companyId: string
  code: string
  name: string
  path: string
  parentId: string | null
  level: number
  isLeaf: boolean
  accountType: AccountType
  direction: BalanceDirection
  isForeignCurrency: boolean
  currency: string | null
  status: AccountStatus
}

/** 凭证列表行视图 */
export interface VoucherListView {
  id: string
  companyId: string
  voucherNo: number | null
  period: string
  voucherDate: string
  status: VoucherStatus
  sourceType: string
  isPrivate: boolean
  deptId: string | null
  createdBy: string
  createdByName: string
  approvedBy: string | null
  approvedAt: string | null
  postedBy: string | null
  postedAt: string | null
  totalDebit: number
  totalCredit: number
  note: string | null
  createdAt: string
}

/** 凭证分录视图 */
export interface VoucherLineView {
  id: string
  voucherId: string
  lineNo: number
  accountId: string
  accountCode: string
  accountName: string
  summary: string | null
  debit: number
  credit: number
  currency: string | null
  deptId: string | null
  partnerId: string | null
  companyId: string
}

/** 凭证详情 = 列表视图 + 冲销关联 + 分录 */
export interface VoucherDetailView extends VoucherListView {
  reversalOfVoucherId: string | null
  reversedByVoucherId: string | null
  lines: VoucherLineView[]
}

/** 分录请求行，编辑态，debit/credit 互斥非负不可双零，科目须末级且 ACTIVE */
export interface VoucherLineRequest {
  lineNo?: number
  accountId: string
  summary?: string
  debit?: string
  credit?: string
  currency?: string
  deptId?: string | null
  partnerId?: string | null
}

/** 凭证创建/更新请求，更新时期间不可变更 */
export interface VoucherCreateRequest {
  companyId: string
  period: string
  voucherDate?: string
  deptId?: string | null
  isPrivate?: boolean
  note?: string
  lines: VoucherLineRequest[]
}

/** 凭证分页查询 */
export interface VoucherQuery extends PageQuery {
  period?: string
  status?: VoucherStatus | ''
  companyId?: string
  keyword?: string
  createdByMe?: boolean
}

/** 总账行，有符号净额，正=借向，负=贷向 */
export interface LedgerView {
  accountId: string
  accountCode: string
  accountName: string
  direction: string
  openingBalance: number
  periodDebit: number
  periodCredit: number
  closingBalance: number
}

/** 试算平衡行，余额有符号，汇总行在 View 层 */
export interface TrialBalanceRow {
  accountId: string
  accountCode: string
  accountName: string
  direction: string
  openingBalance: number
  periodDebit: number
  periodCredit: number
  closingBalance: number
}

/** 试算平衡响应，balanced=false 即凭证链断链异常 */
export interface TrialBalanceView {
  period: string
  rows: TrialBalanceRow[]
  openingDebitTotal: number
  openingCreditTotal: number
  periodDebitTotal: number
  periodCreditTotal: number
  closingDebitTotal: number
  closingCreditTotal: number
  balanced: boolean
}

/** 凭证断号视图，GET /api/gl/voucher-gaps 行，镜像 gl.fn_cron_voucher_number_check，
 * gapFrom~gapTo 为缺失号段，单号缺口 gapFrom==gapTo 且 gapCount=1，仅统计 POSTED/REVERSED 已取号凭证 */
export interface VoucherGapView {
  companyId: string
  period: string
  gapFrom: number
  gapTo: number
  gapCount: number
}

/** 期末汇率视图，gl.fx_rates 实体直出，rate=1 单位外币折本币，无汇率记录的外币科目调汇时跳过 */
export interface FxRateView {
  id: string
  companyId: string
  /** 3 位 ISO 币种代码 */
  currency: string
  /** 期间 YYYY-MM */
  period: string
  /** 期末汇率（>0） */
  rate: number
  updatedBy: string | null
  updatedAt: string | null
  createdAt: string | null
}

/** 期末汇率维护请求，PUT/POST 均 upsert，同公司×币种×期间已存在则更新 rate，软删行复活 */
export interface FxRateRequest {
  companyId: string
  currency: string
  period: string
  rate: number
}

/** 科目创建/更新请求，更新时 code/path 禁改 */
export interface AccountRequest {
  companyId?: string | null
  code: string
  name: string
  parentId?: string | null
  accountType: AccountType
  direction: BalanceDirection
  isForeignCurrency?: boolean
  currency?: string | null
  status?: AccountStatus
}
