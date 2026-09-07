<script setup lang="ts">
/**
 * 报表中心，看板聚合，统计卡片 + 资金趋势 + 应收账龄分桶 + 预算执行 Top，
 * 报表 csv 导出走 report 白名单下拉，带 Bearer 落 a[download]。
 * 手机端，筛选区单列、卡片堆叠、图表高度 240px。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { NButton, NProgress, NSelect, useMessage } from 'naive-ui'
import dayjs from 'dayjs'
import { rptApi } from '@/api/rpt'
import { useAppStore } from '@/stores/app'
import { useConfigStore } from '@/stores/config'
import { useThemeStore } from '@/stores/theme'
import { useBreakpoint } from '@/utils/breakpoint'
import { formatAmount } from '@/utils/format'
import FormGrid from '@/components/common/FormGrid.vue'
import PageCard from '@/components/common/PageCard.vue'
import EChart, { type EChartOption } from '@/components/common/EChart.vue'
import type { DashboardView, ReportType } from '@/types/rpt'

const message = useMessage()
const appStore = useAppStore()
const configStore = useConfigStore()
void configStore.load() // 报表导出白名单经 /api/config 下发（fms.report.enabled-types），后端停用报表时防 40001
const themeStore = useThemeStore()
const { isMobile } = useBreakpoint()

/** 报表类型中文标签，展示文案前端维护，未知类型兜底原值 */
const REPORT_LABELS: Record<string, string> = {
  'trial-balance': '试算平衡表',
  'income-statement': '利润表',
  'balance-sheet': '资产负债表',
  'cash-flow': '现金流量表',
  'ar-aging': '应收账龄',
  'dept-expense': '部门费用',
}
/** 导出选项，白名单单一来源为后端 fms.report.enabled-types，未知值后端 40001 */
const REPORT_OPTIONS = computed(() =>
  configStore.cfg.enabledReportTypes.map((v) => ({
    label: REPORT_LABELS[v] ?? v,
    value: v as ReportType,
  })),
)

const loading = ref(false)
const exporting = ref(false)
const reportType = ref<ReportType>('balance-sheet')

// 白名单收敛防呆，配置下发后若选中类型已被停用，吸附到白名单首项，避免 n-select 无匹配项
watch(
  () => configStore.cfg.enabledReportTypes,
  (types) => {
    if (!types.includes(reportType.value)) {
      reportType.value = (types[0] ?? 'balance-sheet') as ReportType
    }
  },
  { immediate: true },
)
/** 看板查询日期，缺省当日 */
const dateTs = ref(dayjs().valueOf())
const dashboard = ref<DashboardView | null>(null)

/** 统计卡片，资金日报三项合计 + 应收未核销合计 */
const statCards = computed(() => {
  const days = dashboard.value?.cashDaily ?? []
  const sum = (f: (r: (typeof days)[number]) => number) => days.reduce((s, r) => s + f(r), 0)
  const arTotal = (dashboard.value?.agingBuckets ?? []).reduce((s, b) => s + Number(b.amount), 0)
  const net = sum((r) => Number(r.net))
  return [
    { label: '近 30 日收入', value: formatAmount(sum((r) => Number(r.income))), trend: '资金日报合计', positive: true },
    { label: '近 30 日支出', value: formatAmount(sum((r) => Number(r.expense))), trend: '资金日报合计', positive: true },
    { label: '近 30 日净流入', value: formatAmount(net), trend: '收入 − 支出', positive: net >= 0 },
    { label: '应收未核销余额', value: formatAmount(arTotal), trend: '账龄分桶合计', positive: true },
  ]
})

/** 资金趋势，收入/支出/净额三系列 */
const trendOption = computed<EChartOption>(() => {
  const days = dashboard.value?.cashDaily ?? []
  const axisColor = themeStore.darkMode ? '#a3a3a3' : '#6b7280'
  const splitColor = themeStore.darkMode ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)'
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['收入', '支出', '净额'], top: 0 },
    grid: { left: 8, right: 8, top: 32, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category',
      data: days.map((d) => dayjs(d.trxDate).format('MM-DD')),
      axisLabel: { color: axisColor, fontSize: 10 },
    },
    yAxis: { type: 'value', axisLabel: { color: axisColor, fontSize: 10 }, splitLine: { lineStyle: { color: splitColor } } },
    series: [
      { name: '收入', type: 'line', smooth: true, data: days.map((d) => Number(d.income)), itemStyle: { color: '#1677ff' } },
      { name: '支出', type: 'line', smooth: true, data: days.map((d) => Number(d.expense)), itemStyle: { color: '#fa8c16' } },
      { name: '净额', type: 'line', smooth: true, data: days.map((d) => Number(d.net)), itemStyle: { color: '#18a058' } },
    ],
  }
})

