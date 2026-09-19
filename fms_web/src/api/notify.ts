/**
 * 通知 api，/api/notify，4 端点与 NotifyController 一一对应，本人通知分页/未读数/单条已读/全部已读。
 * WebSocket 端点 /api/nt/ws，NotifyWebSocketEndpoint，网关按同路径精确匹配升级请求并转发，
 * 握手在 query 携带访问令牌，网关校验后按令牌主体改写 uid 再转发，服务端连接即回推未读数。
 */
import { request } from './http'
import type { PageResult } from '@/types/api'
import type { NotificationView, NotifyQuery } from '@/types/notify'

/** 通知实时通道地址，网关直通 /api/nt/ws，握手 query 携带访问令牌，服务端即时回推未读数 */
export const NOTIFY_WS_URL = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/nt/ws`

export const notifyApi = {
  pageList(q: NotifyQuery): Promise<PageResult<NotificationView>> {
    return request<PageResult<NotificationView>>({ url: '/notify/list', method: 'get', params: q })
  },
  unreadCount(): Promise<number> {
    return request<number>({ url: '/notify/unread-count', method: 'get' })
  },
  markRead(id: string): Promise<void> {
    return request<void>({ url: `/notify/${id}/read`, method: 'post' })
  },
  /** 全部已读，幂等，返回本次置读行数 */
  markAllRead(): Promise<number> {
    return request<number>({ url: '/notify/read-all', method: 'post' })
  },
  /**
   * 系统公告广播，仅 ADMIN，向全部 ACTIVE 用户插 SYSTEM 通知 + ws 全量推送。
   * 后端返回 Void，无条数统计，前端文案不依赖 count。
   */
  announce(title: string, content: string): Promise<void> {
    return request<void>({ url: '/notify/announcements', method: 'post', data: { title, content } })
  },
}
