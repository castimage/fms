/**
 * 费用报销类型，与后端 Model/vo（ReimbursementView/ReimbursementDetailView/ReimbursementLineView）
 * 及 dto（ReimbursementRequest/ReimbursementLineRequest）逐字段一致。
 * 金额为后端 BigDecimal（json number），展示经 MoneyText / formatAmount。
 */
import type { PageQuery } from './api'

/** 报销状态机经 DRAFT 提交 SUBMITTED、审核 APPROVED、付款 PAID、过账 POSTED，REJECTED 仅自 SUBMITTED，退回可改再提交 */
export type ReimbursementStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PAID' | 'POSTED' | 'REJECTED'

/** 报销单列表行视图，rls 已按本人/本部门/终态收敛可见集合，越权详情则 null 不泄露存在性 */
export interface ReimbursementView {
  id: string
  companyId: string
  applicantId: string
  applicantName: string
  deptId: string
  deptName: string
  title: string
  amount: number
  currency: string
  status: ReimbursementStatus
  wfInstanceId: string | null
  voucherId: string | null
  createdAt: string
  updatedAt: string
}

/** 报销明细行视图，行号升序，金额 > 0 */
export interface ReimbursementLineView {
  id: string
  reimbursementId: string
  lineNo: number
  type: string
  amount: number
  trxDate: string
  summary: string
  invoiceFileId: string | null
}

/** 报销单详情 = 列表视图 + 明细行，头金额 = 明细合计，Service 强制 */
export interface ReimbursementDetailView extends ReimbursementView {
  createdBy: string
  lines: ReimbursementLineView[]
}

/** 明细行请求，type/amount/trxDate/summary 必填，行合计须 = 头金额 */
export interface ReimbursementLineRequest {
  type: string
  amount: number
  trxDate: string
  summary: string
  invoiceFileId?: string
}

/** 报销单创建/更新请求，applicant 恒为当前用户，请求不传，更新仅 DRAFT/REJECTED 生效 */
export interface ReimbursementRequest {
  companyId: string
  title: string
  amount: number
  currency?: string
  deptId: string
  lines: ReimbursementLineRequest[]
}

/** 报销单分页查询，companyId 仅过滤提示 */
export interface ReimbursementQuery extends PageQuery {
  companyId?: string
  status?: ReimbursementStatus | ''
  keyword?: string
}
