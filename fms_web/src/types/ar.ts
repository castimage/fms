/**
 * 应收模块类型，与后端 Model/vo（CustomerView/ArInvoiceView/ReceiptView/AllocationView/
 * BadDebtView/AgingRow）及 dto（CustomerRequest/ArInvoiceRequest/ReceiptRequest/
 * AllocateRequest/BadDebtRequest）逐字段一致，金额为后端 BigDecimal（json number）。
 */
import type { PageQuery } from './api'

export type CustomerStatus = 'ACTIVE' | 'INACTIVE' | 'BLOCKED'
export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PARTIAL' | 'PAID' | 'VOID'
export type ReceiptStatus = 'RECEIVED' | 'ALLOCATED' | 'REVERSED'
export type BadDebtStatus = 'DRAFT' | 'CONFIRMED' | 'REVERSED'

/** 客户视图，列表/详情共用，详情越权则 data:null */
export interface CustomerView {
  id: string
  companyId: string
  code: string
  name: string
  taxNo: string | null
  creditLimit: number | null
  status: CustomerStatus
  createdAt: string
}

/** 客户创建/更新请求，创建 code 必填，status 缺省 ACTIVE */
export interface CustomerRequest {
  companyId: string
  code: string
  name: string
  taxNo?: string
  creditLimit?: number
  status?: CustomerStatus
}

/** 应收发票视图，核销余额 = total - 已核销 */
export interface ArInvoiceView {
  id: string
  companyId: string
  customerId: string
  customerName: string
  invoiceNo: string
  invoiceDate: string
  dueDate: string | null
  currency: string
  total: number
  tax: number
  status: InvoiceStatus
  voucherId: string | null
  version: number
  createdAt: string
}

/** 发票创建/更新请求，开票后不可变，更新仅 DRAFT 生效 */
export interface ArInvoiceRequest {
  companyId: string
  customerId: string
  invoiceNo: string
  invoiceDate: string
  dueDate?: string
  currency?: string
  total: number
  tax?: number
}

/** 收款单视图，bankName 为空表示未指定收款账户 */
export interface ReceiptView {
  id: string
  companyId: string
  customerId: string
  customerName: string
  amount: number
  bankAccountId: string | null
  bankName: string | null
  receiptDate: string
  status: ReceiptStatus
  voucherId: string | null
  createdAt: string
}

/** 收款登记请求 */
export interface ReceiptRequest {
  companyId: string
  customerId: string
  amount: number
  bankAccountId?: string
  receiptDate: string
}

/** 核销请求体，receiptId 以路径为准，后端覆盖同字段防改路径 */
export interface AllocateRequest {
  receiptId: string
  invoiceId: string
  amount: number
}

/** 核销明细视图，allocate 响应，unallocate 以 id 定位 */
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

/** 坏账计提视图，确认后禁改禁删 */
export interface BadDebtView {
  id: string
  companyId: string
  invoiceId: string
  invoiceNo: string
  period: string
  amount: number
  status: BadDebtStatus
  createdAt: string
}

/** 坏账计提请求（(invoice_id, period) 查重防重复计提） */
export interface BadDebtRequest {
  invoiceId: string
  period: string
  amount: number
}

/** 账龄分桶行，bucket，0-30/31-60/61-90/91-180/180+，未核销余额 */
export interface AgingRow {
  customerId: string
  customerName: string
  bucket: string
  openBalance: number
}

export interface CustomerQuery extends PageQuery {
  companyId?: string
  keyword?: string
}

export interface InvoiceQuery extends PageQuery {
  companyId?: string
  customerId?: string
  status?: InvoiceStatus | ''
  keyword?: string
}

export interface ReceiptQuery extends PageQuery {
  companyId?: string
  customerId?: string
  status?: ReceiptStatus | ''
}

export interface BadDebtQuery extends PageQuery {
  companyId?: string
  status?: BadDebtStatus | ''
}
