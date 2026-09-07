<script setup lang="ts">
/**
 * 应付账龄（/ap/aging），直查后端 /api/ap/aging，rpt.ap_aging 视图 + 窗口函数分桶，
 * 按供应商 × 账龄桶，0-30/31-60/61-90/91-180/180+聚合真实未核销余额
 * total - payment_allocations 已核销，immv rpt.ap_aging_paid 实时累计，与应收账龄页同构。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import { apApi } from '@/api/ap'
import { useAppStore } from '@/stores/app'
import PageCard from '@/components/common/PageCard.vue'
import DataTable from '@/components/common/DataTable.vue'
import EChart, { type EChartOption } from '@/components/common/EChart.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { ApAgingRow } from '@/types/ap'
import { formatAmount } from '@/utils/format'

const appStore = useAppStore()

const BUCKETS = ['0-30', '31-60', '61-90', '91-180', '180+'] as const
const BUCKET_LABELS: Record<string, string> = {
  '0-30': '0-30 天',
  '31-60': '31-60 天',
  '61-90': '61-90 天',
  '91-180': '91-180 天',
  '180+': '180 天以上',
}

const rows = ref<ApAgingRow[]>([])
const loading = ref(false)

// 客户端分页，账龄一次取全量，分页仅兜底大列表
const page = ref(1)
const size = ref(20)

async function load() {
  loading.value = true
  try {
    rows.value = await apApi.aging(appStore.companyId ?? undefined)
  } finally {
    loading.value = false
  }
}
watch(() => appStore.companyId, () => void load())
onMounted(() => void load())

/** 按供应商透视，行 = 供应商 × 各桶余额 + 合计 */
interface AgingRowView {
  supplierId: string
  supplierName: string
  buckets: Record<string, number>
  total: number
}
const pivotRows = computed<AgingRowView[]>(() => {
  const map = new Map<string, AgingRowView>()
  for (const r of rows.value) {
    let v = map.get(r.supplierId)
    if (!v) {
      v = { supplierId: r.supplierId, supplierName: r.supplierName, buckets: {}, total: 0 }
      map.set(r.supplierId, v)
    }
    const n = Number(r.openBalance) || 0
    v.buckets[r.bucket] = (v.buckets[r.bucket] ?? 0) + n
    v.total += n
  }
  return [...map.values()]
})

/** 客户端切片，DataTable 仅展示与分页控件，不做数据切片 */
const visibleRows = computed(() => {
  const start = (page.value - 1) * size.value
  return pivotRows.value.slice(start, start + size.value)
})

/** 每页条数变化后钳制页码，防越界空页 */
watch([page, size], () => {
  const maxPage = Math.max(1, Math.ceil(pivotRows.value.length / size.value))
  if (page.value > maxPage) page.value = maxPage
})

/** 各桶汇总，顶部统计卡 */
const bucketTotals = computed(() => {
  const t: Record<string, number> = {}
  for (const r of pivotRows.value) {
    for (const b of BUCKETS) t[b] = (t[b] ?? 0) + (r.buckets[b] ?? 0)
  }
  return t
})
const grandTotal = computed(() => pivotRows.value.reduce((s, r) => s + r.total, 0))

/** 堆叠柱，供应商 × 桶，未核销余额元 */
const chartOption = computed<EChartOption>(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v) => formatAmount(Number(v)) },
  legend: { data: BUCKETS.map((b) => BUCKET_LABELS[b]), top: 0 },
  grid: { left: 8, right: 8, top: 40, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: pivotRows.value.map((r) => r.supplierName), axisLabel: { interval: 0, rotate: 30 } },
  yAxis: { type: 'value' },
  series: BUCKETS.map((b) => ({
    name: BUCKET_LABELS[b],
    type: 'bar',
    stack: 'aging',
    barMaxWidth: 40,
    data: pivotRows.value.map((r) => r.buckets[b] ?? 0),
  })),
}))

function renderAmount(v: number) {
  return h('span', { class: 'num' }, formatAmount(v))
}

const columns: FmsColumn<AgingRowView>[] = [
  { key: 'supplierName', title: '供应商', width: 160 },
  { key: 'b0', title: BUCKET_LABELS['0-30'], width: 110, align: 'right', hideBelow: 'sm', render: (r) => renderAmount(r.buckets['0-30'] ?? 0) },
  { key: 'b1', title: BUCKET_LABELS['31-60'], width: 110, align: 'right', hideBelow: 'sm', render: (r) => renderAmount(r.buckets['31-60'] ?? 0) },
  { key: 'b2', title: BUCKET_LABELS['61-90'], width: 110, align: 'right', hideBelow: 'md', render: (r) => renderAmount(r.buckets['61-90'] ?? 0) },
  { key: 'b3', title: BUCKET_LABELS['91-180'], width: 110, align: 'right', hideBelow: 'md', render: (r) => renderAmount(r.buckets['91-180'] ?? 0) },
  { key: 'b4', title: BUCKET_LABELS['180+'], width: 110, align: 'right', hideBelow: 'md', render: (r) => renderAmount(r.buckets['180+'] ?? 0) },
  { key: 'total', title: '应付合计', width: 130, align: 'right', render: (r) => h('span', { class: 'num', style: 'font-weight:600' }, formatAmount(r.total)) },
]
</script>

<template>
  <PageCard title="应付账龄">
    <template #extra>
      <n-button size="small" :loading="loading" @click="load">刷新</n-button>
    </template>

    <!-- 桶汇总卡，手机 2 列换行 -->
    <div class="aging-summary">
      <div v-for="b in BUCKETS" :key="b" class="aging-card">
        <span class="aging-card-label">{{ BUCKET_LABELS[b] }}</span>
        <span class="aging-card-value num">{{ formatAmount(bucketTotals[b] ?? 0) }}</span>
      </div>
      <div class="aging-card aging-card-total">
        <span class="aging-card-label">应付合计</span>
        <span class="aging-card-value num">{{ formatAmount(grandTotal) }}</span>
      </div>
    </div>

    <EChart v-if="pivotRows.length" :option="chartOption" :height="300" />
    <div v-else class="aging-empty">暂无未核销应付余额</div>

    <DataTable v-model:page="page" v-model:size="size" :columns="columns" :data="visibleRows" :total="pivotRows.length" :loading="loading" :scroll-x="900" class="aging-table">
      <template #empty>
        <span>暂无账龄数据</span>
      </template>
    </DataTable>
  </PageCard>
</template>

<style scoped>
.aging-summary {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
@media (max-width: 992px) {
  .aging-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 576px) {
  .aging-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.aging-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  background: var(--n-color, #fff);
  border-radius: 8px;
}
html.dark .aging-card {
  background: var(--n-color, #1e1e1e);
}
.aging-card-total {
  background: var(--n-primary-color, #18a058);
  color: #fff;
}
.aging-card-label {
  font-size: 12px;
  opacity: 0.75;
}
.aging-card-value {
  font-size: 16px;
  font-weight: 600;
}
.aging-table {
  margin-top: 12px;
}
.aging-empty {
  padding: 32px 0;
  text-align: center;
  color: var(--fms-text-muted);
}
</style>
