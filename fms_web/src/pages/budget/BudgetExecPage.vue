<script setup lang="ts">
/**
 * 预算执行对比，plan LEFT JOIN rpt.budget_actual immv + 已批调整净额，
 * 期间/部门过滤 + 执行率/可用余额汇总，warn 80% / block 100% 阈值与 db 超支触发器同源
 * 超支入账会被拒绝，本页仅展示不阻断。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NDatePicker, NInput, NSelect, NTag } from 'naive-ui'
import type { TagProps } from 'naive-ui'
import dayjs from 'dayjs'
import { budgetApi } from '@/api/budget'
import * as orgApi from '@/api/org'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { DepartmentView } from '@/types/org'
import type { BudgetExecutionRow, BudgetLevel } from '@/types/budget'
import { formatAmount, formatPeriod } from '@/utils/format'
import { refdataPageSize } from '@/utils/refdata'

const appStore = useAppStore()

const deptId = ref<string | null>(null)
const rows = ref<BudgetExecutionRow[]>([])
const loading = ref(false)
const depts = ref<DepartmentView[]>([])

/** 期间，绑定 appStore.period，月选择器时间戳与 YYYY-MM 互转 */
const periodTs = computed(() => (appStore.period ? dayjs(appStore.period + '-01').valueOf() : null))
function onPeriodChange(ts: number | null) {
  appStore.setPeriod(ts ? dayjs(ts).format('YYYY-MM') : dayjs().format('YYYY-MM'))
}

const deptOptions = computed(() => depts.value.map((d) => ({ label: d.name, value: d.id })))

async function loadDepts() {
  const res = await orgApi.pageDepartments({ page: 1, size: await refdataPageSize(), companyId: appStore.companyId ?? undefined })
  depts.value = res.items
}

async function load() {
  loading.value = true
  try {
    rows.value = await budgetApi.execution({
      companyId: appStore.companyId ?? undefined,
      period: appStore.period,
      deptId: deptId.value || undefined,
    })
  } finally {
    loading.value = false
  }
}

watch([() => appStore.companyId, () => appStore.period, deptId], () => void load())

const LEVEL_TAG: Record<BudgetLevel, { label: string; type: TagProps['type'] }> = {
  NORMAL: { label: '正常', type: 'success' },
  WARN: { label: '预警', type: 'warning' },
  BLOCK: { label: '超支', type: 'error' },
}

/** 执行率展示，ratio 标度 4，乘 100 转百分比 */
function renderRatio(v: number) {
  return h('span', { class: 'num' }, `${(v * 100).toFixed(2)}%`)
}

const columns: FmsColumn<BudgetExecutionRow>[] = [
  { key: 'period', title: '期间', width: 90, render: (r) => formatPeriod(r.period) },
  { key: 'account', title: '预算科目', width: 180, render: (r) => `${r.accountCode} ${r.accountName}` },
  { key: 'deptName', title: '部门', width: 120, hideBelow: 'sm' },
  { key: 'planAmount', title: '预算金额', width: 130, align: 'right', render: (r) => h(MoneyText, { value: r.planAmount }) },
  { key: 'adjustedAmount', title: '调整净额', width: 130, align: 'right', hideBelow: 'md', render: (r) => h(MoneyText, { value: r.adjustedAmount }) },
  { key: 'actualAmount', title: '实际金额', width: 130, align: 'right', render: (r) => h(MoneyText, { value: r.actualAmount }) },
  { key: 'availableAmount', title: '可用余额', width: 130, align: 'right', render: (r) => h(MoneyText, { value: r.availableAmount }) },
  { key: 'ratio', title: '执行率', width: 100, align: 'right', hideBelow: 'md', render: (r) => renderRatio(r.ratio) },
  {
    key: 'level',
    title: '级别',
    width: 90,
    render: (r) => {
      const t = LEVEL_TAG[r.level]
      return h(NTag, { type: t.type, size: 'small', bordered: false }, { default: () => t.label })
    },
  },
]

/** 汇总，预算/实际/可用，当前过滤集 */
const summary = computed(() => ({
  plan: rows.value.reduce((s, r) => s + r.planAmount, 0),
  actual: rows.value.reduce((s, r) => s + r.actualAmount, 0),
  available: rows.value.reduce((s, r) => s + r.availableAmount, 0),
}))
const blockCount = computed(() => rows.value.filter((r) => r.level === 'BLOCK').length)

onMounted(() => {
  void loadDepts()
  void load()
})
</script>

<template>
  <div class="budget-exec">
    <FormGrid class="filter-grid">
      <n-form-item label="期间">
        <n-date-picker type="month" :value="periodTs" :clearable="false" style="width: 100%" @update:value="onPeriodChange" />
      </n-form-item>
      <n-form-item label="部门">
        <n-select v-model:value="deptId" :options="deptOptions" filterable clearable placeholder="全部部门" style="width: 100%" />
      </n-form-item>
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
    </FormGrid>

    <!-- 超支提示，阻断由 db 触发器承担 -->
    <n-alert v-if="blockCount > 0" type="error" :bordered="false">
      存在 {{ blockCount }} 项超支科目，超支凭证入账将被 DB 触发器拒绝（40900）
    </n-alert>

    <!-- 工具栏，汇总 + 刷新 -->
    <div class="toolbar">
      <span class="toolbar-title">预算执行对比</span>
      <span class="summary">预算 {{ formatAmount(summary.plan) }} / 实际 {{ formatAmount(summary.actual) }} / 可用 {{ formatAmount(summary.available) }}</span>
      <n-button size="small" @click="load">刷新</n-button>
    </div>

    <DataTable :columns="columns" :data="rows" :loading="loading" :total="rows.length" :scroll-x="1100" :local-pagination="true">
      <template #empty>
        <span>暂无执行数据</span>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.budget-exec {
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
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar-title {
  font-weight: 600;
}
.summary {
  font-size: 13px;
  color: var(--n-text-color-3, var(--fms-text-muted));
}
</style>
