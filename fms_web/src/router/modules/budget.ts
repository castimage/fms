/**
 * 预算模块路由，children 形式与 index.ts gl 段一致，编制列表 + 执行对比两页，
 * 懒加载 + meta{title, icon}，接入主路由时挂到 Layout children，path 相对 'budget'。
 */
import type { RouteRecordRaw } from 'vue-router'

export const budgetRoutes: RouteRecordRaw[] = [
  {
    path: 'budget',
    name: 'budget',
    redirect: '/budget/plans',
    meta: { title: '预算管理', icon: 'wallet' },
    children: [
      {
        path: 'plans',
        name: 'budget-plans',
        component: () => import('@/pages/budget/BudgetList.vue'),
        meta: { title: '预算编制', icon: 'document', keepAlive: true },
      },
      {
        path: 'execution',
        name: 'budget-execution',
        component: () => import('@/pages/budget/BudgetExecPage.vue'),
        meta: { title: '预算执行', icon: 'trend', keepAlive: true },
      },
    ],
  },
]

export default budgetRoutes
