/**
 * 通知 api，/api/notify，4 端点与 NotifyController 一一对应，本人通知分页/未读数/单条已读/全部已读。
 * WebSocket 端点 /ws/notify，NotifyWebSocketEndpoint，连接即推送未读数，本批不做真实订阅，
 * NOTIFY_WS_URL 仅作连接示例提示。
 */
import { request } from './http'
import type { PageResult } from '@/types/api'
import type { NotificationView, NotifyQuery } from '@/types/notify'

/** WebSocket 连接示例，网关直通 /ws/notify，握手须带 uid 查询参数，服务端即时回推未读数 */
export const NOTIFY_WS_URL = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/notify`

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
