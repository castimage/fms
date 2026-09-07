/**
 * 应收模块路由，格式对照 router/index.ts 的 gl 段，作为主布局，/children 使用，
 * 坏账无独立页面，并入 InvoiceList 的"坏账台账"弹窗入口。
 */
import type { RouteRecordRaw } from 'vue-router'

const arRoutes: RouteRecordRaw[] = [
  {
    path: 'ar',
    name: 'ar',
    redirect: '/ar/customers',
    meta: { title: '应收', icon: 'cash' },
    children: [
      {
        path: 'customers',
        name: 'ar-customers',
        component: () => import('@/pages/ar/CustomerList.vue'),
        meta: { title: '客户管理', icon: 'people', keepAlive: true },
      },
      {
        path: 'invoices',
        name: 'ar-invoices',
        component: () => import('@/pages/ar/InvoiceList.vue'),
        meta: { title: '应收发票', icon: 'document', keepAlive: true },
      },
      {
        path: 'receipts',
        name: 'ar-receipts',
        component: () => import('@/pages/ar/ReceiptList.vue'),
        meta: { title: '收款单', icon: 'cash', keepAlive: true },
      },
      {
        path: 'aging',
        name: 'ar-aging',
        component: () => import('@/pages/ar/AgingPage.vue'),
        meta: { title: '账龄分析', icon: 'clock', keepAlive: true },
      },
    ],
  },
]

export default arRoutes
