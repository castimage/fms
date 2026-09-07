<script setup lang="ts">
/**
 * 试算平衡表，平衡状态横幅，balanced=false 即凭证链断链异常，ui 必须覆盖 + 6 项汇总卡
 * 期末列高亮 + 分科目表，余额有符号，负值标红 + 合计行，手机汇总卡 2 列 + 行展开。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NDatePicker, NEmpty, NInput, NStatistic } from 'naive-ui'
import dayjs from 'dayjs'
import { glApi } from '@/api/gl'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { TrialBalanceRow, TrialBalanceView as TBView } from '@/types/gl'
import { formatAmount } from '@/utils/format'

const appStore = useAppStore()

const view = ref<TBView | null>(null)
const loading = ref(false)
const page = ref(1)
const size = ref(20)

const periodTs = computed(() => (appStore.period ? dayjs(appStore.period + '-01').valueOf() : null))
function onPeriodChange(ts: number | null) {
  appStore.setPeriod(ts ? dayjs(ts).format('YYYY-MM') : dayjs().format('YYYY-MM'))
  void load() // 期间变更直接重查，watch 联动实测不可靠，双保险
}

async function load() {
  if (!appStore.companyId) return
  loading.value = true
  try {
    view.value = await glApi.trialBalance(appStore.companyId, appStore.period)
  } finally {
    loading.value = false
  }
}
/** 期间切换自动重查 */
watch(() => appStore.period, () => void load())
onMounted(() => void load())

/** 金额单元格，有符号余额，负值标红，.num.negative 见 styles/index.css */
function renderAmount(v: number) {
  return h('span', { class: ['num', v < 0 ? 'negative' : ''] }, formatAmount(v))
}

/** 汇总行，加在表格末尾的合计 */
type TableRow = TrialBalanceRow & { isSummary?: boolean }

const tableRows = computed<TableRow[]>(() => {
  const v = view.value
  if (!v) return []
  const summary: TableRow = {
    accountId: '',
    accountCode: '合计',
    accountName: '',
    direction: '',
    openingBalance: v.openingDebitTotal - v.openingCreditTotal,
    periodDebit: v.periodDebitTotal,
    periodCredit: v.periodCreditTotal,
    closingBalance: v.closingDebitTotal - v.closingCreditTotal,
    isSummary: true,
  }
  return [...v.rows, summary]
})

const columns: FmsColumn<TableRow>[] = [
  {
    key: 'accountCode',
    title: '科目编码',
    width: 110,
    render: (r) => h('span', { class: r.isSummary ? 'summary-cell' : '' }, r.accountCode),
  },
  {
    key: 'accountName',
    title: '科目名称',
    width: 140,
    hideBelow: 'sm',
    render: (r) => h('span', { class: r.isSummary ? 'summary-cell' : '' }, r.accountName),
  },
  { key: 'openingBalance', title: '期初余额', width: 130, align: 'right', render: (r) => renderAmount(r.openingBalance) },
  { key: 'periodDebit', title: '本期借方', width: 130, align: 'right', render: (r) => renderAmount(r.periodDebit) },
  { key: 'periodCredit', title: '本期贷方', width: 130, align: 'right', render: (r) => renderAmount(r.periodCredit) },
  { key: 'closingBalance', title: '期末余额', width: 130, align: 'right', render: (r) => renderAmount(r.closingBalance) },
]

/** 汇总卡数据，两列对照，借方/贷方 */
const summaryStats = computed(() => {
  const v = view.value
  if (!v) return []
  return [
    { label: '期初借方', value: v.openingDebitTotal, highlight: false },
    { label: '期初贷方', value: v.openingCreditTotal, highlight: false },
    { label: '本期借方', value: v.periodDebitTotal, highlight: false },
    { label: '本期贷方', value: v.periodCreditTotal, highlight: false },
    { label: '期末借方', value: v.closingDebitTotal, highlight: true },
    { label: '期末贷方', value: v.closingCreditTotal, highlight: true },
  ]
})
</script>

<template>
  <div class="tb-page">
    <!-- 筛选 -->
    <FormGrid>
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
      <n-form-item label="期间">
        <n-date-picker
          type="month"
          :value="periodTs"
          :clearable="false"
          style="width: 100%"
          @update:value="onPeriodChange"
        />
      </n-form-item>
      <n-form-item label=" ">
        <n-button type="primary" size="small" :loading="loading" @click="load">查询</n-button>
      </n-form-item>
    </FormGrid>

    <!-- 平衡状态横幅，balanced=false 理论不可达，ui 仍必须覆盖 -->
    <n-alert v-if="view" :type="view.balanced ? 'success' : 'error'" :bordered="false">
      <template #icon>
        <span>{{ view.balanced ? '✓' : '!' }}</span>
      </template>
      <template #default>
        <span v-if="view.balanced">
          借贷平衡（期末借方合计 = 期末贷方合计，{{ formatAmount(view.closingDebitTotal) }}）
        </span>
        <span v-else>借贷不平衡——凭证链异常，请联系管理员</span>
      </template>
    </n-alert>

    <!-- 汇总卡，桌面 3 列 / 手机 2 列网格，期末列高亮 -->
    <div v-if="view" class="summary-grid">
      <div
        v-for="s in summaryStats"
        :key="s.label"
        :class="['summary-item', s.highlight ? 'is-highlight' : '']"
      >
        <n-statistic :label="s.label" :value="formatAmount(s.value)" />
      </div>
    </div>

    <!-- 分科目表，含合计行 -->
    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="columns"
      :data="tableRows"
      :loading="loading"
      :scroll-x="700" :local-pagination="true"
      :row-key="(r) => (r.isSummary ? 'summary' : r.accountId)"
    >
      <template #empty>
        <n-empty description="当前期间无过账凭证" />
      </template>
      <template #rowDetail="{ row }">
        <div v-if="!row.isSummary" class="row-detail">
          <div>科目：{{ row.accountCode }} {{ row.accountName }}</div>
          <div>期初余额：{{ formatAmount(row.openingBalance) }}</div>
          <div>本期借方：{{ formatAmount(row.periodDebit) }}</div>
          <div>本期贷方：{{ formatAmount(row.periodCredit) }}</div>
          <div>期末余额：{{ formatAmount(row.closingBalance) }}</div>
        </div>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.tb-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* 汇总卡，桌面 3 列 / 手机 2 列 */
.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
@media (min-width: 768px) {
  .summary-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
.summary-item {
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--n-color, #fff);
}
html.dark .summary-item {
  background: var(--n-color, #1e1e1e);
}
.is-highlight {
  box-shadow: inset 0 0 0 1px var(--fms-primary, #1677ff);
}
.summary-cell {
  font-weight: 700;
}
.row-detail {
  font-size: 12px;
  line-height: 1.8;
  color: var(--fms-text-secondary);
}
</style>
