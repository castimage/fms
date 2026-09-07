/**
 * 现金银行模块路由，结构对照 router/index.ts 的 gl 段，组件懒加载，meta 契约见 index.ts。
 */
import type { RouteRecordRaw } from 'vue-router'

const Layout = () => import('@/layouts/Layout.vue')

const cashRoutes: RouteRecordRaw[] = [
  {
    path: '/cash',
    component: Layout,
    redirect: '/cash/bank-accounts',
    meta: { title: '现金银行', icon: 'wallet' },
    children: [
      {
        path: 'bank-accounts',
        name: 'cash-bank-accounts',
        component: () => import('@/pages/cash/BankAccountList.vue'),
        meta: { title: '银行账户', icon: 'wallet', keepAlive: true },
      },
      {
        path: 'transactions',
        name: 'cash-transactions',
        component: () => import('@/pages/cash/ReceiptPaymentList.vue'),
        meta: { title: '收付款单', icon: 'swap', keepAlive: true },
      },
      {
        path: 'reconcile',
        name: 'cash-reconcile',
        component: () => import('@/pages/cash/ReconcilePage.vue'),
        meta: { title: '对账', icon: 'sync', keepAlive: true },
      },
    ],
  },
]

export default cashRoutes
