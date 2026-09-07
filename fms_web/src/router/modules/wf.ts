/**
 * 审批流模块路由，children 形式与 index.ts gl 段一致，待办 + 实例两页，
 * 懒加载 + meta{title, icon}，接入主路由时挂到 Layout children，path 相对 'wf'。
 */
import type { RouteRecordRaw } from 'vue-router'

export const wfRoutes: RouteRecordRaw[] = [
  {
    path: 'wf',
    name: 'wf',
    redirect: '/wf/todos',
    meta: { title: '审批流', icon: 'sync' },
    children: [
      {
        path: 'todos',
        name: 'wf-todos',
        component: () => import('@/pages/wf/TodoList.vue'),
        meta: { title: '我的待办', icon: 'checkbox', keepAlive: true },
      },
      {
        path: 'instances',
        name: 'wf-instances',
        component: () => import('@/pages/wf/ProcessList.vue'),
        meta: { title: '流程实例', icon: 'grid', keepAlive: true },
      },
      {
        path: 'definitions',
        name: 'wf-definitions',
        component: () => import('@/pages/wf/DefinitionList.vue'),
        meta: { title: '流程定义', icon: 'settings', roles: ['admin'], keepAlive: true },
      },
    ],
  },
]

export default wfRoutes
