/**
 * 审计模块路由，结构对照 modules/ap.ts，日志分页 + 链完整性校验，后端 rls 仅 auditor/admin 可读，
 * 前端 roles 过滤为体验优化，数据安全由后端兜底。
 */
import type { RouteRecordRaw } from 'vue-router'

const auditRoutes: RouteRecordRaw[] = [
  {
    path: 'audit',
    name: 'audit',
    redirect: '/audit/logs',
    meta: { title: '审计', icon: 'shield', roles: ['auditor', 'admin'] },
    children: [
      {
        path: 'logs',
        name: 'audit-logs',
        component: () => import('@/pages/audit/AuditLogList.vue'),
        meta: { title: '审计日志', icon: 'document', roles: ['auditor', 'admin'], keepAlive: true },
      },
    ],
  },
]

export default auditRoutes
