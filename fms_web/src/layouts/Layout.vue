<script setup lang="ts">
/**
 * 主布局，响应式三态，桌面（≥992px）侧边栏 220px 可折叠 64px + 全功能顶栏，
 * 平板（768–991px）侧边栏 64px 折叠 + 精简顶栏，手机（<768px）隐藏侧边栏 + 抽屉菜单。
 * 断点唯一事实源，utils/breakpoint.ts 与 styles/variables.css --fms-bp-*。
 *
 * 背景半透明，启用背景图时，内容区容器，卡片/表格/表单等按 contentOpacity 滑杆
 * 经 css color-mix 半透明化，露出底层背景图。侧边栏/顶栏/弹层同步覆盖。
 * 覆盖策略，scoped 样式覆盖 layout-content 内的 naive-ui 组件 + 全局样式覆盖
 * teleport 到 body 的弹层 + 各页面自定义容器，filter-grid/tree-pane 等。
 * !important 必须，naive-ui css-render 运行时注入的 __ComponentHash 类
 * 在同一元素设置 --n-color，源序靠后，!important 强制胜出。
 */
import { computed, h, onMounted, ref, type Component } from 'vue'
import { NIcon, type MenuOption } from 'naive-ui'
import {
  BarChartOutline,
  BookOutline,
  CashOutline,
  CheckmarkCircleOutline,
  CubeOutline,
  DocumentTextOutline,
  HomeOutline,
  NotificationsOutline,
  PeopleOutline,
  PieChartOutline,
  SettingsOutline,
  ShieldCheckmarkOutline,
  WalletOutline,
} from '@vicons/ionicons5'
import { useRoute, useRouter } from 'vue-router'
import { useBreakpoint } from '@/utils/breakpoint'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useAppStore } from '@/stores/app'
import { useBackgroundStore } from '@/stores/background'
import BackgroundLayer from '@/components/common/BackgroundLayer.vue'
import BackgroundSettings from '@/components/common/BackgroundSettings.vue'
import { pageCompanies } from '@/api/org'
import { WRITE_ROLES as PERM_WRITE_ROLES } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'
import type { BreakpointKey } from '@/types/background'

const route = useRoute()
const router = useRouter()
const { isMobile, isTablet } = useBreakpoint()
const auth = useAuthStore()
const theme = useThemeStore()
const app = useAppStore()
const background = useBackgroundStore()

/** 公司选择器数据源，pageCompanies 首批仅取列表，后端 rls 已收敛可见公司 */
const companyOptions = ref<{ label: string; value: string }[]>([])

onMounted(async () => {
  try {
    const res = await pageCompanies({ page: 1, size: await refdataPageSize() })
    companyOptions.value = res.items.map((c) => ({ label: c.name, value: c.id }))
    if (!app.companyId && res.items.length > 0) {
      app.setCompany(res.items[0].id, res.items[0].name)
    }
  } catch {
    // 失败全局已提示，选择器保持空态
  }
  // 用户背景，失败回落默认不阻断，http.ts 已统一提示
  void background.loadUser()
})

/** 侧边栏折叠态，桌面手动切换 / 平板默认 64px */
const collapsed = ref(false)

/** 页面标题，取路由 meta，与 router afterEach 一致 */
const pageTitle = computed(() => (route.meta.title as string | undefined) ?? 'FMS')

// 用户下拉，安全中心在管理后台菜单内
const userOptions = [
  { label: '外观设置', key: 'appearance' },
  { label: '两步验证', key: 'totp' },
  { label: '退出登录', key: 'logout' },
]

/** 外观设置弹窗开关 */
const appearanceOpen = ref(false)

function onUserSelect(key: string | number) {
  if (key === 'appearance') {
    appearanceOpen.value = true
  } else if (key === 'totp') {
    void router.push('/totp/setup')
  } else if (key === 'logout') {
    void auth.logout().then(() => router.replace('/login'))
  }
}

/** 当前断点档名，与 useBreakpoint 对齐，isMobile 对应 mobile / isTablet 对应 tablet / 否则 desktop */
const bgBreakpoint = computed<BreakpointKey>(() =>
  isMobile.value ? 'mobile' : isTablet.value ? 'tablet' : 'desktop',
)

/** 合并后的用户背景单档配置，断点覆盖 + 顶层兜底 */
const userResolvedCfg = computed(() => background.resolveFor(background.userCfg, bgBreakpoint.value))

/** 是否启用用户背景，有图才挂载背景层并让内容区透明 */
const hasBg = computed(() => background.userCfg.enabled && !!background.userCfg.imageUrl)