/** 应收账龄分桶，环图 */
const agingOption = computed<EChartOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, type: 'scroll' },
  series: [
    {
      name: '应收账龄',
      type: 'pie',
      radius: ['40%', '65%'],
      center: ['50%', '45%'],
      data: (dashboard.value?.agingBuckets ?? []).map((b) => ({ name: `${b.bucket} 天`, value: Number(b.amount) })),
    },
  ],
}))

/** 预算执行进度，ratio 为百分比 0-100，超 100 封顶防进度条溢出 */
const budgetTop = computed(() =>
  (dashboard.value?.budgetTop ?? []).map((b) => ({
    ...b,
    pct: Math.min(100, Math.round(Number(b.ratio) || 0)),
  })),
)

async function load() {
  if (!appStore.companyId) {
    dashboard.value = null
    return
  }
  loading.value = true
  try {
    dashboard.value = await rptApi.dashboard(appStore.companyId, dayjs(dateTs.value).format('YYYY-MM-DD'))
  } finally {
    loading.value = false
  }
}

async function onExport() {
  if (!appStore.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  exporting.value = true
  try {
    await rptApi.exportCsv(reportType.value, {
      companyId: appStore.companyId,
      period: appStore.period,
      date: dayjs(dateTs.value).format('YYYY-MM-DD'),
    })
    message.success('导出成功，请查看下载文件')
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    exporting.value = false
  }
}

watch([() => appStore.companyId, dateTs], () => void load())
onMounted(() => void load())
</script>

<template>
  <div class="report-home">
    <!-- 筛选区，公司只读 / 查询日期 / 导出入口，手机单列 -->
    <FormGrid class="filter-grid">
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
      <n-form-item label="查询日期">
        <n-date-picker
          type="date"
          :value="dateTs"
          :clearable="false"
          style="width: 100%"
          @update:value="(v: number | null) => (dateTs = v ?? dayjs().valueOf())"
        />
      </n-form-item>
      <n-form-item label="报表导出">
        <div class="export-row">
          <n-select v-model:value="reportType" :options="REPORT_OPTIONS" style="flex: 1" />
          <n-button type="primary" size="small" :loading="exporting" @click="onExport">导出 CSV</n-button>
        </div>
      </n-form-item>
    </FormGrid>

    <!-- 统计卡片，桌面 4 列 / 手机单列 -->
    <n-grid :cols="isMobile ? 1 : 4" :x-gap="12" :y-gap="12" responsive="screen">
      <n-gi v-for="card in statCards" :key="card.label">
        <n-card :bordered="false" class="stat-card">
          <template #header>
            <span class="stat-label">{{ card.label }}</span>
          </template>
          <n-statistic :value="card.value" />
          <div :class="['stat-trend', card.positive ? 'is-pos' : 'is-neg']">{{ card.trend }}</div>
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 图表区，资金趋势，2/3+ 账龄分桶，1/3，无公司时占位提示 -->
    <n-grid v-if="dashboard" :cols="isMobile ? 1 : 3" :x-gap="12" :y-gap="12" responsive="screen">
      <n-gi :span="2">
        <PageCard title="近 30 日资金趋势">
          <EChart :option="trendOption" :height="isMobile ? 240 : 320" />
        </PageCard>
      </n-gi>
      <n-gi>
        <PageCard title="应收账龄分桶">
          <EChart :option="agingOption" :height="isMobile ? 240 : 320" />
        </PageCard>
      </n-gi>
    </n-grid>
    <n-empty v-else description="请先在顶栏选择公司后查看报表中心" />

    <!-- 预算执行排行，进度条，超支行标红 -->
    <PageCard title="预算执行 Top10">
      <div class="budget-list">
        <div v-for="b in budgetTop" :key="`${b.deptName}-${b.accountCode}`" class="budget-item">
          <div class="budget-head">
            <span>{{ b.deptName }} · {{ b.accountName }}</span>
            <span :class="Number(b.ratio) > 100 ? 'is-neg' : ''">{{ b.pct }}%</span>
          </div>
          <n-progress
            type="line"
            :percentage="b.pct"
            :color="Number(b.ratio) > 100 ? '#d03050' : undefined"
            :height="8"
            :show-indicator="false"
          />
          <div class="budget-amounts">
            <span>预算 {{ formatAmount(b.planAmount) }}</span>
            <span>实际 {{ formatAmount(b.actualAmount) }}</span>
          </div>
        </div>
      </div>
    </PageCard>
  </div>
</template>

<style scoped>
.report-home {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.filter-grid {
  background: var(--n-color, #fff);
  border-radius: 8px;
  padding: 16px 16px 0;
}
html.dark .filter-grid {
  background: var(--n-color, #1e1e1e);
}
.export-row {
  display: flex;
  gap: 8px;
  width: 100%;
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
.budget-amounts {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--fms-text-muted);
  margin-top: 2px;
}
</style>
