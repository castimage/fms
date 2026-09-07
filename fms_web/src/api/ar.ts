/**
 * 应收 api，与 ArController 一一对应，24 个端点，客户/发票 CRUD + 状态机（issue/void）、
 * 收款登记 + 核销/冲销、账龄分析、坏账计提，封装后返回业务数据 T，错误抛 ApiError。
 */
import { request } from './http'
import type { PageResult } from '@/types/api'
import type {
  AgingRow,
  AllocateRequest,
  AllocationView,
  ArInvoiceRequest,
  ArInvoiceView,
  BadDebtRequest,
  BadDebtView,
  BadDebtQuery,
  CustomerQuery,
  CustomerRequest,
  CustomerView,
  InvoiceQuery,
  ReceiptQuery,
  ReceiptRequest,
  ReceiptView,
} from '@/types/ar'

export const arApi = {
  // 客户
  pageCustomers(q: CustomerQuery): Promise<PageResult<CustomerView>> {
    return request<PageResult<CustomerView>>({ url: '/ar/customers', method: 'get', params: q })
  },
  getCustomer(id: string): Promise<CustomerView | null> {
    return request<CustomerView | null>({ url: `/ar/customers/${id}`, method: 'get' })
  },
  createCustomer(req: CustomerRequest): Promise<CustomerView> {
    return request<CustomerView>({ url: '/ar/customers', method: 'post', data: req })
  },
  updateCustomer(id: string, req: CustomerRequest): Promise<CustomerView> {
    return request<CustomerView>({ url: `/ar/customers/${id}`, method: 'put', data: req })
  },
  deleteCustomer(id: string): Promise<void> {
    return request<void>({ url: `/ar/customers/${id}`, method: 'delete' })
  },

  // 发票，状态机经 DRAFT 开票 ISSUED，再 PARTIAL、PAID，可 VOID
  pageInvoices(q: InvoiceQuery): Promise<PageResult<ArInvoiceView>> {
    return request<PageResult<ArInvoiceView>>({ url: '/ar/invoices', method: 'get', params: q })
  },
  getInvoice(id: string): Promise<ArInvoiceView | null> {
    return request<ArInvoiceView | null>({ url: `/ar/invoices/${id}`, method: 'get' })
  },
  createInvoice(req: ArInvoiceRequest): Promise<ArInvoiceView> {
    return request<ArInvoiceView>({ url: '/ar/invoices', method: 'post', data: req })
  },
  updateInvoice(id: string, req: ArInvoiceRequest): Promise<ArInvoiceView> {
    return request<ArInvoiceView>({ url: `/ar/invoices/${id}`, method: 'put', data: req })
  },
  deleteInvoice(id: string): Promise<void> {
    return request<void>({ url: `/ar/invoices/${id}`, method: 'delete' })
  },
  issueInvoice(id: string): Promise<ArInvoiceView> {
    return request<ArInvoiceView>({ url: `/ar/invoices/${id}/issue`, method: 'post' })
  },
  voidInvoice(id: string): Promise<ArInvoiceView> {
    return request<ArInvoiceView>({ url: `/ar/invoices/${id}/void`, method: 'post' })
  },

  // 收款与核销
  pageReceipts(q: ReceiptQuery): Promise<PageResult<ReceiptView>> {
    return request<PageResult<ReceiptView>>({ url: '/ar/receipts', method: 'get', params: q })
  },
  getReceipt(id: string): Promise<ReceiptView | null> {
    return request<ReceiptView | null>({ url: `/ar/receipts/${id}`, method: 'get' })
  },
  createReceipt(req: ReceiptRequest): Promise<ReceiptView> {
    return request<ReceiptView>({ url: '/ar/receipts', method: 'post', data: req })
  },
  deleteReceipt(id: string): Promise<void> {
    return request<void>({ url: `/ar/receipts/${id}`, method: 'delete' })
  },
  /** 核销，receiptId 以路径为准，后端覆盖请求体同字段防改路径 */
  allocate(receiptId: string, req: Omit<AllocateRequest, 'receiptId'>): Promise<AllocationView> {
    return request<AllocationView>({ url: `/ar/receipts/${receiptId}/allocate`, method: 'post', data: req })
  },
  unallocate(allocationId: string): Promise<void> {
    return request<void>({ url: `/ar/allocations/${allocationId}/unallocate`, method: 'post' })
  },
  /** 冲销，reason 可空，审计追溯 */
  reverseReceipt(id: string, reason?: string): Promise<void> {
    return request<void>({ url: `/ar/receipts/${id}/reverse`, method: 'post', data: { reason } })
  },

  // 账龄 / 坏账
  aging(companyId?: string): Promise<AgingRow[]> {
    return request<AgingRow[]>({ url: '/ar/aging', method: 'get', params: { companyId } })
  },
  pageBadDebts(q: BadDebtQuery): Promise<PageResult<BadDebtView>> {
    return request<PageResult<BadDebtView>>({ url: '/ar/bad-debt', method: 'get', params: q })
  },
  createBadDebt(req: BadDebtRequest): Promise<BadDebtView> {
    return request<BadDebtView>({ url: '/ar/bad-debt', method: 'post', data: req })
  },
  confirmBadDebt(id: string): Promise<BadDebtView> {
    return request<BadDebtView>({ url: `/ar/bad-debt/${id}/confirm`, method: 'post' })
  },
  deleteBadDebt(id: string): Promise<void> {
    return request<void>({ url: `/ar/bad-debt/${id}`, method: 'delete' })
  },
}
