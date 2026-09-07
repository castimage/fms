<script setup lang="ts">
/**
 * 付款管理（/ap/payments），状态/供应商筛选 + 服务端分页，新建付款登记，登记即 PAID，
 * 核销弹窗按供应商加载未付清发票（ISSUED/PARTIAL）批量核销，防重由 uk_ap_alloc 兜底。
 *
 * 核销明细仅存本次会话，后端无按付款单查核销端点，PAID/ALLOCATED 可冲销，清核销关联并
 * 回算发票状态，PAID 未核销可删除。
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
import dayjs from 'dayjs'
import { apApi } from '@/api/ap'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { ApInvoiceView, PaymentStatus, PaymentView } from '@/types/ap'
import { formatDate } from '@/utils/format'
import { toFen } from '@/utils/money'
import { useCanWrite } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'

const message = useMessage()
const appStore = useAppStore()
// 写操作仅 admin/operator 可见，viewer/auditor/report 只读
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '已付款', value: 'PAID' },
  { label: '已核销', value: 'ALLOCATED' },
  { label: '已冲销', value: 'REVERSED' },
]

const supplierOptions = ref<{ label: string; value: string }[]>([])
async function loadSuppliers() {
  if (!appStore.companyId) return
  const res = await apApi.pageSuppliers({ page: 1, size: await refdataPageSize(), companyId: appStore.companyId })
  supplierOptions.value = res.items.map((s) => ({ label: `${s.code} ${s.name}`, value: s.id }))
}

// 列表状态
const status = ref<PaymentStatus | ''>('')
const supplierId = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<PaymentView[]>([])
const total = ref(0)
const loading = ref(false)

let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await apApi.pagePayments({
      page: page.value,
      size: size.value,
      companyId: appStore.companyId ?? undefined,
      supplierId: supplierId.value || undefined,
      status: status.value || undefined,
    })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

/** 筛选变化重置到第一页并重查，page 未变时直接 load，防 watch 漏触发 */
watch([status, supplierId], () => {
  if (page.value === 1) void load()
  else page.value = 1
})
watch(() => appStore.companyId, () => void loadSuppliers())
watch([page, size, () => appStore.companyId], () => void load())
onMounted(async () => {
  await loadSuppliers()
  await load()
})

// 新建付款单
const showForm = ref(false)
const formRef = ref<FormInst | null>(null)
const formLoading = ref(false)
const form = ref({ supplierId: '', amount: null as number | null, paymentDate: dayjs().format('YYYY-MM-DD') })

const paymentDateTs = computed(() => (form.value.paymentDate ? dayjs(form.value.paymentDate).valueOf() : null))

const rules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: ['blur', 'change'] }],
  amount: [{ required: true, message: '付款金额必填', trigger: ['blur', 'change'] }],
}

function openCreate() {
  form.value = { supplierId: '', amount: null, paymentDate: dayjs().format('YYYY-MM-DD') }
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
    await apApi.createPayment({
      companyId: appStore.companyId ?? '',
      supplierId: form.value.supplierId,
      amount: form.value.amount ?? 0,
      paymentDate: form.value.paymentDate,
    })
    message.success('付款已登记')
    showForm.value = false
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    formLoading.value = false
  }
}

async function onDelete(id: string) {
  await apApi.deletePayment(id)
  await load()
}
async function onReverse(id: string) {
  await apApi.reverse(id)
  sessionAllocs.value = sessionAllocs.value.filter((a) => a.paymentId !== id)
  await load()
}

// 会话级核销明细，无按付款单查询核销端点，撤销仅会话内可见
interface SessionAlloc {
  id: string
  paymentId: string
  invoiceId: string
  invoiceNo: string
  amount: number
}

const sessionAllocs = ref<SessionAlloc[]>([])

/** 付款单本次会话已核销合计，剩余可核销 = amount - 该值 */
function allocSumByPayment(paymentId: string): number {
  return sessionAllocs.value.filter((a) => a.paymentId === paymentId).reduce((s, a) => s + a.amount, 0)
}
/** 发票已被会话核销的累计，可核销余额 = total - 该值 */
function allocSumByInvoice(invoiceId: string): number {
  return sessionAllocs.value.filter((a) => a.invoiceId === invoiceId).reduce((s, a) => s + a.amount, 0)
}