/** 内容区不透明度，用户可调，0.5~1，默认 1 不透明 */
const contentOpacity = computed(() =>
  Math.min(Math.max(background.userCfg.contentOpacity ?? 1, 0.5), 1).toFixed(2),
)

// 同步到根 css 变量 + body 类，弹层，teleport 到 body，经全局样式继承 --fms-content-opacity
watch(contentOpacity, (v) => {
  document.documentElement.style.setProperty('--fms-content-opacity', v)
})
watch(hasBg, (v) => {
  document.body.classList.toggle('fms-has-bg', v)
})

/** 公司切换，回填名称，选项里查 label，列表页 watch appStore 自动刷新 */
function onCompanyChange(v: string | number | null) {
  const id = typeof v === 'string' ? v : v === null ? null : String(v)
  const opt = companyOptions.value.find((o) => o.value === id)
  app.setCompany(id, opt?.label ?? '')
}

/** 期间切换，value-format 下 v 为 yyyy-MM 字符串 */
function onPeriodChange(v: string | number | null) {
  if (typeof v === 'string' && v) app.setPeriod(v)
}

/** n-menu 图标约定为渲染函数，NIcon 包裹使尺寸/颜色随主题 */
function renderIcon(icon: Component) {
  return () => h(NIcon, null, { default: () => h(icon) })
}

/** 角色可见性，无 roles 限制全角色可见，登录态由路由守卫兜底 */
function visibleTo(roles?: string[]) {
  return !roles || !auth.user || roles.includes(auth.user.role)
}

/** 写操作入口仅 admin/operator 可见，auditor/report/viewer 只读，不显写菜单，
 * 数据安全由后端五层模型兜底，统一常量见 utils/perm.ts */
const WRITE_ROLES = [...PERM_WRITE_ROLES]

/** 业务模块菜单，key 即路由 path，与路由收口一致，审计中心仅 auditor/admin 可见，
 * 写操作子菜单按 WRITE_ROLES 过滤，viewer 只读角色仅保留查询入口 */
const moduleMenu = computed<MenuOption[]>(() => {
  const groups: { key: string; label: string; icon: Component; roles?: string[]; children: { key: string; label: string; roles?: string[] }[] }[] = [
    {
      key: '/ar', label: '应收管理', icon: PeopleOutline,
      children: [
        { key: '/ar/customers', label: '客户管理', roles: WRITE_ROLES },
        { key: '/ar/invoices', label: '应收发票', roles: WRITE_ROLES },
        { key: '/ar/receipts', label: '收款单', roles: WRITE_ROLES },
        { key: '/ar/aging', label: '账龄分析' },
      ],
    },
    {
      key: '/ap', label: '应付管理', icon: WalletOutline,
      children: [
        { key: '/ap/vendors', label: '供应商', roles: WRITE_ROLES },
        { key: '/ap/invoices', label: '应付发票', roles: WRITE_ROLES },
        { key: '/ap/payments', label: '付款管理', roles: WRITE_ROLES },
        { key: '/ap/aging', label: '应付账龄' },
      ],
    },
    {
      key: '/cash', label: '现金银行', icon: CashOutline,
      children: [
        { key: '/cash/bank-accounts', label: '银行账户', roles: WRITE_ROLES },
        { key: '/cash/transactions', label: '收付款单', roles: WRITE_ROLES },
        { key: '/cash/reconcile', label: '对账', roles: WRITE_ROLES },
      ],
    },
    {
      key: '/fa', label: '固定资产', icon: CubeOutline,
      children: [
        { key: '/fa/assets', label: '资产卡片', roles: WRITE_ROLES },
        { key: '/fa/depreciation', label: '折旧记录' },
      ],
    },
    {
      key: '/expense', label: '费用报销', icon: DocumentTextOutline,
      children: [{ key: '/expense/reimbursements', label: '报销申请', roles: WRITE_ROLES }],
    },
    {
      key: '/budget', label: '预算管理', icon: PieChartOutline,
      children: [
        { key: '/budget/plans', label: '预算编制', roles: WRITE_ROLES },
        { key: '/budget/execution', label: '预算执行' },
      ],
    },
    {
      key: '/wf', label: '审批中心', icon: CheckmarkCircleOutline,
      children: [
        { key: '/wf/todos', label: '我的待办', roles: WRITE_ROLES },
        { key: '/wf/instances', label: '流程实例', roles: WRITE_ROLES },
        { key: '/wf/definitions', label: '流程定义', roles: ['admin'] },
      ],
    },
    {
      key: '/rpt', label: '报表中心', icon: BarChartOutline,
      children: [
        { key: '/rpt/home', label: '报表中心' },
        { key: '/rpt/statements', label: '财务报表' },
      ],
    },
    {
      key: '/audit', label: '审计中心', icon: ShieldCheckmarkOutline, roles: ['auditor', 'admin'],
      children: [{ key: '/audit/logs', label: '审计日志' }],
    },
  ]
  return groups
    .filter((g) => visibleTo(g.roles))
    .map((g) => ({ key: g.key, label: g.label, icon: renderIcon(g.icon), children: g.children.filter((c) => visibleTo(c.roles)) }))
    .filter((g) => g.children.length > 0)
})

