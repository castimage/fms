<script setup lang="ts">
/**
 * 首页，统计卡片 + 资金趋势 / 预算执行 Top10 / 待办审批 / 异常告警，
 * 全部来自 GET /api/rpt/dashboard，后端 RptServiceImpl.dashboard 七路聚合，cash_flow_daily immv、
 * budget_actual 对键计划、ar_aging 分桶、cash.transactions 当日汇总、wf.tasks 待办、
 * fn_dashboard_alerts 阈值告警。
 *
 * 公司切换自动刷新，移动端卡片单列堆叠、图表高 240px。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NEmpty, NList, NListItem, NProgress, NSkeleton, NStatistic } from 'naive-ui'
import PageCard from '@/components/common/PageCard.vue'
import EChart, { type EChartOption } from '@/components/common/EChart.vue'
import dayjs from 'dayjs'
import { useRouter } from 'vue-router'
import { useBreakpoint } from '@/utils/breakpoint'
import { useThemeStore } from '@/stores/theme'
import { useAppStore } from '@/stores/app'
import { rptApi } from '@/api/rpt'
import type { DashboardView } from '@/types/rpt'
import { formatAmount } from '@/utils/format'

const router = useRouter()
const { isMobile } = useBreakpoint()
const themeStore = useThemeStore()
const appStore = useAppStore()

const chartHeight = computed(() => (isMobile.value ? 240 : 320))

const data = ref<DashboardView | null>(null)
const loading = ref(false)

/** 加载看板聚合，companyId 由 Layout 注入，rls 收敛可见集合 */
async function load() {
  const cid = appStore.companyId
  if (!cid) return
  loading.value = true
  try {
    data.value = await rptApi.dashboard(cid)
  } finally {
    loading.value = false
  }
}

/** 公司切换自动刷新，Layout 挂载后回填首个可见公司 */
watch(() => appStore.companyId, () => void load())

/** 统计卡片，今日收款 / 今日付款 / 可用余额 / 待办审批，金额千分位，趋势相对昨日现金日报 */
const statCards = computed(() => {
  const d = data.value
  if (!d) return []
  const yDate = dayjs(d.date).subtract(1, 'day').format('YYYY-MM-DD')
  const yRow = d.cashDaily.find((r) => r.trxDate === yDate)
  const yIn = yRow?.income ?? 0
  const yOut = yRow?.expense ?? 0
  const inChg = yIn > 0 ? ((d.cashSummary.income - yIn) / yIn) * 100 : 0
  const outChg = yOut > 0 ? ((d.cashSummary.expense - yOut) / yOut) * 100 : 0
  const fmt = (v: number) => `${v >= 0 ? '+' : ''}${v.toFixed(1)}%`
  return [
    { label: '今日收款', value: formatAmount(d.cashSummary.income), prefix: '¥', trend: `较昨日 ${fmt(inChg)}`, positive: inChg >= 0 },
    { label: '今日付款', value: formatAmount(d.cashSummary.expense), prefix: '¥', trend: `较昨日 ${fmt(outChg)}`, positive: outChg <= 0 },
    { label: '可用余额', value: formatAmount(d.cashSummary.balance), prefix: '¥', trend: `截至 ${dayjs(d.date).format('MM-DD')}`, positive: true },
    { label: '待办审批', value: String(d.todoSummary.pendingCount), prefix: '', trend: `${d.todoSummary.dueTodayCount} 条今日到期`, positive: d.todoSummary.dueTodayCount === 0 },
  ]
})

/** 近 30 日资金趋势，cashDaily 双系列，收入/支出 */
const trendOption = computed<EChartOption>(() => {
  const rows = data.value?.cashDaily ?? []
  const days = rows.map((r) => dayjs(r.trxDate).format('MM-DD'))
  const income = rows.map((r) => r.income)
  const expense = rows.map((r) => r.expense)
  const axisColor = themeStore.darkMode ? '#a3a3a3' : '#6b7280'
  const splitColor = themeStore.darkMode ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)'
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['收入', '支出'], top: 0 },
    grid: { left: 8, right: 8, top: 32, bottom: 0, containLabel: true },
    xAxis: { type: 'category', data: days, axisLabel: { color: axisColor, fontSize: 10 } },
    yAxis: {
      type: 'value',
      axisLabel: { color: axisColor, fontSize: 10 },
      splitLine: { lineStyle: { color: splitColor } },
    },
    series: [
      { name: '收入', type: 'line', smooth: true, data: income, itemStyle: { color: '#1677ff' } },
      { name: '支出', type: 'line', smooth: true, data: expense, itemStyle: { color: '#fa8c16' } },
    ],
  }
})

/** 预算执行 top10，ratio 为后端百分比 0-100，无计划行恒 0 防除零 */
const budgetTop = computed(() =>
  (data.value?.budgetTop ?? []).map((b) => ({
    name: `${b.deptName ?? ''} ${b.accountName ?? b.accountCode ?? ''}`.trim() || b.accountCode,
    plan: b.planAmount,
    actual: b.actualAmount,
    ratio: b.ratio,
  })),
)

/** 待办审批列表，dueAt ISO-8601 本地化展示，查看全部跳 /wf/todos */
const todoItems = computed(() =>
  (data.value?.todos ?? []).map((t) => ({
    title: t.title,
    time: t.dueAt ? dayjs(t.dueAt).format('MM-DD HH:mm') : '',
  })),
)

/** 异常告警，type warning/error 对应 n-alert 类型 */
const alertItems = computed(() => (data.value?.alerts ?? []).map((a) => ({ type: a.type, text: a.text })))

