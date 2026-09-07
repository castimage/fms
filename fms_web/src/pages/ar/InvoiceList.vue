<script setup lang="ts">
/**
 * 发票列表，客户/状态/关键词筛选 + 服务端分页，新建/编辑 n-modal，仅 DRAFT 可改，
 * DRAFT 行可开具（issue）与删除，ISSUED/PARTIAL 行可作废（void），"坏账台账"入口复用 BadDebtModal。
 */
import { h, ref, watch } from 'vue'
import { NButton, NDatePicker, NInput, NInputNumber, NModal, NPopconfirm, NSelect, useMessage } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import dayjs from 'dayjs'
import { arApi } from '@/api/ar'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import BadDebtModal from './BadDebtModal.vue'
import { useCustomerOptions } from './useCustomerOptions'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { ArInvoiceRequest, ArInvoiceView, InvoiceStatus } from '@/types/ar'
import { formatAmount, formatDate } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const appStore = useAppStore()
const { customerOptions } = useCustomerOptions()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '已开具', value: 'ISSUED' },
  { label: '部分核销', value: 'PARTIAL' },
  { label: '已核销', value: 'PAID' },
  { label: '已作废', value: 'VOID' },
]

// 列表状态
const keyword = ref('')
const customerId = ref<string | null>(null)
const status = ref<InvoiceStatus | ''>('')
const page = ref(1)
const size = ref(20)
const rows = ref<ArInvoiceView[]>([])
const total = ref(0)
const loading = ref(false)

let reqSeq = 0

/** 服务端分页查询，竞态序号作废旧结果 */
async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await arApi.pageInvoices({
      page: page.value,
      size: size.value,
      companyId: appStore.companyId ?? undefined,
      customerId: customerId.value ?? undefined,
      status: status.value || undefined,
      keyword: keyword.value || undefined,
    })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

/** 筛选变化重置到第一页并重查，page 未变时直接 load，防 watch 漏触发 */
function onFilterChange() {
  if (page.value === 1) void load()
  else page.value = 1
}
const onKeywordSearch = useDebounceFn(onFilterChange, 500)
watch(keyword, () => onKeywordSearch())
watch([customerId, status], onFilterChange)
watch([page, size, () => appStore.companyId], () => void load())

// 新建/编辑弹窗，仅 DRAFT 可编辑
const showModal = ref(false)
const editing = ref<ArInvoiceView | null>(null)
const saving = ref(false)
const form = ref<ArInvoiceRequest>({
  companyId: '',
  customerId: '',
  invoiceNo: '',
  invoiceDate: dayjs().format('YYYY-MM-DD'),
  dueDate: undefined,
  currency: 'CNY',
  total: 0,
  tax: 0,
})
const invoiceDateTs = ref<number>(dayjs().valueOf())
const dueDateTs = ref<number | null>(null)

/** 打开弹窗，create 空表单 / edit 回填 */
function openModal(row?: ArInvoiceView) {
  editing.value = row ?? null
  form.value = {
    companyId: appStore.companyId ?? '',
    customerId: row?.customerId ?? '',
    invoiceNo: row?.invoiceNo ?? '',
    invoiceDate: row ? dayjs(row.invoiceDate).format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD'),
    dueDate: row?.dueDate ? dayjs(row.dueDate).format('YYYY-MM-DD') : undefined,
    currency: row?.currency ?? 'CNY',
    total: row?.total ?? 0,
    tax: row?.tax ?? 0,
  }
  invoiceDateTs.value = dayjs(form.value.invoiceDate).valueOf()
  dueDateTs.value = form.value.dueDate ? dayjs(form.value.dueDate).valueOf() : null
  showModal.value = true
}

