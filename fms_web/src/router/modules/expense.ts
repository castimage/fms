/**
 * 费用报销路由，children 形式与 router/index.ts gl 段一致，报销列表 + 申请单，新建/详情共用，
 * 懒加载 + meta{title, icon}，接入主路由时挂到 Layout children，path 相对 'expense'。
 */
import type { RouteRecordRaw } from 'vue-router'

const expenseRoutes: RouteRecordRaw[] = [
  {
    path: 'expense',
    name: 'expense',
    redirect: '/expense/reimbursements',
    meta: { title: '费用报销', icon: 'cash' },
    children: [
      {
        path: 'reimbursements',
        name: 'expense-reimbursements',
        component: () => import('@/pages/expense/ExpenseList.vue'),
        meta: { title: '报销申请', icon: 'document', keepAlive: true },
      },
      {
        path: 'reimbursements/new',
        name: 'expense-new',
        component: () => import('@/pages/expense/ExpenseForm.vue'),
        meta: { title: '新建报销单', icon: 'add', menu: false },
      },
      {
        path: 'reimbursements/:id',
        name: 'expense-detail',
        component: () => import('@/pages/expense/ExpenseForm.vue'),
        meta: { title: '报销单详情', menu: false },
      },
    ],
  },
]

export default expenseRoutes
