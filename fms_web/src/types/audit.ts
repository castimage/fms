/**
 * 审计模块类型，与后端 Model/vo（AuditLogView/ChainCheckView）逐字段一致。
 * oldValues/newValues 为 jsonb 透传串，prevHash/rowHash 哈希链两列随行返回。
 */
import type { PageQuery } from './api'

/** 审计日志行视图，rls 仅 fms_auditor 可读 */
export interface AuditLogView {
  id: string
  actorId: string
  action: string
  tableName: string
  recordId: string
  oldValues: string | null
  newValues: string | null
  ip: string
  requestId: string
  occurredAt: string
  prevHash: string
  rowHash: string
}

/** 哈希链校验结果，prevOk=false 时 brokenAt 为首断链点记录 id，detail 为断链详情 jsonb 透传 */
export interface ChainCheckView {
  prevOk: boolean
  brokenAt: string | null
  detail: string | null
}

/** 审计日志分页查询，from/to 为 ISO 日期时间，均可选 */
export interface AuditLogQuery extends PageQuery {
  actorId?: string
  tableName?: string
  /** 目标记录 id，凭证详情审计轨迹按 tableName + recordId 精查 */
  recordId?: string
  action?: string
  from?: string
  to?: string
}
