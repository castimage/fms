/**
 * 费用报销 api，/exp，10 端点与 ExpenseController 一一对应，报销单 CRUD + 状态机
 * 提交/审批/驳回/付款，db 触发器终审，非法流转 40900。
 */
import { request } from './http'
import type { PageResult } from '@/types/api'
import type {
  ReimbursementDetailView,
  ReimbursementQuery,
  ReimbursementRequest,
  ReimbursementView,
} from '@/types/expense'

export const expenseApi = {
  // 报销单
  pageReimbursements(q: ReimbursementQuery): Promise<PageResult<ReimbursementView>> {
    return request<PageResult<ReimbursementView>>({ url: '/exp/reimbursements', method: 'get', params: q })
  },
  // 本人报销，applicant = 当前用户，rls is_self 兜底，仅状态/关键词过滤，无 companyId
  pageMyReimbursements(q: Omit<ReimbursementQuery, 'companyId'>): Promise<PageResult<ReimbursementView>> {
    return request<PageResult<ReimbursementView>>({ url: '/exp/my-reimbursements', method: 'get', params: q })
  },
  getReimbursement(id: string): Promise<ReimbursementDetailView | null> {
    return request<ReimbursementDetailView | null>({ url: `/exp/reimbursements/${id}`, method: 'get' })
  },
  createReimbursement(req: ReimbursementRequest): Promise<ReimbursementDetailView> {
    return request<ReimbursementDetailView>({ url: '/exp/reimbursements', method: 'post', data: req })
  },
  updateReimbursement(id: string, req: ReimbursementRequest): Promise<ReimbursementDetailView> {
    return request<ReimbursementDetailView>({ url: `/exp/reimbursements/${id}`, method: 'put', data: req })
  },
  deleteReimbursement(id: string): Promise<void> {
    return request<void>({ url: `/exp/reimbursements/${id}`, method: 'delete' })
  },

  // 状态机流转
  submitReimbursement(id: string): Promise<ReimbursementDetailView> {
    return request<ReimbursementDetailView>({ url: `/exp/reimbursements/${id}/submit`, method: 'post' })
  },
  approveReimbursement(id: string): Promise<ReimbursementDetailView> {
    return request<ReimbursementDetailView>({ url: `/exp/reimbursements/${id}/approve`, method: 'post' })
  },
  rejectReimbursement(id: string): Promise<ReimbursementDetailView> {
    return request<ReimbursementDetailView>({ url: `/exp/reimbursements/${id}/reject`, method: 'post' })
  },
  payReimbursement(id: string): Promise<ReimbursementDetailView> {
    return request<ReimbursementDetailView>({ url: `/exp/reimbursements/${id}/pay`, method: 'post' })
  },
}
