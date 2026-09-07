/**
 * 预算 api，/api/budget，14 个端点与 BudgetController 一一对应，
 * 响应壳已在 http.ts 解包，此处仅暴露业务数据。
 */
import { request } from './http'
import type { PageResult } from '@/types/api'
import type {
  BudgetAdjustmentQuery,
  BudgetAdjustmentRequest,
  BudgetAdjustmentView,
  BudgetExecutionQuery,
  BudgetExecutionRow,
  BudgetPlanQuery,
  BudgetPlanRequest,
  BudgetPlanView,
} from '@/types/budget'

export const budgetApi = {
  // 预算计划
  pagePlans(q: BudgetPlanQuery): Promise<PageResult<BudgetPlanView>> {
    return request<PageResult<BudgetPlanView>>({ url: '/budget/plans', method: 'get', params: q })
  },
  getPlan(id: string): Promise<BudgetPlanView | null> {
    return request<BudgetPlanView | null>({ url: `/budget/plans/${id}`, method: 'get' })
  },
  createPlan(req: BudgetPlanRequest): Promise<BudgetPlanView> {
    return request<BudgetPlanView>({ url: '/budget/plans', method: 'post', data: req })
  },
  updatePlan(id: string, req: BudgetPlanRequest): Promise<BudgetPlanView> {
    return request<BudgetPlanView>({ url: `/budget/plans/${id}`, method: 'put', data: req })
  },
  deletePlan(id: string): Promise<void> {
    return request<void>({ url: `/budget/plans/${id}`, method: 'delete' })
  },
  submitPlan(id: string): Promise<BudgetPlanView> {
    return request<BudgetPlanView>({ url: `/budget/plans/${id}/submit`, method: 'post' })
  },
  approvePlan(id: string): Promise<BudgetPlanView> {
    return request<BudgetPlanView>({ url: `/budget/plans/${id}/approve`, method: 'post' })
  },

  // 预算调整单
  pageAdjustments(q: BudgetAdjustmentQuery): Promise<PageResult<BudgetAdjustmentView>> {
    return request<PageResult<BudgetAdjustmentView>>({ url: '/budget/adjustments', method: 'get', params: q })
  },
  getAdjustment(id: string): Promise<BudgetAdjustmentView | null> {
    return request<BudgetAdjustmentView | null>({ url: `/budget/adjustments/${id}`, method: 'get' })
  },
  createAdjustment(req: BudgetAdjustmentRequest): Promise<BudgetAdjustmentView> {
    return request<BudgetAdjustmentView>({ url: '/budget/adjustments', method: 'post', data: req })
  },
  deleteAdjustment(id: string): Promise<void> {
    return request<void>({ url: `/budget/adjustments/${id}`, method: 'delete' })
  },
  approveAdjustment(id: string): Promise<BudgetAdjustmentView> {
    return request<BudgetAdjustmentView>({ url: `/budget/adjustments/${id}/approve`, method: 'post' })
  },
  rejectAdjustment(id: string): Promise<BudgetAdjustmentView> {
    return request<BudgetAdjustmentView>({ url: `/budget/adjustments/${id}/reject`, method: 'post' })
  },

  // 预算执行对比
  execution(q: BudgetExecutionQuery): Promise<BudgetExecutionRow[]> {
    return request<BudgetExecutionRow[]>({ url: '/budget/execution', method: 'get', params: q })
  },
}
