/**
 * 通知模块路由，children 形式与 index.ts gl 段一致，单页通知中心，
 * 懒加载 + meta{title, icon}，接入主路由时挂到 Layout children，path 相对 'notify'。
 */
import type { RouteRecordRaw } from 'vue-router'

export const notifyRoutes: RouteRecordRaw[] = [
  {
    path: 'notify',
    name: 'notify',
    component: () => import('@/pages/notify/NotifyList.vue'),
    meta: { title: '通知中心', icon: 'mail', keepAlive: true },
  },
]

export default notifyRoutes