/** 桌面菜单，菜单项按角色过滤 */
const menuItems = computed<MenuOption[]>(() => {
  const items: MenuOption[] = [
    { key: '/', label: '首页', icon: renderIcon(HomeOutline) },
    {
      key: '/gl',
      label: '总账',
      icon: renderIcon(BookOutline),
      children: [
        { key: '/gl/vouchers', label: '凭证列表' },
        { key: '/gl/accounts', label: '科目表' },
        { key: '/gl/ledger', label: '总账查询' },
        { key: '/gl/trial-balance', label: '试算平衡表' },
        // 写操作按钮在页面内按 canWrite 收敛，menuItems 无角色过滤，只读角色可见入口但按钮只读
        { key: '/gl/periods', label: '期间管理' },
        { key: '/gl/fx-rates', label: '汇率维护' },
      ],
    },
    ...moduleMenu.value,
    { key: '/notify', label: '消息通知', icon: renderIcon(NotificationsOutline) },
  ]
  if (auth.isAdmin) {
    items.push({
      key: '/admin',
      label: '系统管理',
      icon: renderIcon(SettingsOutline),
      children: [
        { key: '/admin/users', label: '用户管理' },
        { key: '/admin/roles', label: '角色与权限' },
        { key: '/admin/org', label: '组织管理' },
        { key: '/admin/announcements', label: '公告广播' },
        { key: '/admin/params', label: '系统参数' },
        { key: '/admin/logs', label: '任务监控' },
        { key: '/admin/security', label: '安全中心' },
        { key: '/admin/system', label: '系统管理' },
      ],
    })
  }
  return items
})

/** 选中态，key 即完整 path，含模块页，根路径特判首页 */
const selectedKey = computed(() => (route.path === '/' ? '/' : route.path))

/** 平板抽屉模式，菜单弹层 */
const drawerOpen = ref(false)

/** n-menu 选中跳转，emit 值为 string|number|null，统一转 string */
function navigate(key: string | number | null) {
  drawerOpen.value = false
  const target = key === null ? '' : String(key)
  if (target && target !== route.path) router.push(target)
}
</script>

