/**
 * 路由表 + 守卫，全量路由定义，meta 契约驱动菜单/面包屑/权限，
 * beforeEach 做登录校验、roles 过滤与首次会话恢复，afterEach 设页面标题。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { message } from '@/utils/feedback'
// 业务模块路由，children 形式，挂主 Layout
import arRoutes from '@/router/modules/ar'
import apRoutes from '@/router/modules/ap'
import cashRoutes from '@/router/modules/cash'
import faRoutes from '@/router/modules/fa'
import expenseRoutes from '@/router/modules/expense'
import budgetRoutes from '@/router/modules/budget'
import wfRoutes from '@/router/modules/wf'
import rptRoutes from '@/router/modules/rpt'
import auditRoutes from '@/router/modules/audit'
import notifyRoutes from '@/router/modules/notify'

/** meta 契约，模块增强后 to.meta 全量类型化 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 需要登录，缺省 true */
    requiresAuth?: boolean
    /** 允许的 jwt role 短名集合，缺省 = 任意已登录用户，前端过滤是体验优化，数据安全由后端兜底 */
    roles?: string[]
    /** 页面标题，document.title / 面包屑 */
    title: string
    /** 布局类型，default=主布局 / admin=管理后台 / blank=独立全屏 */
    layout?: 'default' | 'admin' | 'blank'
    /** 菜单图标，naive 图标名 */
    icon?: string
    /** 是否出现在菜单，缺省 true */
    menu?: boolean
    /** 列表页开 keep-alive，凭证列表/账簿，表单页不开 */
    keepAlive?: boolean
  }
}

const Layout = () => import('@/layouts/Layout.vue')
const AdminLayout = () => import('@/layouts/AdminLayout.vue')
const LoginLayout = () => import('@/layouts/LoginLayout.vue')

