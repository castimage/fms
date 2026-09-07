/**
 * 报表模块路由，结构对照 modules/ap.ts，懒加载，meta 契约见 router/index.ts。
 * 报表为只读角色共享，rls 收敛可见集合，不设 roles 限制。
 */
import type { RouteRecordRaw } from 'vue-router'

const rptRoutes: RouteRecordRaw[] = [
  {
    path: 'rpt',
    name: 'rpt',
    redirect: '/rpt/home',
    meta: { title: '报表中心', icon: 'trend' },
    children: [
      {
        path: 'home',
        name: 'rpt-home',
        component: () => import('@/pages/rpt/ReportHome.vue'),
        meta: { title: '报表中心', icon: 'trend', keepAlive: true },
      },
      {
        path: 'statements',
        name: 'rpt-statements',
        component: () => import('@/pages/rpt/StatementPage.vue'),
        meta: { title: '财务报表', icon: 'grid', keepAlive: true },
      },
    ],
  },
]

export default rptRoutes