async function onSave() {
  if (!form.value.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  if (!form.value.customerId) {
    message.warning('请选择客户')
    return
  }
  if (!form.value.invoiceNo || !form.value.total || form.value.total <= 0) {
    message.warning('发票号必填且总额须大于 0')
    return
  }
  saving.value = true
  try {
    if (editing.value) await arApi.updateInvoice(editing.value.id, form.value)
    else await arApi.createInvoice(form.value)
    message.success(editing.value ? '发票已更新' : '发票已创建（草稿）')
    showModal.value = false
    await load()
  } catch (e) {
    // 全局已提示（http.ts），本地仅兜底
    if (e instanceof Error) message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function onDelete(row: ArInvoiceView) {
  await arApi.deleteInvoice(row.id)
  message.success('发票已删除')
  await load()
}

async function onIssue(row: ArInvoiceView) {
  await arApi.issueInvoice(row.id)
  message.success('发票已开具')
  await load()
}

async function onVoid(row: ArInvoiceView) {
  await arApi.voidInvoice(row.id)
  message.success('发票已作废')
  await load()
}

const showBadDebt = ref(false)

function renderAmount(v: number) {
  return h('span', { class: 'num' }, formatAmount(v))
}

const columns: FmsColumn<ArInvoiceView>[] = [
  { key: 'invoiceNo', title: '发票号', width: 120 },
  { key: 'customerName', title: '客户', width: 140, hideBelow: 'sm' },
  { key: 'invoiceDate', title: '开票日期', width: 110, render: (r) => formatDate(r.invoiceDate) },
  { key: 'dueDate', title: '到期日', width: 110, hideBelow: 'md', render: (r) => formatDate(r.dueDate) },
  { key: 'currency', title: '币种', width: 70, hideBelow: 'md' },
  { key: 'total', title: '总额', width: 120, align: 'right', render: (r) => renderAmount(r.total) },
  { key: 'status', title: '状态', width: 100, render: (r) => h(StatusTag, { status: r.status }) },
  {
    key: 'actions',
    title: '操作',
    width: 160,
    align: 'center',
    render: (r) => {
      if (!canWrite.value) return ''
      const nodes: ReturnType<typeof h>[] = []
      if (r.status === 'DRAFT') {
        nodes.push(
          h(NButton, { text: true, size: 'small', onClick: () => openModal(r) }, { default: () => '编辑' }),
          h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => onIssue(r) }, { default: () => '开具' }),
          h(NPopconfirm, { onPositiveClick: () => onDelete(r) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
            default: () => '确认删除该草稿发票？',
          }),
        )
      } else if (r.status === 'ISSUED' || r.status === 'PARTIAL') {
        nodes.push(
          h(NPopconfirm, { onPositiveClick: () => onVoid(r) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '作废' }),
            default: () => '确认作废该发票？',
          }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px' }, nodes)
    },
  },
]
</script>

<template>
  <div class="invoice-list">
    <!-- 筛选区，手机 1 列 / 平板 2 列 / 桌面 3 列 -->
    <FormGrid class="filter-grid">
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
      <n-form-item label="客户">
        <n-select v-model:value="customerId" :options="customerOptions" filterable clearable placeholder="全部客户" style="width: 100%" />
      </n-form-item>
      <n-form-item label="状态">
        <n-select v-model:value="status" :options="STATUS_OPTIONS" style="width: 100%" />
      </n-form-item>
      <n-form-item label="关键词">
        <n-input v-model:value="keyword" placeholder="发票号 / 客户名" clearable @update:value="onKeywordSearch" />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">发票列表</span>
      <div class="toolbar-actions">
        <n-button v-if="canWrite" size="small" @click="showBadDebt = true">坏账台账</n-button>
        <n-button v-if="canWrite" type="primary" size="small" @click="openModal()">新建发票</n-button>
      </div>
    </div>

    <DataTable v-model:page="page" v-model:size="size" :columns="columns" :data="rows" :loading="loading" :total="total" :scroll-x="1000">
      <template #empty>
        <span>暂无发票数据</span>
      </template>
    </DataTable>

    <!-- 新建/编辑发票弹窗，FormGrid 手机单列 -->
    <n-modal v-model:show="showModal" preset="card" :title="editing ? '编辑发票' : '新建发票'" style="width: 620px; max-width: 92vw">
      <FormGrid>
        <n-form-item label="客户">
          <n-select v-model:value="form.customerId" :options="customerOptions" filterable placeholder="选择客户" style="width: 100%" />
        </n-form-item>
        <n-form-item label="发票号">
          <n-input v-model:value="form.invoiceNo" maxlength="32" placeholder="公司内唯一" />
        </n-form-item>
        <n-form-item label="开票日期">
          <n-date-picker v-model:value="invoiceDateTs" type="date" :clearable="false" style="width: 100%" @update:value="(ts: number | null) => ts && (form.invoiceDate = dayjs(ts).format('YYYY-MM-DD'))" />
        </n-form-item>
        <n-form-item label="到期日">
          <n-date-picker v-model:value="dueDateTs" type="date" clearable style="width: 100%" @update:value="(ts: number | null) => (form.dueDate = ts ? dayjs(ts).format('YYYY-MM-DD') : undefined)" />
        </n-form-item>
        <n-form-item label="币种">
          <n-input v-model:value="form.currency" maxlength="3" placeholder="CNY" />
        </n-form-item>
        <n-form-item label="总额">
          <n-input-number v-model:value="form.total" :min="0.01" :precision="2" style="width: 100%" />
        </n-form-item>
        <n-form-item label="税额">
          <n-input-number v-model:value="form.tax" :min="0" :precision="2" style="width: 100%" />
        </n-form-item>
      </FormGrid>
      <template #footer>
        <div class="modal-actions">
          <n-button @click="showModal = false">取消</n-button>
          <n-button v-if="canWrite" type="primary" :loading="saving" @click="onSave">保存</n-button>
        </div>
      </template>
    </n-modal>

    <BadDebtModal v-model:show="showBadDebt" />
  </div>
</template>

<style scoped>
.invoice-list {
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
.toolbar-actions {
  display: flex;
  gap: 8px;
}
.toolbar-title {
  font-weight: 600;
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