/** cash 模块自带 Layout 壳，绝对 path + 自身组件，与其余模块的纯 children 约定不符，剥壳转相对，避免 Layout 嵌套 */
function unwrapLayoutShell(records: RouteRecordRaw[]): RouteRecordRaw[] {
  return records.map((r) => {
    if (!r.path.startsWith('/')) return r
    const { component: _ownLayout, ...rest } = r
    // 解构后 TS 丢失 RouteRecordRaw union 中的 component 键，显式断言还原
    return { ...rest, path: r.path.replace(/^\//, '') } as RouteRecordRaw
  })
}

const routes: RouteRecordRaw[] = [
  // 登录，blank 全屏壳，两步登录 + 2fa 设置共用布局
  {
    path: '/login',
    component: LoginLayout,
    children: [
      {
        path: '',
        name: 'login',
        component: () => import('@/pages/login/LoginView.vue'),
        meta: { requiresAuth: false, title: '登录' },
      },
    ],
  },
  {
    path: '/totp/setup',
    name: 'totp-setup',
    component: () => import('@/pages/login/TotpSetupView.vue'),
    meta: { title: '两步验证设置', menu: false },
  },
  // 自助改密，首登强制改密/弱密码标记双入口，requiresAuth 缺省 true
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/pages/login/ChangePasswordView.vue'),
    meta: { title: '修改密码', menu: false },
  },

  // 主布局，首页 + 总账
  {
    path: '/',
    component: Layout,
    children: [
      {
        path: '',
        name: 'dashboard',
        component: () => import('@/pages/dashboard/DashboardView.vue'),
        meta: { title: '首页', icon: 'home', keepAlive: true },
      },
      {
        path: 'gl',
        name: 'gl',
        redirect: '/gl/vouchers',
        meta: { title: '总账', icon: 'book' },
        children: [
          {
            path: 'vouchers',
            name: 'voucher-list',
            component: () => import('@/pages/gl/VoucherList.vue'),
            meta: { title: '凭证列表', icon: 'document', keepAlive: true },
          },
          {
            path: 'vouchers/new',
            name: 'voucher-new',
            component: () => import('@/pages/gl/VoucherEdit.vue'),
            meta: { title: '凭证录入', icon: 'add', menu: false },
          },
          {
            path: 'vouchers/:id',
            name: 'voucher-detail',
            component: () => import('@/pages/gl/VoucherDetail.vue'),
            meta: { title: '凭证详情', menu: false },
          },
          {
            path: 'vouchers/:id/edit',
            name: 'voucher-edit',
            component: () => import('@/pages/gl/VoucherEdit.vue'),
            meta: { title: '凭证编辑', menu: false },
          },
          {
            path: 'accounts',
            name: 'account-tree',
            component: () => import('@/pages/gl/AccountTree.vue'),
            meta: { title: '科目表', icon: 'git-branch', keepAlive: true },
          },
          {
            path: 'ledger',
            name: 'ledger',
            component: () => import('@/pages/gl/LedgerView.vue'),
            meta: { title: '总账查询', icon: 'grid', keepAlive: true },
          },
          {
            path: 'trial-balance',
            name: 'trial-balance',
            component: () => import('@/pages/gl/TrialBalanceView.vue'),
            meta: { title: '试算平衡表', icon: 'balance', keepAlive: true },
          },
          {
            path: 'periods',
            name: 'gl-periods',
            component: () => import('@/pages/gl/PeriodManage.vue'),
            meta: { title: '期间管理', icon: 'calendar', keepAlive: true },
          },
          {
            path: 'fx-rates',
            name: 'gl-fx-rates',
            component: () => import('@/pages/gl/FxRateManage.vue'),
            meta: { title: '汇率维护', icon: 'cash', keepAlive: true },
          },
        ],
      },
      // 业务模块，父 path 即模块名，redirect 已由各模块提供
      ...arRoutes,
      ...apRoutes,
      ...faRoutes,
      ...expenseRoutes,
      ...budgetRoutes,
      ...wfRoutes,
      ...rptRoutes,
      ...auditRoutes,
      ...notifyRoutes,
      ...unwrapLayoutShell(cashRoutes),
    ],
  },

  // 管理后台，admin 角色
  {
    path: '/admin',
    component: AdminLayout,
    redirect: '/admin/users',
    meta: { title: '系统管理', layout: 'admin', roles: ['admin'], icon: 'settings' },
    children: [
      { path: 'users', name: 'admin-users', component: () => import('@/pages/admin/UserList.vue'), meta: { title: '用户管理', roles: ['admin'] } },
      { path: 'roles', name: 'admin-roles', component: () => import('@/pages/admin/RoleList.vue'), meta: { title: '角色与权限', roles: ['admin'] } },
      { path: 'params', name: 'admin-params', component: () => import('@/pages/admin/ParamList.vue'), meta: { title: '系统参数', roles: ['admin'] } },
      { path: 'logs', name: 'admin-logs', component: () => import('@/pages/admin/TaskList.vue'), meta: { title: '任务监控', roles: ['admin'] } },
      { path: 'security', name: 'admin-security', component: () => import('@/pages/admin/SecurityPage.vue'), meta: { title: '安全中心', roles: ['admin'] } },
      { path: 'system', name: 'admin-system', component: () => import('@/pages/admin/SystemInfoPage.vue'), meta: { title: '系统管理', roles: ['admin'] } },
      { path: 'announcements', name: 'admin-announcements', component: () => import('@/pages/admin/Announcement.vue'), meta: { title: '公告广播', roles: ['admin'] } },
      { path: 'org', name: 'admin-org', component: () => import('@/pages/admin/OrgManage.vue'), meta: { title: '组织管理', roles: ['admin'] } },
    ],
  },

  // 404，blank 全屏壳
  {
    path: '/:pathMatch(.*)*',
    component: LoginLayout,
    children: [
      {
        path: '',
        name: 'not-found',
        component: () => import('@/pages/error/NotFoundPage.vue'),
        meta: { requiresAuth: false, title: '页面不存在' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局守卫
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const requiresAuth = to.meta.requiresAuth !== false

  // 已登录访问 /login 时回首页
  if (to.path === '/login' && auth.isAuthenticated) return { path: '/' }

  // 需登录但未登录时跳登录并携带回跳地址
  if (requiresAuth && !auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 首次加载，store 无 user 但本地有 token 则恢复会话
  if (requiresAuth && !auth.user && localStorage.getItem('fms_access_token')) {
    await auth.fetchSession()
    // fetchSession 失败，网络错误/40100，已清会话则复查并回登录，避免未登录滞留受保护页
    if (!auth.isAuthenticated) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // roles 过滤，前端体验优化，数据安全由后端五层模型兜底
  const roles = to.meta.roles
  if (roles && roles.length > 0 && auth.user && !roles.includes(auth.user.role)) {
    message.warning('无权访问')
    return { path: '/' }
  }

  // 首登强制改密，附录 B，must_change_pwd 用户在完成改密前不得进入业务页
  // 登录返回 ok-change 与刷新页面后均被本守卫拦到改密页，双通道闭环
  if (auth.user?.mustChangePwd && to.path !== '/change-password') {
    return { path: '/change-password' }
  }

  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title ?? 'FMS'} - FMS 财务管理系统`
})

export default router