<template>
  <n-layout
    has-sider
    class="layout-root"
    :class="{ 'has-bg': hasBg }"
    :style="{ '--fms-content-opacity': contentOpacity }"
  >
    <!-- 用户背景层，fixed 底层，内容区透明后露出，侧边栏/顶栏保持实色 -->
    <BackgroundLayer
      v-if="background.userCfg.enabled && background.userCfg.imageUrl"
      :cfg="userResolvedCfg"
    />
    <!-- 桌面/平板侧边栏 -->
    <n-layout-sider
      v-if="!isMobile"
      class="layout-sider"
      :width="220"
      :collapsed-width="64"
      :collapsed="isTablet || collapsed"
      :show-trigger="!isTablet"
      collapse-mode="width"
      bordered
      @collapse="collapsed = true"
      @expand="collapsed = false"
    >
      <div class="layout-sider__logo" @click="router.push('/')">
        <span class="layout-sider__logo-mark">F</span>
        <span v-if="!isTablet && !collapsed" class="layout-sider__logo-text">FMS 财务管理系统</span>
      </div>
      <n-menu
        :value="selectedKey"
        :options="menuItems"
        :collapsed="isTablet || collapsed"
        :collapsed-width="64"
        @update:value="navigate"
      />
    </n-layout-sider>

    <!-- 平板/手机抽屉菜单，顶栏 ≡ 按钮弹出，手机侧边栏隐藏，抽屉为完整菜单唯一入口 -->
    <n-drawer v-if="isTablet || isMobile" v-model:show="drawerOpen" placement="left" :width="220">
      <n-drawer-content title="导航" closable>
        <n-menu :value="selectedKey" :options="menuItems" @update:value="navigate" />
      </n-drawer-content>
    </n-drawer>

    <n-layout class="layout-main">
      <!-- 顶栏 -->
      <n-layout-header bordered class="layout-header">
        <div class="layout-header__left">
          <!-- 平板/手机，抽屉菜单按钮，手机侧边栏隐藏，抽屉为完整菜单入口 -->
          <n-button v-if="isTablet || isMobile" quaternary circle @click="drawerOpen = true">
            <template #icon>
              <span class="header-icon">≡</span>
            </template>
          </n-button>
          <span class="layout-header__title">{{ pageTitle }}</span>
        </div>

        <div class="layout-header__right">
          <!-- 桌面，公司 + 期间 -->
          <template v-if="!isMobile">
            <n-select
              v-if="!isTablet"
              class="header-company"
              size="small"
              placeholder="选择公司"
              :value="app.companyId"
              :options="companyOptions"
              @update:value="onCompanyChange"
            />
            <n-date-picker
              v-if="!isTablet"
              class="header-period"
              size="small"
              type="month"
              :formatted-value="app.period"
              value-format="yyyy-MM"
              @update:formatted-value="onPeriodChange"
            />
            <!-- 主题开关 -->
            <n-switch :value="theme.darkMode" @update:value="theme.toggleDark">
              <template #checked>深</template>
              <template #unchecked>浅</template>
            </n-switch>
          </template>

          <!-- 用户下拉 -->
          <n-dropdown :options="userOptions" @select="onUserSelect">
            <n-button quaternary size="small">
              <template #icon>
                <n-avatar round :size="26" class="layout-header__avatar">
                  {{ auth.displayName.slice(0, 1).toUpperCase() }}
                </n-avatar>
              </template>
              <span v-if="!isMobile">{{ auth.displayName }}</span>
            </n-button>
          </n-dropdown>
        </div>
      </n-layout-header>

      <!-- 内容区 -->
      <n-layout-content class="layout-content">
        <router-view v-slot="{ Component, route: r }">
          <!-- keep-alive include 匹配组件 name，SFC 文件名，与路由 meta.keepAlive 名单对应 -->
          <keep-alive :include="['VoucherList', 'LedgerView', 'AccountTree', 'TrialBalanceView', 'PeriodManage', 'FxRateManage']">
            <component :is="Component" :key="r.path" />
          </keep-alive>
        </router-view>
      </n-layout-content>
    </n-layout>

    <!-- 外观设置弹窗，用户背景 + 管理员登录页背景 -->
    <BackgroundSettings v-model:show="appearanceOpen" />
  </n-layout>
</template>

<style scoped>
.layout-root {
  height: 100vh;
}

/* 启用背景时内容区/主区域透明露出底层图，侧边栏/顶栏保持实色保可读性 */
.layout-root.has-bg .layout-main,
.layout-root.has-bg .layout-content {
  background: transparent;
}

/* ---- 内容区组件半透明（scoped :deep） ----
 * naive-ui 组件的 --n-color 被 css-render __ComponentHash 类在同一元素设置，
 * 必须 !important 强制胜出。白名单精确指定容器和表单组件，
 * 不覆盖按钮/标签/开关等有独立配色的交互组件 */
.layout-root.has-bg .layout-content :deep(.n-card),
.layout-root.has-bg .layout-content :deep(.n-list),
.layout-root.has-bg .layout-content :deep(.n-alert),
.layout-root.has-bg .layout-content :deep(.n-statistic),
.layout-root.has-bg .layout-content :deep(.n-descriptions),
.layout-root.has-bg .layout-content :deep(.n-thing),
.layout-root.has-bg .layout-content :deep(.n-collapse),
.layout-root.has-bg .layout-content :deep(.n-form-item),
.layout-root.has-bg .layout-content :deep(.n-input),
.layout-root.has-bg .layout-content :deep(.n-input-number),
.layout-root.has-bg .layout-content :deep(.n-select),
.layout-root.has-bg .layout-content :deep(.n-date-picker),
.layout-root.has-bg .layout-content :deep(.n-picker),
.layout-root.has-bg .layout-content :deep(.n-switch),
.layout-root.has-bg .layout-content :deep(.n-radio),
.layout-root.has-bg .layout-content :deep(.n-checkbox) {
  --n-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
}

/* 数据表格，容器/表头/表体独立颜色变量 */
.layout-root.has-bg .layout-content :deep(.n-data-table) {
  --n-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
  --n-th-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
  --n-td-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
  --n-merged-th-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
  --n-merged-td-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
}

/* 分页 item */
.layout-root.has-bg .layout-content :deep(.n-pagination .n-pagination-item) {
  --n-item-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
  --n-item-color-hover: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
}

