/**
 * 固定资产路由，children 形式与 router/index.ts gl 段一致，资产卡片 + 折旧记录两页，
 * 懒加载 + meta{title, icon}，接入主路由时挂到 Layout children，path 相对 'fa'。
 */
import type { RouteRecordRaw } from 'vue-router'

const faRoutes: RouteRecordRaw[] = [
  {
    path: 'fa',
    name: 'fa',
    redirect: '/fa/assets',
    meta: { title: '固定资产', icon: 'box' },
    children: [
      {
        path: 'assets',
        name: 'fa-assets',
        component: () => import('@/pages/fa/AssetList.vue'),
        meta: { title: '资产卡片', icon: 'cube', keepAlive: true },
      },
      {
        path: 'depreciation',
        name: 'fa-depreciation',
        component: () => import('@/pages/fa/DepreciationList.vue'),
        meta: { title: '折旧记录', icon: 'trend', keepAlive: true },
      },
    ],
  },
]

export default faRoutes
