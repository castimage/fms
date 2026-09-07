<script setup lang="ts">
/**
 * 财务报表，三表 tab 切换，后端均按期间聚合返回行列表，
 * 资产负债表恒等式 totalAsset = totalLiability + totalEquity 断链警示，期间跟随顶栏 appStore.period，
 * 各 tab 独立导出 csv。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NDatePicker, useMessage } from 'naive-ui'
import dayjs from 'dayjs'
import { rptApi } from '@/api/rpt'
import { useAppStore } from '@/stores/app'
import { useConfigStore } from '@/stores/config'
import DataTable from '@/components/common/DataTable.vue'
import FormGrid from '@/components/common/FormGrid.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import PageCard from '@/components/common/PageCard.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { BalanceSheetRow, CashFlowRow, IncomeStatementRow, ReportType } from '@/types/rpt'

const message = useMessage()
const appStore = useAppStore()
const configStore = useConfigStore()
void configStore.load() // 报表导出白名单经 /api/config 下发（fms.report.enabled-types），防停用报表后前端 40001

const TABS = ['income', 'balance', 'cash'] as const
type TabKey = (typeof TABS)[number]

/** tab 决定导出报告类型，后端停用时按白名单禁用导出入口 */
const EXPORT_BY_TAB: Record<TabKey, ReportType> = {
  income: 'income-statement',
  balance: 'balance-sheet',
  cash: 'cash-flow',
}
/** 当前 tab 报表是否在导出白名单内，后端配置单一来源 */
const exportEnabled = computed(() =>
  configStore.cfg.enabledReportTypes.includes(EXPORT_BY_TAB[tab.value]),
)

const tab = ref<TabKey>('income')
const loading = ref(false)
const exporting = ref(false)
const incomeRows = ref<IncomeStatementRow[]>([])
const balanceRows = ref<BalanceSheetRow[]>([])
const cashRows = ref<CashFlowRow[]>([])

/** 期间绑定 appStore.period，月选择器时间戳 <-> YYYY-MM */
const periodTs = computed(() => (appStore.period ? dayjs(appStore.period + '-01').valueOf() : null))
function onPeriodChange(ts: number | null) {
  appStore.setPeriod(ts ? dayjs(ts).format('YYYY-MM') : dayjs().format('YYYY-MM'))
}

function renderAmount(v: number | null | undefined) {
  return h(MoneyText, { value: v })
}

const incomeColumns: FmsColumn<IncomeStatementRow>[] = [
  { key: 'period', title: '期间', width: 100 },
  { key: 'totalRevenue', title: '营业收入', width: 140, align: 'right', render: (r) => renderAmount(r.totalRevenue) },
  { key: 'totalExpense', title: '营业费用', width: 140, align: 'right', hideBelow: 'sm', render: (r) => renderAmount(r.totalExpense) },
  { key: 'netProfit', title: '净利润', width: 140, align: 'right', render: (r) => renderAmount(r.netProfit) },
]

const balanceColumns: FmsColumn<BalanceSheetRow>[] = [
  { key: 'period', title: '期间', width: 100 },
  { key: 'totalAsset', title: '资产合计', width: 140, align: 'right', render: (r) => renderAmount(r.totalAsset) },
  { key: 'totalLiability', title: '负债合计', width: 140, align: 'right', hideBelow: 'sm', render: (r) => renderAmount(r.totalLiability) },
  { key: 'totalEquity', title: '权益合计', width: 140, align: 'right', hideBelow: 'sm', render: (r) => renderAmount(r.totalEquity) },
]

const cashColumns: FmsColumn<CashFlowRow>[] = [
  { key: 'period', title: '期间', width: 100 },
  { key: 'cashIn', title: '现金流入', width: 140, align: 'right', render: (r) => renderAmount(r.cashIn) },
  { key: 'cashOut', title: '现金流出', width: 140, align: 'right', hideBelow: 'sm', render: (r) => renderAmount(r.cashOut) },
  { key: 'netFlow', title: '净流量', width: 140, align: 'right', render: (r) => renderAmount(r.netFlow) },
]

