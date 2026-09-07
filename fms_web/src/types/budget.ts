/**
 * 预算模块类型，与后端 BudgetController 的 vo/dto 逐字段一致，BigDecimal 以 number 承接。
 * 状态机，计划经 DRAFT 提交 SUBMITTED 再 APPROVED，调整单经 DRAFT 转 APPROVED/REJECTED。
 */
import type { PageQuery } from './api'

export type BudgetPlanStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED'
export type BudgetAdjustmentStatus = 'DRAFT' | 'APPROVED' | 'REJECTED'
/** 执行级别，warn 阈值 80%、block 阈值 100%，与 db 触发器同源 */
export type BudgetLevel = 'NORMAL' | 'WARN' | 'BLOCK'

/** 预算计划创建/更新请求，amount > 0，同键位重复 40900 */
export interface BudgetPlanRequest {
  companyId: string
  period: string
  accountId: string
  deptId: string
  amount: number
}

/** 预算计划视图，join 科目编码/名称 + 部门名 */
export interface BudgetPlanView {
  id: string
  companyId: string
  period: string
  accountId: string
  accountCode: string
  accountName: string
  deptId: string
  deptName: string
  amount: number
  version: number
  status: BudgetPlanStatus
  approvedBy: string | null
  approvedAt: string | null
  createdBy: string
  createdAt: string
}

/** 预算调整单请求，amount 正=追加 负=调减，<> 0 */
export interface BudgetAdjustmentRequest {
  planId: string
  amount: number
  reason: string
}

/** 预算调整单视图，join 父计划带出期间 */
export interface BudgetAdjustmentView {
  id: string
  companyId: string
  planId: string
  period: string
  amount: number
  reason: string
  status: BudgetAdjustmentStatus
  adjustedBy: string | null
  adjustedAt: string | null
  createdAt: string
}

/** 预算执行对比行，plan LEFT JOIN rpt.budget_actual immv + 已批调整净额 */
export interface BudgetExecutionRow {
  planId: string
  companyId: string
  period: string
  accountId: string
  accountCode: string
  accountName: string
  deptId: string
  deptName: string
  planAmount: number
  actualAmount: number
  adjustedAmount: number
  /** plan + adjusted - actual */
  availableAmount: number
  /** (actual + adjusted) / plan，标度 4，0.85 = 85% */
  ratio: number
  overLimit: boolean
  level: BudgetLevel
}

export interface BudgetPlanQuery extends PageQuery {
  companyId?: string
  period?: string
  deptId?: string
  status?: BudgetPlanStatus
}

export interface BudgetAdjustmentQuery extends PageQuery {
  companyId?: string
  planId?: string
  status?: BudgetAdjustmentStatus
}

export interface BudgetExecutionQuery {
  companyId?: string
  period?: string
  deptId?: string
}
