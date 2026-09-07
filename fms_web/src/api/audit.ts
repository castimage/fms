/**
 * 审计 api，/api/audit，3 端点与 AuditController 一一对应，日志分页/哈希链校验/csv 导出。
 * 全部只读路径，rls 仅 fms_auditor 可读，其他角色 40300。
 */
import { request } from './http'
import { downloadCsv } from './rpt'
import type { PageResult } from '@/types/api'
import type { AuditLogQuery, AuditLogView, ChainCheckView } from '@/types/audit'

export const auditApi = {
  pageLogs(q: AuditLogQuery): Promise<PageResult<AuditLogView>> {
    return request<PageResult<AuditLogView>>({ url: '/audit/logs', method: 'get', params: q })
  },
  chainCheck(): Promise<ChainCheckView> {
    return request<ChainCheckView>({ url: '/audit/chain-check', method: 'get' })
  },
  /** csv 导出，同分页过滤条件全量，文件名 audit_yyyyMMdd.csv */
  exportCsv(q: Omit<AuditLogQuery, 'page' | 'size'> = {}): Promise<void> {
    return downloadCsv('/audit/export/csv', { ...q })
  },
}
