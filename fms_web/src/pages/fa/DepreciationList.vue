<script setup lang="ts">
/**
 * 折旧计提批次列表（/fa/depreciation），期间筛选 + 服务端分页，批次行显示资产数与计提总额
 * lines jsonb 解析，明细弹窗行级展示，DRAFT 批次可确认
 * 同公司同期间仅一条 CONFIRMED，重复确认后端 40900。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NDatePicker, NModal, NPopconfirm, NTable, useMessage } from 'naive-ui'
import dayjs from 'dayjs'
import { faApi } from '@/api/fa'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { DepreciationRunView } from '@/types/fa'
import { formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const appStore = useAppStore()
// 写角色（admin/operator）才可见确认按钮，其余角色只读
const canWrite = useCanWrite()

const period = ref(appStore.period)
const periodTs = computed(() => (period.value ? dayjs(period.value + '-01').valueOf() : null))
function onPeriodChange(ts: number | null) {
  period.value = ts ? dayjs(ts).format('YYYY-MM') : ''
  // 页码已为 1 时 page watcher 不触发，需直接重查
  if (page.value === 1) void load()
  else page.value = 1
}

const page = ref(1)
const size = ref(20)
const rows = ref<DepreciationRunView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await faApi.pageDepreciationRuns({
      page: page.value,
      size: size.value,
      period: period.value || undefined,
    })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

watch([page, size], () => void load())
onMounted(() => void load())

const detailVisible = ref(false)
const detailRun = ref<DepreciationRunView | null>(null)

function openDetail(r: DepreciationRunView) {
  detailRun.value = r
  detailVisible.value = true
}

/** 批次计提总额，lines jsonb 求和，服务端未聚合 */
function runTotal(r: DepreciationRunView): number {
  return r.lines.reduce((s, l) => s + l.amount, 0)
}

async function onConfirm(r: DepreciationRunView) {
  await faApi.confirmDepreciation(r.id)
  message.success('批次已确认')
  await load()
}

const columns: FmsColumn<DepreciationRunView>[] = [
  { key: 'period', title: '期间', width: 110 },
  { key: 'status', title: '状态', width: 90, render: (r) => h(StatusTag, { status: r.status }) },
  {
    key: 'assetCount',
    title: '资产数',
    width: 90,
    align: 'right',
    render: (r) => String(r.lines.length),
  },
  {
    key: 'total',
    title: '计提总额',
    width: 140,
    align: 'right',
    hideBelow: 'md',
    render: (r) => h(MoneyText, { value: runTotal(r), symbol: '¥' }),
  },
  { key: 'createdAt', title: '计提时间', width: 160, hideBelow: 'sm', render: (r) => formatDateTime(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 130,
    align: 'center',
    render: (r) => {
      const nodes = [h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openDetail(r) }, { default: () => '明细' })]
      // 确认批次为写操作，仅 admin/operator 可见
      if (r.status === 'DRAFT' && canWrite.value) {
        nodes.push(
          h(NPopconfirm, { onPositiveClick: () => onConfirm(r) }, {
            trigger: () => h(NButton, { text: true, size: 'small' }, { default: () => '确认' }),
            default: () => '确认后同公司同期间不可重复计提，是否继续？',
          }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px' }, nodes)
    },
  },
]
</script>

<template>
  <div class="depr-list">
    <FormGrid class="filter-grid">
      <n-form-item label="期间">
        <n-date-picker
          v-model:value="periodTs"
          type="month"
          clearable
          style="width: 100%"
          @update:value="onPeriodChange"
        />
      </n-form-item>
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">折旧记录</span>
    </div>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="columns"
      :data="rows"
      :loading="loading"
      :total="total"
      :scroll-x="900"
    >
      <template #empty>
        <span>暂无折旧批次数据</span>
      </template>
    </DataTable>

    <!-- 批次明细弹窗，jsonb 行级展开 -->
    <n-modal
      v-model:show="detailVisible"
      preset="card"
      :title="detailRun ? `计提明细 · ${detailRun.period}` : '计提明细'"
      style="width: min(640px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-table :bordered="false" size="small">
        <thead>
          <tr>
            <th>资产编码</th>
            <th>类别</th>
            <th>方法</th>
            <th class="num-col">计提金额</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(l, i) in detailRun?.lines ?? []" :key="i">
            <td>{{ l.assetCode }}</td>
            <td>{{ l.category }}</td>
            <td>{{ l.method === 'STRAIGHT_LINE' ? '直线法' : '双倍余额递减' }}</td>
            <td class="num-col"><MoneyText :value="l.amount" symbol="¥" /></td>
          </tr>
        </tbody>
      </n-table>
      <template #action>
        <n-button size="small" @click="detailVisible = false">关闭</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.depr-list {
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
}
.toolbar-title {
  font-weight: 600;
}
.num-col {
  text-align: right;
}
</style>