/** 资产负债表恒等式断链，|资产 − 负债 − 权益| > 1 分即异常，浮点兜底 */
const identityBroken = computed(() => {
  const r = balanceRows.value[0]
  if (!r) return null
  return Math.abs(Number(r.totalAsset) - Number(r.totalLiability) - Number(r.totalEquity)) > 1
})

async function load() {
  if (!appStore.companyId) {
    incomeRows.value = []
    balanceRows.value = []
    cashRows.value = []
    return
  }
  loading.value = true
  try {
    const opts = { companyId: appStore.companyId, period: appStore.period }
    if (tab.value === 'income') {
      incomeRows.value = await rptApi.incomeStatement(opts.companyId, opts.period)
    } else if (tab.value === 'balance') {
      balanceRows.value = await rptApi.balanceSheet(opts.companyId, opts.period)
    } else {
      cashRows.value = await rptApi.cashFlow(opts.companyId, opts.period)
    }
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
    await rptApi.exportCsv(EXPORT_BY_TAB[tab.value], { companyId: appStore.companyId, period: appStore.period })
    message.success('导出成功，请查看下载文件')
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    exporting.value = false
  }
}

watch([tab, () => appStore.companyId, () => appStore.period], () => void load())
onMounted(() => void load())
</script>

<template>
  <div class="statement-page">
    <PageCard :loading="loading">
      <!-- 头部，期间 + 导出，tab 对应的报表类型 -->
      <FormGrid class="filter-grid">
        <n-form-item label="会计期间">
          <n-date-picker
            type="month"
            :value="periodTs"
            :clearable="false"
            style="width: 100%"
            @update:value="onPeriodChange"
          />
        </n-form-item>
        <n-form-item label="公司">
          <n-input :value="appStore.companyName || '默认公司'" readonly />
        </n-form-item>
        <n-form-item label="导出">
          <!-- 白名单收敛，fms.report.enabled-types 停用该类型时禁用导出，防 40001 -->
          <n-button type="primary" size="small" :loading="exporting" :disabled="!exportEnabled" @click="onExport">
            导出 CSV
          </n-button>
        </n-form-item>
      </FormGrid>

      <!-- 恒等式断链警示，仅资产负债表 -->
      <n-alert
        v-if="tab === 'balance' && identityBroken !== null && identityBroken"
        type="error"
        :bordered="false"
        class="identity-alert"
      >
        资产负债表恒等式断链：资产 ≠ 负债 + 权益，请核查凭证链
      </n-alert>

      <n-tabs v-model:value="tab" type="line" animated>
        <n-tab-pane name="income" tab="利润表">
          <DataTable v-if="incomeRows.length" :columns="incomeColumns" :data="incomeRows" :scroll-x="560" :local-pagination="true">
            <template #empty><span>暂无利润表数据</span></template>
          </DataTable>
          <n-empty v-else description="暂无利润表数据" />
        </n-tab-pane>
        <n-tab-pane name="balance" tab="资产负债表">
          <DataTable v-if="balanceRows.length" :columns="balanceColumns" :data="balanceRows" :scroll-x="560">
            <template #empty><span>暂无资产负债表数据</span></template>
          </DataTable>
          <n-empty v-else description="暂无资产负债表数据" />
        </n-tab-pane>
        <n-tab-pane name="cash" tab="现金流量表">
          <DataTable v-if="cashRows.length" :columns="cashColumns" :data="cashRows" :scroll-x="560">
            <template #empty><span>暂无现金流量表数据</span></template>
          </DataTable>
          <n-empty v-else description="暂无现金流量表数据" />
        </n-tab-pane>
      </n-tabs>
    </PageCard>
  </div>
</template>

<style scoped>
.statement-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.filter-grid {
  margin-bottom: 8px;
}
.identity-alert {
  margin-bottom: 8px;
}
</style>