// 核销弹窗
const showAlloc = ref(false)
const allocLoading = ref(false)
const activePayment = ref<PaymentView | null>(null)
/** 可核销发票，供应商维度 ISSUED/PARTIAL 未付清 + 本次输入金额 */
const allocRows = ref<{ invoice: ApInvoiceView; amount: number | null }[]>([])

async function openAllocate(p: PaymentView) {
  activePayment.value = p
  allocRows.value = []
  showAlloc.value = true
  allocLoading.value = true
  try {
    const res = await apApi.pageInvoices({ page: 1, size: await refdataPageSize(), companyId: appStore.companyId ?? undefined, supplierId: p.supplierId })
    // 已核销余额未暴露，以发票总额 - 会话已核销 近似，口径同账龄页
    allocRows.value = res.items
      .filter((i) => i.status === 'ISSUED' || i.status === 'PARTIAL')
      .map((invoice) => ({ invoice, amount: null }))
  } finally {
    allocLoading.value = false
  }
}

const remainingAlloc = computed(() => {
  if (!activePayment.value) return 0
  return activePayment.value.amount - allocSumByPayment(activePayment.value.id)
})

async function submitAlloc() {
  const items = allocRows.value
    .filter((r) => (r.amount ?? 0) > 0)
    .map((r) => ({ invoiceId: r.invoice.id, amount: r.amount ?? 0 }))
  if (!items.length) {
    message.warning('请至少输入一笔核销金额')
    return
  }
  const sum = items.reduce((s, i) => s + i.amount, 0)
  if (sum > remainingAlloc.value) {
    message.warning(`核销合计 ${sum} 超过可核销余额 ${remainingAlloc.value}`)
    return
  }
  if (!activePayment.value) return
  allocLoading.value = true
  try {
    const allocs = await apApi.allocate(activePayment.value.id, items)
    for (const a of allocs) {
      sessionAllocs.value.push({
        id: a.id,
        paymentId: activePayment.value.id,
        invoiceId: a.invoiceId,
        invoiceNo: a.invoiceNo,
        amount: a.amount,
      })
    }
    message.success(`已核销 ${allocs.length} 笔`)
    showAlloc.value = false
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    allocLoading.value = false
  }
}

async function onUnallocate(allocationId: string) {
  await apApi.unallocate(allocationId)
  sessionAllocs.value = sessionAllocs.value.filter((a) => a.id !== allocationId)
  message.success('已撤销核销')
  await load()
}