/* ---- 侧边栏半透明 ---- */
.layout-root.has-bg .layout-sider {
  --n-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
}
.layout-root.has-bg .layout-sider :deep(.n-menu-item),
.layout-root.has-bg .layout-sider :deep(.n-submenu) {
  --n-item-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
  --n-item-color-hover: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
  --n-item-color-active: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
}

.layout-sider__logo {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  padding: 0 16px;
  cursor: pointer;
  overflow: hidden;
  white-space: nowrap;
}

.layout-sider__logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 6px;
  background: var(--fms-primary);
  color: #fff;
  font-weight: 700;
}

.layout-sider__logo-text {
  font-size: 15px;
  font-weight: 600;
}

.layout-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 16px;
  background: var(--fms-bg-card);
}

.layout-header__left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.layout-header__title {
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.layout-header__right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-company {
  width: 160px;
}

.header-period {
  width: 140px;
}

.header-icon {
  font-size: 18px;
  line-height: 1;
}

.layout-header__avatar {
  background: var(--fms-primary);
  /* n-button icon slot 的 flex 会把头像拉成椭圆，锁尺寸并禁止伸缩 */
  flex: none;
  flex-shrink: 0;
}

.layout-content {
  flex: 1;
  min-height: 0;
  padding: 16px;
  overflow: auto;
}
</style>

<!-- 全局半透明样式，非 scoped，覆盖 teleport 到 body 的弹层 + 各页面自定义容器。
     Layout scoped 样式对 body 下弹层不可达，必须全局覆盖。
     --fms-content-opacity 由 watch 设置到 html 根元素，body 下所有元素可继承。 -->
<style>
/* 弹层容器半透明 */
body.fms-has-bg .n-popover,
body.fms-has-bg .n-dropdown,
body.fms-has-bg .n-date-panel,
body.fms-has-bg .n-modal,
body.fms-has-bg .n-drawer-content,
body.fms-has-bg .n-select-menu,
body.fms-has-bg .n-cascader-menu,
body.fms-has-bg .n-auto-complete-menu {
  --n-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
}
/* 纯文字菜单类弹层（下拉菜单/选择/级联/自动补全/日期面板）：底色设不透明下限、
   文字钉成主题实色。否则底色随背景图透出后，文字与框的界线分不清
   （浅色模式 + 背景图时尤其明显）。 */
body.fms-has-bg .n-popover,
body.fms-has-bg .n-dropdown,
body.fms-has-bg .n-dropdown-menu,
body.fms-has-bg .n-select-menu,
body.fms-has-bg .n-base-select-menu,
body.fms-has-bg .n-cascader-menu,
body.fms-has-bg .n-auto-complete-menu,
body.fms-has-bg .n-date-panel {
  --n-color: color-mix(
    in srgb,
    var(--fms-bg-card) max(calc(var(--fms-content-opacity) * 100%), 92%),
    transparent
  ) !important;
  --n-text-color: var(--fms-text) !important;
  --n-option-text-color: var(--fms-text) !important;
  --n-option-text-color-hover: var(--fms-text) !important;
  --n-option-text-color-active: var(--fms-text) !important;
  --n-option-text-color-pressed: var(--fms-text) !important;
  --n-option-text-color-disabled: var(--fms-text-muted) !important;
}
/* 弹层内的表单控件跟随半透明 */
body.fms-has-bg .n-popover .n-input,
body.fms-has-bg .n-popover .n-select,
body.fms-has-bg .n-popover .n-date-picker,
body.fms-has-bg .n-modal .n-input,
body.fms-has-bg .n-modal .n-select,
body.fms-has-bg .n-modal .n-date-picker,
body.fms-has-bg .n-drawer-content .n-input,
body.fms-has-bg .n-drawer-content .n-select,
body.fms-has-bg .n-drawer-content .n-date-picker {
  --n-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
}
/* 页头 + 各页面自定义容器（scoped 中使用 var(--fms-bg-card) 作为背景的类）。
   同时覆盖 --n-color，naive-ui 组件变量和 background-color，自定义 scoped 样式 */
body.fms-has-bg .n-layout-header,
body.fms-has-bg .layout-header,
body.fms-has-bg .filter-grid,
body.fms-has-bg .tree-pane,
body.fms-has-bg .summary-item,
body.fms-has-bg .stat-card,
body.fms-has-bg .key-result,
body.fms-has-bg .aging-card,
body.fms-has-bg .stmt-filter,
body.fms-has-bg .action-bar,
body.fms-has-bg .entry-toolbar,
body.fms-has-bg .param-row {
  --n-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
  background-color: color-mix(in srgb, var(--fms-bg-card) calc(var(--fms-content-opacity) * 100%), transparent) !important;
}
</style>