onMounted(() => void load())
</script>

<template>
  <div class="dashboard">
    <!-- 加载骨架，数据未到达时占位，统计卡 4 张 + 图表 + 双列表，到达即替换 -->
    <template v-if="loading">
      <n-grid :cols="isMobile ? 1 : 4" :x-gap="12" :y-gap="12" responsive="screen">
        <n-gi v-for="i in 4" :key="i">
          <n-card :bordered="false" class="stat-card">
            <n-skeleton text :width="48" />
            <n-skeleton text :width="120" style="margin-top: 8px" />
            <n-skeleton text :width="80" style="margin-top: 4px" />
          </n-card>
        </n-gi>
      </n-grid>
      <n-grid :cols="isMobile ? 1 : 3" :x-gap="12" :y-gap="12" responsive="screen">
        <n-gi :span="2">
          <n-card :bordered="false" title="近 30 日资金趋势">
            <n-skeleton height="200" />
          </n-card>
        </n-gi>
        <n-gi>
          <n-card :bordered="false" title="预算执行 Top10">
            <n-skeleton v-for="i in 5" :key="i" text style="margin-bottom: 10px" />
          </n-card>
        </n-gi>
      </n-grid>
      <n-grid :cols="isMobile ? 1 : 2" :x-gap="12" :y-gap="12" responsive="screen">
        <n-gi v-for="i in 2" :key="i">
          <n-card :bordered="false">
            <n-skeleton v-for="j in 4" :key="j" text style="margin-bottom: 8px" />
          </n-card>
        </n-gi>
      </n-grid>
    </template>

    <template v-else>
    <!-- 统计卡片，4 卡片，桌面 4 列 / 手机单列 -->
    <n-grid :cols="isMobile ? 1 : 4" :x-gap="12" :y-gap="12" responsive="screen">
      <n-gi v-for="card in statCards" :key="card.label">
        <n-card :bordered="false" class="stat-card">
          <template #header>
            <span class="stat-label">{{ card.label }}</span>
          </template>
          <n-statistic :value="card.value" :prefix="card.prefix" />
          <div :class="['stat-trend', card.positive ? 'is-pos' : 'is-neg']">{{ card.trend }}</div>
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 中部，资金趋势 + 预算执行 -->
    <n-grid :cols="isMobile ? 1 : 3" :x-gap="12" :y-gap="12" responsive="screen">
      <n-gi :span="2">
        <PageCard title="近 30 日资金趋势">
          <template #extra>
            <n-button text type="primary" size="small" :loading="loading" @click="load">刷新</n-button>
          </template>
          <EChart :option="trendOption" :height="chartHeight" />
        </PageCard>
      </n-gi>
      <n-gi>
        <PageCard title="预算执行 Top10">
          <div class="budget-list">
            <div v-for="b in budgetTop" :key="b.name" class="budget-item">
              <div class="budget-head">
                <span>{{ b.name }}</span>
                <span class="budget-ratio">{{ Math.round(b.ratio) }}%</span>
              </div>
              <n-progress
                type="line"
                :percentage="Math.min(Math.round(b.ratio), 100)"
                :height="8"
                :show-indicator="false"
              />
              <div class="budget-amounts">
                <span>预算 {{ formatAmount(b.plan) }}</span>
                <span>实际 {{ formatAmount(b.actual) }}</span>
              </div>
            </div>
            <n-empty v-if="!budgetTop.length" description="暂无预算执行数据" />
          </div>
        </PageCard>
      </n-gi>
    </n-grid>

    <!-- 底部，待办审批 + 异常告警 -->
    <n-grid :cols="isMobile ? 1 : 2" :x-gap="12" :y-gap="12" responsive="screen">
      <n-gi>
        <PageCard title="待办审批">
          <template #extra>
            <n-button text type="primary" size="small" @click="router.push('/wf/todos')">查看全部</n-button>
          </template>
          <n-list v-if="todoItems.length" size="small">
            <n-list-item v-for="item in todoItems" :key="item.title">
              <div class="todo-item">
                <span>{{ item.title }}</span>
                <span class="todo-time">{{ item.time }}</span>
              </div>
            </n-list-item>
          </n-list>
          <n-empty v-else description="暂无待办" />
        </PageCard>
      </n-gi>
      <n-gi>
        <PageCard title="异常告警">
          <template #extra>
            <n-button text type="primary" size="small" :loading="loading" @click="load">刷新</n-button>
          </template>
          <n-alert
            v-for="(a, i) in alertItems"
            :key="i"
            :type="a.type"
            :bordered="false"
            class="alert-item"
          >
            {{ a.text }}
          </n-alert>
          <n-empty v-if="!alertItems.length" description="暂无告警" />
        </PageCard>
      </n-gi>
    </n-grid>
    </template>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.stat-card :deep(.n-card-header) {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.stat-label {
  font-weight: 600;
}
.stat-trend {
  margin-top: 4px;
  font-size: 12px;
  color: var(--fms-text-muted);
}
.is-pos {
  color: var(--n-success-color, #18a058);
}
.is-neg {
  color: var(--n-error-color, #d03050);
}
.budget-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.budget-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 4px;
}
.budget-ratio {
  font-weight: 600;
}
.budget-amounts {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--fms-text-muted);
  margin-top: 2px;
}
.todo-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.todo-time {
  color: var(--fms-text-muted);
  font-size: 12px;
  white-space: nowrap;
}
.alert-item {
  margin-bottom: 8px;
}
.alert-item:last-child {
  margin-bottom: 0;
}
</style>