const columns: FmsColumn<PaymentView>[] = [
  { key: 'paymentDate', title: '付款日期', width: 110, render: (r) => formatDate(r.paymentDate) },
  { key: 'supplierName', title: '供应商', width: 170, hideBelow: 'sm' },
  { key: 'bankName', title: '付款账户', width: 120, hideBelow: 'md', render: (r) => r.bankName ?? '未指定' },
  {
    key: 'amount',
    title: '付款金额',
    width: 120,
    align: 'right',
    render: (r) => h(MoneyText, { value: r.amount, symbol: '¥' }),
  },
  { key: 'status', title: '状态', width: 100, render: (r) => h(StatusTag, { status: r.status }) },
  { key: 'createdAt', title: '登记时间', width: 130, hideBelow: 'sm', render: (r) => formatDate(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 200,
    align: 'center',
    render: (r) => {
      const nodes: ReturnType<typeof h>[] = []
      if ((r.status === 'PAID' || r.status === 'ALLOCATED') && canWrite.value) {
        nodes.push(
          h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openAllocate(r) }, { default: () => '核销' }),
        )
      }
      if (allocSumByPayment(r.id) > 0 && canWrite.value) {
        nodes.push(
          h(NButton, { text: true, size: 'small', onClick: () => openAllocate(r) }, { default: () => '撤销核销' }),
        )
      }
      if ((r.status === 'PAID' || r.status === 'ALLOCATED') && canWrite.value) {
        nodes.push(
          h(NPopconfirm, { onPositiveClick: () => onReverse(r.id) }, {
            trigger: () => h(NButton, { text: true, type: 'warning', size: 'small' }, { default: () => '冲销' }),
            default: () => '确认冲销该付款单？将清空核销关联',
          }),
        )
      }
      if (r.status === 'PAID' && allocSumByPayment(r.id) === 0 && canWrite.value) {
        nodes.push(
          h(NPopconfirm, { onPositiveClick: () => onDelete(r.id) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
            default: () => '确认删除该付款单？',
          }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px' }, nodes)
    },
  },
]
</script>

<template>
  <div class="payment-list">
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
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">付款管理</span>
      <n-button v-if="canWrite" type="primary" size="small" @click="openCreate">新建付款单</n-button>
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
        <span>暂无付款数据</span>
      </template>
    </DataTable>

    <!-- 新建付款单 -->
    <n-modal
      v-model:show="showForm"
      preset="card"
      title="新建付款单"
      style="width: min(480px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" size="small">
        <n-form-item label="供应商" path="supplierId">
          <n-select v-model:value="form.supplierId" :options="supplierOptions" filterable placeholder="选择供应商" style="width: 100%" />
        </n-form-item>
        <n-form-item label="付款金额" path="amount">
          <n-input-number v-model:value="form.amount" :min="0" :precision="2" style="width: 100%" placeholder="> 0" />
        </n-form-item>
        <n-form-item label="付款日期" path="paymentDate">
          <n-date-picker
            v-model:value="paymentDateTs"
            type="date"
            :clearable="false"
            style="width: 100%"
            @update:value="(v: number | null) => (form.paymentDate = v ? dayjs(v).format('YYYY-MM-DD') : '')"
          />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showForm = false">取消</n-button>
        <n-button v-if="canWrite" size="small" type="primary" :loading="formLoading" @click="submitForm">登记付款</n-button>
      </template>
    </n-modal>

    <!-- 核销弹窗 -->
    <n-modal
      v-model:show="showAlloc"
      preset="card"
      title="付款核销"
      style="width: min(680px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-spin :show="allocLoading">
        <template v-if="activePayment">
          <div class="alloc-summary">
            <span>付款金额：<b class="num">{{ activePayment.amount }}</b></span>
            <span>已核销：<b class="num">{{ allocSumByPayment(activePayment.id) }}</b></span>
            <span>可核销：<b class="num">{{ remainingAlloc }}</b></span>
          </div>

          <!-- 本次会话已核销明细，撤销仅会话内可见 -->
          <n-divider v-if="allocSumByPayment(activePayment!.id) > 0" title-placement="left">已核销明细（本次会话）</n-divider>
          <div v-if="allocSumByPayment(activePayment!.id) > 0" class="alloc-done">
            <div v-for="a in sessionAllocs.filter((x) => x.paymentId === activePayment!.id)" :key="a.id" class="alloc-done-row">
              <span class="num">{{ a.invoiceNo }}</span>
              <MoneyText :value="a.amount" />
              <n-button v-if="canWrite" text size="small" type="error" @click="onUnallocate(a.id)">撤销</n-button>
            </div>
          </div>

          <n-divider title-placement="left">待核销发票（ISSUED/PARTIAL 未付清）</n-divider>
          <n-data-table
            :columns="[
              { key: 'no', title: '发票号', width: 110, render: (r: any) => r.invoice.invoiceNo },
              { key: 'date', title: '开票日期', width: 110, render: (r: any) => formatDate(r.invoice.invoiceDate) },
              { key: 'total', title: '发票总额', width: 110, align: 'right', render: (r: any) => h(MoneyText, { value: r.invoice.total }) },
              { key: 'open', title: '可核销余额', width: 110, align: 'right', render: (r: any) => h(MoneyText, { fen: (toFen(r.invoice.total) ?? 0) - (toFen(allocSumByInvoice(r.invoice.id)) ?? 0) }) },
              { key: 'input', title: '本次核销', width: 130, render: (r: any) => h(NInputNumber, { value: r.amount, min: 0, precision: 2, placeholder: '金额', onUpdateValue: (v: number | null) => (r.amount = v) }) },
            ]"
            :data="allocRows"
            size="small"
            :scroll-x="600"
          >
            <template #empty>
              <span>该供应商暂无未付清发票</span>
            </template>
          </n-data-table>
        </template>
      </n-spin>
      <template #action>
        <n-button size="small" @click="showAlloc = false">取消</n-button>
        <n-button v-if="canWrite" size="small" type="primary" :loading="allocLoading" @click="submitAlloc">确认核销</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.payment-list {
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
.alloc-summary {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 13px;
  margin-bottom: 4px;
}
.alloc-done-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--n-divider-color, #eee);
}
</style>
