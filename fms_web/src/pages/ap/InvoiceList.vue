<script setup lang="ts">
/**
 * 应付发票列表（/ap/invoices），供应商/状态/关键词筛选 + 服务端分页，
 * 新建发票弹窗，DRAFT 起步，DRAFT 行可审核开票（issue），ISSUED/PARTIAL 行可作废
 * void，不可变触发器允许的唯一已开票迁移。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NDatePicker,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  useMessage,
} from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import dayjs from 'dayjs'
import { apApi } from '@/api/ap'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { ApInvoiceStatus, ApInvoiceView, SupplierView } from '@/types/ap'
import { formatDate } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'

const message = useMessage()
const appStore = useAppStore()
// 写操作仅 admin/operator 可见，viewer/auditor/report 只读
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '已开票', value: 'ISSUED' },
  { label: '部分核销', value: 'PARTIAL' },
  { label: '已付清', value: 'PAID' },
  { label: '已作废', value: 'VOID' },
]

// 供应商下拉，筛选与新建表单共用
const supplierOptions = ref<{ label: string; value: string }[]>([])
async function loadSuppliers() {
  if (!appStore.companyId) return
  const res = await apApi.pageSuppliers({ page: 1, size: await refdataPageSize(), companyId: appStore.companyId })
  supplierOptions.value = res.items.map((s: SupplierView) => ({ label: `${s.code} ${s.name}`, value: s.id }))
}

// 筛选状态
const keyword = ref('')
const status = ref<ApInvoiceStatus | ''>('')
const supplierId = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<ApInvoiceView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await apApi.pageInvoices({
      page: page.value,
      size: size.value,
      companyId: appStore.companyId ?? undefined,
      supplierId: supplierId.value || undefined,
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
watch([status, supplierId], onFilterChange)
watch(() => appStore.companyId, () => void loadSuppliers())
watch([page, size, () => appStore.companyId], () => void load())
onMounted(async () => {
  await loadSuppliers()
  await load()
})

// 状态动作
async function onIssue(id: string) {
  await apApi.issueInvoice(id)
  message.success('已开票，发票进入资金闭环（不可再改）')
  await load()
}
async function onVoid(id: string) {
  await apApi.voidInvoice(id)
  message.success('已作废')
  await load()
}

// 新建发票弹窗
const showForm = ref(false)
const formRef = ref<FormInst | null>(null)
const formLoading = ref(false)
const form = ref({
  supplierId: '',
  invoiceNo: '',
  invoiceDate: dayjs().format('YYYY-MM-DD'),
  dueDate: '',
  currency: 'CNY',
  total: null as number | null,
  tax: 0,
})

const invoiceDateTs = computed(() => (form.value.invoiceDate ? dayjs(form.value.invoiceDate).valueOf() : null))
const dueDateTs = computed(() => (form.value.dueDate ? dayjs(form.value.dueDate).valueOf() : null))

const rules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: ['blur', 'change'] }],
  invoiceNo: [{ required: true, message: '发票号必填', trigger: ['blur'] }],
  total: [{ required: true, message: '发票总额必填', trigger: ['blur', 'change'] }],
}

function openCreate() {
  form.value = {
    supplierId: '',
    invoiceNo: '',
    invoiceDate: dayjs().format('YYYY-MM-DD'),
    dueDate: '',
    currency: 'CNY',
    total: null,
    tax: 0,
  }
  showForm.value = true
}

async function submitForm() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  formLoading.value = true
  try {
    await apApi.createInvoice({
      companyId: appStore.companyId ?? '',
      supplierId: form.value.supplierId,
      invoiceNo: form.value.invoiceNo.trim(),
      invoiceDate: form.value.invoiceDate,
      dueDate: form.value.dueDate || undefined,
      currency: form.value.currency || 'CNY',
      total: form.value.total ?? 0,
      tax: form.value.tax || undefined,
    })
    message.success('已创建草稿发票')
    showForm.value = false
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    formLoading.value = false
  }
}

const columns: FmsColumn<ApInvoiceView>[] = [
  { key: 'invoiceNo', title: '发票号', width: 110 },
  { key: 'supplierName', title: '供应商', width: 160, hideBelow: 'sm' },
  { key: 'invoiceDate', title: '开票日期', width: 110, render: (r) => formatDate(r.invoiceDate) },
  { key: 'dueDate', title: '到期日', width: 110, hideBelow: 'md', render: (r) => formatDate(r.dueDate) },
  { key: 'currency', title: '币种', width: 70, hideBelow: 'sm' },
  {
    key: 'total',
    title: '发票总额',
    width: 120,
    align: 'right',
    render: (r) => h(MoneyText, { value: r.total, symbol: r.currency === 'CNY' ? '¥' : '' }),
  },
  { key: 'tax', title: '税额', width: 110, align: 'right', hideBelow: 'md', render: (r) => h(MoneyText, { value: r.tax }) },
  { key: 'status', title: '状态', width: 100, render: (r) => h(StatusTag, { status: r.status }) },
  {
    key: 'actions',
    title: '操作',
    width: 140,
    align: 'center',
    render: (r) => {
      const nodes: ReturnType<typeof h>[] = []
      if (r.status === 'DRAFT' && canWrite.value) {
        nodes.push(
          h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => onIssue(r.id) }, { default: () => '审核开票' }),
        )
      }
      if ((r.status === 'ISSUED' || r.status === 'PARTIAL') && canWrite.value) {
        nodes.push(
          h(NPopconfirm, { onPositiveClick: () => onVoid(r.id) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '作废' }),
            default: () => '确认作废该发票？作废后不可恢复',
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
    <FormGrid class="filter-grid">
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
      <n-form-item label="供应商">
        <n-select v-model:value="supplierId" :options="supplierOptions" clearable filterable placeholder="全部供应商" style="width: 100%" />
      </n-form-item>
      <n-form-item label="状态">
        <n-select v-model:value="status" :options="STATUS_OPTIONS" style="width: 100%" />
      </n-form-item>
      <n-form-item label="关键词">
        <n-input v-model:value="keyword" placeholder="发票号" clearable />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">应付发票</span>
      <n-button v-if="canWrite" type="primary" size="small" @click="openCreate">新建发票</n-button>
    </div>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="columns"
      :data="rows"
      :loading="loading"
      :total="total"
      :scroll-x="1000"
    >
      <template #empty>
        <span>暂无发票数据</span>
      </template>
    </DataTable>

    <n-modal
      v-model:show="showForm"
      preset="card"
      title="新建发票"
      style="width: min(520px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" size="small">
        <n-form-item label="供应商" path="supplierId">
          <n-select v-model:value="form.supplierId" :options="supplierOptions" filterable placeholder="选择供应商" style="width: 100%" />
        </n-form-item>
        <n-form-item label="发票号" path="invoiceNo">
          <n-input v-model:value="form.invoiceNo" maxlength="32" placeholder="≤32 字符，公司内唯一" />
        </n-form-item>
        <n-form-item label="开票日期" path="invoiceDate">
          <n-date-picker v-model:value="invoiceDateTs" type="date" :clearable="false" style="width: 100%" @update:value="(v: number | null) => (form.invoiceDate = v ? dayjs(v).format('YYYY-MM-DD') : '')" />
        </n-form-item>
        <n-form-item label="到期日">
          <n-date-picker v-model:value="dueDateTs" type="date" clearable style="width: 100%" @update:value="(v: number | null) => (form.dueDate = v ? dayjs(v).format('YYYY-MM-DD') : '')" />
        </n-form-item>
        <n-form-item label="含税总额" path="total">
          <n-input-number v-model:value="form.total" :min="0" :precision="2" style="width: 100%" placeholder="> 0" />
        </n-form-item>
        <n-form-item label="税额">
          <n-input-number v-model:value="form.tax" :min="0" :precision="2" style="width: 100%" placeholder="缺省 0" />
        </n-form-item>
        <n-form-item label="币种（ISO 3 位）">
          <n-input v-model:value="form.currency" maxlength="3" placeholder="CNY" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showForm = false">取消</n-button>
        <n-button v-if="canWrite" size="small" type="primary" :loading="formLoading" @click="submitForm">创建草稿</n-button>
      </template>
    </n-modal>
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
.toolbar-title {
  font-weight: 600;
}
</style>
