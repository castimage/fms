<script setup lang="ts">
/**
 * 坏账计提台账弹窗，InvoiceList 工具栏入口，分页列表（Pager）+ 计提表单
 * 发票取未核销 ISSUED/PARTIAL，DRAFT 行可确认/删除，确认后禁改禁删。
 */
import { computed, h, ref, watch } from 'vue'
import { NButton, NDataTable, NDatePicker, NInputNumber, NModal, NPopconfirm, NSelect, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import dayjs from 'dayjs'
import { arApi } from '@/api/ar'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import Pager from '@/components/common/Pager.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { BadDebtStatus, BadDebtView } from '@/types/ar'
import { formatAmount, formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'

const show = defineModel<boolean>('show', { default: false })
const message = useMessage()
const appStore = useAppStore()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '已确认', value: 'CONFIRMED' },
  { label: '已冲销', value: 'REVERSED' },
]

// 期间选择，n-date-picker 的 v-model:value 类型为 number|string|数组|null，与 Ref<string>
// 逆变不兼容，故改用 :value + @update:formatted-value 拿格式化字符串
function onPeriodChange(v: string | [string, string] | null) {
  period.value = Array.isArray(v) ? v[0] : (v ?? '')
}

// 列表
const status = ref<BadDebtStatus | ''>('')
const page = ref(1)
const size = ref(10)
const rows = ref<BadDebtView[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await arApi.pageBadDebts({
      page: page.value,
      size: size.value,
      companyId: appStore.companyId ?? undefined,
      status: status.value || undefined,
    })
    rows.value = res.items
    total.value = res.total
  } finally {
    loading.value = false
  }
}

// 计提表单
const invoiceOptions = ref<{ label: string; value: string }[]>([])
const invoiceId = ref<string | null>(null)
const period = ref(dayjs().format('YYYY-MM'))
const amount = ref<number | null>(null)
const saving = ref(false)

/** 未核销发票下拉，ISSUED/PARTIAL 各取前 N 条，N 读 web.refdata.page.size，缺省 200 */
async function loadInvoices() {
  const optSize = await refdataPageSize()
  const [a, b] = await Promise.all([
    arApi.pageInvoices({ page: 1, size: optSize, companyId: appStore.companyId ?? undefined, status: 'ISSUED' }),
    arApi.pageInvoices({ page: 1, size: optSize, companyId: appStore.companyId ?? undefined, status: 'PARTIAL' }),
  ])
  invoiceOptions.value = [...a.items, ...b.items].map((inv) => ({
    label: `${inv.invoiceNo}（${inv.customerName} ¥${formatAmount(inv.total)}）`,
    value: inv.id,
  }))
}

async function onCreate() {
  if (!invoiceId.value || !period.value || amount.value === null || amount.value === undefined) {
    message.warning('请选择发票、期间并填写计提金额')
    return
  }
  saving.value = true
  try {
    await arApi.createBadDebt({ invoiceId: invoiceId.value, period: period.value, amount: amount.value })
    message.success('计提已创建')
    invoiceId.value = null
    amount.value = null
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function onConfirm(row: BadDebtView) {
  await arApi.confirmBadDebt(row.id)
  message.success('计提已确认')
  await load()
}

async function onDelete(row: BadDebtView) {
  await arApi.deleteBadDebt(row.id)
  message.success('计提已删除')
  await load()
}

const columns = computed<DataTableColumns>(() => [
  { key: 'invoiceNo', title: '发票号', width: 140 },
  { key: 'period', title: '期间', width: 90 },
  { key: 'amount', title: '计提金额', width: 120, align: 'right', render: (r: BadDebtView) => h('span', { class: 'num' }, formatAmount(r.amount)) },
  { key: 'status', title: '状态', width: 90, render: (r: BadDebtView) => h(StatusTag, { status: r.status }) },
  { key: 'createdAt', title: '创建时间', width: 150, render: (r: BadDebtView) => formatDateTime(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 110,
    align: 'center',
    render: (r: BadDebtView) =>
      r.status === 'DRAFT' && canWrite.value
        ? h('span', { style: 'display:inline-flex;gap:8px' }, [
            h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => onConfirm(r) }, { default: () => '确认' }),
            h(NPopconfirm, { onPositiveClick: () => onDelete(r) }, {
              trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
              default: () => '确认删除该计提？',
            }),
          ])
        : null,
  },
] as DataTableColumns)

/** 状态筛选回第一页并重查 */
watch(status, () => {
  page.value = 1
  void load()
})
watch([page, size], () => void load())
/** 打开弹窗时刷新列表与发票下拉 */
watch(show, (v) => {
  if (v) {
    void load()
    void loadInvoices()
  }
})
</script>

<template>
  <n-modal v-model:show="show" preset="card" title="坏账计提台账" style="width: 760px; max-width: 94vw">
    <!-- 计提表单，发票取未核销（ISSUED/PARTIAL），期间缺省当月 -->
    <FormGrid>
      <n-form-item label="发票">
        <n-select v-model:value="invoiceId" :options="invoiceOptions" filterable placeholder="选择未核销发票" style="width: 100%" />
      </n-form-item>
      <n-form-item label="计提期间">
        <n-date-picker :formatted-value="period" type="month" value-format="yyyy-MM" @update:formatted-value="onPeriodChange" style="width: 100%" />
      </n-form-item>
      <n-form-item label="计提金额">
        <n-input-number v-model:value="amount" :min="0" :precision="2" style="width: 100%" />
      </n-form-item>
      <n-form-item>
        <n-button v-if="canWrite" type="primary" :loading="saving" @click="onCreate">创建计提</n-button>
      </n-form-item>
    </FormGrid>

    <n-select v-model:value="status" :options="STATUS_OPTIONS" size="small" style="width: 160px; margin-bottom: 8px" />
    <n-data-table :columns="columns" :data="rows" :loading="loading" size="small" :scroll-x="760" />
    <Pager v-model:page="page" v-model:size="size" :total="total" />
  </n-modal>
</template>
