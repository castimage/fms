/**
 * 应付 api，/api/ap，26 端点与 ApController 一一对应，供应商/发票/付款核销/供应商对账，
 * 封装后返回业务数据 T，错误抛 ApiError。
 */
import { request } from './http'
import type { PageResult } from '@/types/api'
import type {
  AllocationView,
  ApAgingRow,
  ApInvoiceRequest,
  ApInvoiceView,
  InvoiceQuery,
  MatchLineRequest,
  PaymentAllocateRequest,
  PaymentQuery,
  PaymentRequest,
  PaymentView,
  ReconciliationDetailView,
  ReconciliationImportRequest,
  ReconciliationQuery,
  ReconciliationRequest,
  ReconciliationView,
  ReverseReceiptRequest,
  SupplierQuery,
  SupplierRequest,
  SupplierView,
} from '@/types/ap'

export const apApi = {
  // 供应商
  pageSuppliers(q: SupplierQuery): Promise<PageResult<SupplierView>> {
    return request<PageResult<SupplierView>>({ url: '/ap/suppliers', method: 'get', params: q })
  },
  getSupplier(id: string): Promise<SupplierView | null> {
    return request<SupplierView | null>({ url: `/ap/suppliers/${id}`, method: 'get' })
  },
  createSupplier(req: SupplierRequest): Promise<SupplierView> {
    return request<SupplierView>({ url: '/ap/suppliers', method: 'post', data: req })
  },
  updateSupplier(id: string, req: SupplierRequest): Promise<SupplierView> {
    return request<SupplierView>({ url: `/ap/suppliers/${id}`, method: 'put', data: req })
  },
  deleteSupplier(id: string): Promise<void> {
    return request<void>({ url: `/ap/suppliers/${id}`, method: 'delete' })
  },

  // 发票，状态机经 DRAFT 开票 ISSUED，再 PARTIAL、PAID，可 VOID
  pageInvoices(q: InvoiceQuery): Promise<PageResult<ApInvoiceView>> {
    return request<PageResult<ApInvoiceView>>({ url: '/ap/invoices', method: 'get', params: q })
  },
  getInvoice(id: string): Promise<ApInvoiceView | null> {
    return request<ApInvoiceView | null>({ url: `/ap/invoices/${id}`, method: 'get' })
  },
  createInvoice(req: ApInvoiceRequest): Promise<ApInvoiceView> {
    return request<ApInvoiceView>({ url: '/ap/invoices', method: 'post', data: req })
  },
  updateInvoice(id: string, req: ApInvoiceRequest): Promise<ApInvoiceView> {
    return request<ApInvoiceView>({ url: `/ap/invoices/${id}`, method: 'put', data: req })
  },
  deleteInvoice(id: string): Promise<void> {
    return request<void>({ url: `/ap/invoices/${id}`, method: 'delete' })
  },
  issueInvoice(id: string): Promise<ApInvoiceView> {
    return request<ApInvoiceView>({ url: `/ap/invoices/${id}/issue`, method: 'post' })
  },
  voidInvoice(id: string): Promise<ApInvoiceView> {
    return request<ApInvoiceView>({ url: `/ap/invoices/${id}/void`, method: 'post' })
  },

  // 付款与核销，防重双闸，uk_ap_alloc + version 乐观锁
  pagePayments(q: PaymentQuery): Promise<PageResult<PaymentView>> {
    return request<PageResult<PaymentView>>({ url: '/ap/payments', method: 'get', params: q })
  },
  getPayment(id: string): Promise<PaymentView | null> {
    return request<PaymentView | null>({ url: `/ap/payments/${id}`, method: 'get' })
  },
  createPayment(req: PaymentRequest): Promise<PaymentView> {
    return request<PaymentView>({ url: '/ap/payments', method: 'post', data: req })
  },
  deletePayment(id: string): Promise<void> {
    return request<void>({ url: `/ap/payments/${id}`, method: 'delete' })
  },
  allocate(paymentId: string, items: PaymentAllocateRequest[]): Promise<AllocationView[]> {
    return request<AllocationView[]>({ url: `/ap/payments/${paymentId}/allocate`, method: 'post', data: items })
  },
  unallocate(allocationId: string): Promise<void> {
    return request<void>({ url: `/ap/allocations/${allocationId}/unallocate`, method: 'post' })
  },
  reverse(paymentId: string, req?: ReverseReceiptRequest): Promise<void> {
    return request<void>({ url: `/ap/payments/${paymentId}/reverse`, method: 'post', data: req })
  },

  // 账龄，后端 rpt.ap_aging 未核销余额分桶，一次取全量
  aging(companyId?: string): Promise<ApAgingRow[]> {
    return request<ApAgingRow[]>({ url: '/ap/aging', method: 'get', params: { companyId } })
  },

  // 供应商对账，核对动作，不改发票/付款状态
  pageReconciliations(q: ReconciliationQuery): Promise<PageResult<ReconciliationView>> {
    return request<PageResult<ReconciliationView>>({ url: '/ap/reconciliations', method: 'get', params: q })
  },
  getReconciliation(id: string): Promise<ReconciliationDetailView | null> {
    return request<ReconciliationDetailView | null>({ url: `/ap/reconciliations/${id}`, method: 'get' })
  },
  createReconciliation(req: ReconciliationRequest): Promise<ReconciliationView> {
    return request<ReconciliationView>({ url: '/ap/reconciliations', method: 'post', data: req })
  },
  deleteReconciliation(id: string): Promise<void> {
    return request<void>({ url: `/ap/reconciliations/${id}`, method: 'delete' })
  },
  importLines(id: string, req: ReconciliationImportRequest): Promise<void> {
    return request<void>({ url: `/ap/reconciliations/${id}/lines`, method: 'post', data: req })
  },
  matchLine(id: string, req: MatchLineRequest): Promise<void> {
    return request<void>({ url: `/ap/reconciliations/${id}/match`, method: 'post', data: req })
  },
  confirmReconciliation(id: string): Promise<ReconciliationView> {
    return request<ReconciliationView>({ url: `/ap/reconciliations/${id}/confirm`, method: 'post' })
  },
}
