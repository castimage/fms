/**
 * 应付模块路由，children 形式与 index.ts gl 段一致，供应商/发票/付款/账龄四页，
 * 懒加载 + meta{title, icon}，接入主路由时挂到 Layout children，path 相对 'ap'。
 */
import type { RouteRecordRaw } from 'vue-router'

export const apRoutes: RouteRecordRaw[] = [
  {
    path: 'ap',
    name: 'ap',
    redirect: '/ap/vendors',
    meta: { title: '应付管理', icon: 'book' },
    children: [
      {
        path: 'vendors',
        name: 'ap-vendors',
        component: () => import('@/pages/ap/VendorList.vue'),
        meta: { title: '供应商', icon: 'people', keepAlive: true },
      },
      {
        path: 'invoices',
        name: 'ap-invoices',
        component: () => import('@/pages/ap/InvoiceList.vue'),
        meta: { title: '应付发票', icon: 'document', keepAlive: true },
      },
      {
        path: 'payments',
        name: 'ap-payments',
        component: () => import('@/pages/ap/PaymentList.vue'),
        meta: { title: '付款管理', icon: 'card', keepAlive: true },
      },
      {
        path: 'aging',
        name: 'ap-aging',
        component: () => import('@/pages/ap/AgingPage.vue'),
        meta: { title: '应付账龄', icon: 'trend', keepAlive: true },
      },
    ],
  },
]

export default apRoutes
