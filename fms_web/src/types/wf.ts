/**
 * 审批流模块类型，与后端 WfController 的 vo/dto 逐字段一致。
 * 状态机，实例经 RUNNING 转 APPROVED/REJECTED，任务经 PENDING/CLAIMED 转 APPROVED/REJECTED/TRANSFERRED。
 */
import type { PageQuery } from './api'

export type WfDefinitionStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED'
export type WfInstanceStatus = 'RUNNING' | 'APPROVED' | 'REJECTED'
export type WfTaskStatus = 'PENDING' | 'CLAIMED' | 'APPROVED' | 'REJECTED' | 'TRANSFERRED'

/** 流程定义创建/更新请求，code 公司内唯一且创建后不可变更 */
export interface WfDefinitionRequest {
  companyId: string
  code: string
  name: string
  formType?: string
  /** 节点编排 json 数组文本，激活前须为非空数组 */
  nodeJson: string
}

/** 流程定义视图，node_json 原样透传展示 */
export interface WfDefinitionView {
  id: string
  companyId: string
  code: string
  name: string
  version: number
  formType: string | null
  nodeJson: string
  status: WfDefinitionStatus
  createdAt: string
  updatedAt: string
}

/** 流程实例视图，join 流程定义 + 发起人登录名，rls 限定本人发起或终态 */
export interface WfInstanceView {
  id: string
  companyId: string
  definitionId: string
  definitionCode: string
  definitionName: string
  businessType: string | null
  businessId: string | null
  initiatorId: string
  initiatorName: string
  currentNode: string | null
  status: WfInstanceStatus
  createdAt: string
}

/** 工作流任务视图，join 实例业务信息 + 流程定义名 + 处理人 */
export interface WfTaskView {
  id: string
  companyId: string
  instanceId: string
  businessType: string | null
  businessId: string | null
  definitionName: string
  nodeCode: string
  assigneeId: string
  assigneeName: string
  taskType: string
  status: WfTaskStatus
  dueAt: string | null
  claimAt: string | null
  version: number
  remark: string | null
  createdAt: string
}

/** 任务事件流水，仅追加，CLAIM/APPROVE/REJECT/TRANSFER/REMIND/TIMEOUT */
export interface WfTaskEventView {
  id: string
  companyId: string
  taskId: string
  action: string
  operatorId: string
  operatorName: string
  fromNode: string | null
  toNode: string | null
  remark: string | null
  occurredAt: string
}

/** 任务动作请求，approve/reject 的 remark 为审批意见/驳回原因，transfer 的 remark 传新处理人 uuid */
export interface TaskActionRequest {
  remark?: string
}

export interface WfDefinitionQuery extends PageQuery {
  companyId?: string
  status?: WfDefinitionStatus
  keyword?: string
}

export interface WfInstanceQuery extends PageQuery {
  companyId?: string
  status?: WfInstanceStatus
  businessType?: string
}
