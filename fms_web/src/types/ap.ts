/**
 * 应付模块类型，与后端 Model/vo（SupplierView/ApInvoiceView/PaymentView/AllocationView/
 * ReconciliationView/...）及 dto（SupplierRequest/ApInvoiceRequest/PaymentRequest/...）逐字段一致。
 * 金额为后端 BigDecimal（json number），展示经 utils/format.formatAmount / MoneyText。
 */
import type { PageQuery } from './api'

export type SupplierStatus = 'ACTIVE' | 'INACTIVE' | 'BLOCKED'
/** 发票状态机经 DRAFT 开票 ISSUED，再 PARTIAL、PAID，可 VOID，与 ar 同构，PARTIAL=部分核销 */
export type ApInvoiceStatus = 'DRAFT' | 'ISSUED' | 'PARTIAL' | 'PAID' | 'VOID'
export type PaymentStatus = 'PAID' | 'ALLOCATED' | 'REVERSED'
export type ReconciliationStatus = 'DRAFT' | 'CONFIRMED' | 'VOID'
export type MatchStatus = 'UNMATCHED' | 'MATCHED' | 'MANUAL'

/** 供应商视图，列表/详情共用，越权则 data:null */
export interface SupplierView {
  id: string
  companyId: string
  code: string
  name: string
  taxNo: string | null
  creditLimit: number
  status: SupplierStatus
  createdAt: string
}

/** 供应商创建/更新请求，code 公司内唯一，status 缺省 ACTIVE */
export interface SupplierRequest {
  companyId: string
  code: string
  name: string
  taxNo?: string
  creditLimit?: number
  status?: SupplierStatus
}

/** 应付发票视图，join 供应商名，付款余额 = total - 已核销，余额后端未暴露 */
export interface ApInvoiceView {
  id: string
  companyId: string
  supplierId: string
  supplierName: string
  invoiceNo: string
  invoiceDate: string
  dueDate: string | null
  currency: string
  total: number
  tax: number
  status: ApInvoiceStatus
  voucherId: string | null
  version: number
  createdAt: string
}

/** 发票创建/更新请求，开票后不可变，更新仅 DRAFT 生效 */
export interface ApInvoiceRequest {
  companyId: string
  supplierId: string
  invoiceNo: string
  invoiceDate: string
  dueDate?: string
  currency?: string
  total: number
  tax?: number
}

/** 付款单视图，join 供应商/银行账户名，bankName 空 = 未指定付款账户 */
export interface PaymentView {
  id: string
  companyId: string
  supplierId: string
  supplierName: string
  amount: number
  bankAccountId: string | null
  bankName: string | null
  paymentDate: string
  status: PaymentStatus
  voucherId: string | null
  createdAt: string
}

/** 付款登记请求，登记即付款，status=PAID */
export interface PaymentRequest {
  companyId: string
  supplierId: string
  amount: number
  bankAccountId?: string
  paymentDate: string
}

/** 付款核销单项，同对 payment+invoice 由 uk_ap_alloc 防重复，合计不得超过付款金额 */
export interface PaymentAllocateRequest {
  invoiceId: string
  amount: number
}

/** 核销明细视图，字段沿用收款单命名 receiptId，version 供乐观锁回传 */
export interface AllocationView {
  id: string
  receiptId: string
  invoiceId: string
  invoiceNo: string
  invoiceDate: string
  amount: number
  version: number
  createdAt: string
}

/** 付款冲销请求，reason 可选，供审计追溯 */
export interface ReverseReceiptRequest {
  reason?: string
}

/** 对账单头视图，join 供应商名，行明细经 getReconciliation 返回 */
export interface ReconciliationView {
  id: string
  companyId: string
  supplierId: string
  supplierName: string
  statementDate: string
  status: ReconciliationStatus
  createdAt: string
}

/** 对账单行视图，matchedInvoiceNo 空 = 未匹配，similarityScore 为候选建议 */
export interface ReconciliationLineView {
  id: string
  reconciliationId: string
  trxDate: string
  amount: number
  summary: string | null
  matchStatus: MatchStatus
  matchedInvoiceId: string | null
  matchedInvoiceNo: string | null
  similarityScore: number | null
  createdAt: string
}

/** 对账单详情，头 + 行明细 */
export interface ReconciliationDetailView {
  header: ReconciliationView
  lines: ReconciliationLineView[]
}

/** 对账单创建请求，DRAFT 起步，行导入/匹配经 lines/match/confirm 端点推进 */
export interface ReconciliationRequest {
  companyId: string
  supplierId: string
  statementDate: string
}

/** 对账单行导入请求，全部落库 UNMATCHED，候选 top1>0.6 自动回写建议，不自动匹配 */
export interface ReconciliationImportRequest {
  lines: {
    trxDate: string
    amount: number
    summary?: string
  }[]
}

/** 对账行人工匹配请求，WHERE UNMATCHED 原子守卫防双配，matched_invoice_id 仅回填不核销 */
export interface MatchLineRequest {
  lineId: string
  invoiceId: string
}

/** 账龄分桶行（GET /api/ap/aging），供应商 × 账龄桶，0-30/31-60/61-90/91-180/180+未核销余额，后端 rpt.ap_aging 窗口函数聚合 */
export interface ApAgingRow {
  supplierId: string
  supplierName: string
  bucket: string
  openBalance: number
}

/** 供应商分页查询，keyword 走 trgm 相似度 + ILIKE 兜底 */
export interface SupplierQuery extends PageQuery {
  companyId?: string
  keyword?: string
}

/** 发票分页查询，supplierId/status/keyword 过滤 */
export interface InvoiceQuery extends PageQuery {
  companyId?: string
  supplierId?: string
  status?: ApInvoiceStatus | ''
  keyword?: string
}

/** 付款单分页查询，supplierId/status 过滤 */
export interface PaymentQuery extends PageQuery {
  companyId?: string
  supplierId?: string
  status?: PaymentStatus | ''
}

/** 对账单分页查询，supplierId/status 过滤 */
export interface ReconciliationQuery extends PageQuery {
  companyId?: string
  supplierId?: string
  status?: ReconciliationStatus | ''
}
