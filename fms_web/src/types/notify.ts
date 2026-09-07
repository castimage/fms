/**
 * 通知模块类型，与后端 NotificationView 逐字段一致，isRead=false 行供未读数与列表角标。
 */
import type { PageQuery } from './api'

export interface NotificationView {
  id: string
  title: string
  content: string
  type: string
  refType: string | null
  refId: string | null
  isRead: boolean
  createdAt: string
  readAt: string | null
}

/** 通知分页查询，isRead 可选，true=已读 / false=未读 / 缺省=全部 */
export interface NotifyQuery extends PageQuery {
  isRead?: boolean
}
