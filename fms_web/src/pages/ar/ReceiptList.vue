<script setup lang="ts">
/**
 * 收款单列表，客户/状态筛选 + 服务端分页，登记收款 n-modal，
 * RECEIVED 行可核销与删除，选择未核销发票 + 金额，防重由后端 uk_ar_alloc 双闸兜底，
 * RECEIVED/ALLOCATED 行可冲销，置 REVERSED，reason 审计。
 */
import { h, ref, watch } from 'vue'
import { NButton, NDatePicker, NInput, NInputNumber, NModal, NPopconfirm, NSelect, useMessage } from 'naive-ui'
import dayjs from 'dayjs'
import { arApi } from '@/api/ar'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { useCustomerOptions } from './useCustomerOptions'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { ArInvoiceView, ReceiptRequest, ReceiptStatus, ReceiptView } from '@/types/ar'
import { formatAmount, formatDate } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'

const message = useMessage()
const appStore = useAppStore()
const { customerOptions } = useCustomerOptions()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '已登记', value: 'RECEIVED' },
  { label: '已核销', value: 'ALLOCATED' },
  { label: '已冲销', value: 'REVERSED' },
]

// 列表状态
const customerId = ref<string | null>(null)
const status = ref<ReceiptStatus | ''>('')
const page = ref(1)
const size = ref(20)
const rows = ref<ReceiptView[]>([])
const total = ref(0)
const loading = ref(false)

let reqSeq = 0

/** 服务端分页查询，竞态序号作废旧结果 */
async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await arApi.pageReceipts({
      page: page.value,
      size: size.value,
      companyId: appStore.companyId ?? undefined,
      customerId: customerId.value ?? undefined,
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
watch([customerId, status], () => {
  if (page.value === 1) void load()
  else page.value = 1
})
watch([page, size, () => appStore.companyId], () => void load())

// 登记收款弹窗
const showCreate = ref(false)
const saving = ref(false)
const receiptForm = ref<ReceiptRequest>({
  companyId: '',
  customerId: '',
  amount: 0,
  receiptDate: dayjs().format('YYYY-MM-DD'),
})
const receiptDateTs = ref<number>(dayjs().valueOf())

function openCreate() {
  receiptForm.value = {
    companyId: appStore.companyId ?? '',
    customerId: '',
    amount: 0,
    receiptDate: dayjs().format('YYYY-MM-DD'),
  }
  receiptDateTs.value = dayjs().valueOf()
  showCreate.value = true
}

async function onCreate() {
  if (!receiptForm.value.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  if (!receiptForm.value.customerId || !receiptForm.value.amount || receiptForm.value.amount <= 0) {
    message.warning('请选择客户并填写收款金额（大于 0）')
    return
  }
  saving.value = true
  try {
    await arApi.createReceipt(receiptForm.value)
    message.success('收款已登记')
    showCreate.value = false
    await load()
  } catch (e) {
    // 全局已提示（http.ts），本地仅兜底
    if (e instanceof Error) message.error(e.message)
  } finally {
    saving.value = false
  }
}

// 核销弹窗
const showAllocate = ref(false)
const allocating = ref(false)
const allocateReceipt = ref<ReceiptView | null>(null)
const allocateInvoiceId = ref<string | null>(null)
const allocateAmount = ref<number | null>(null)
const invoiceOptions = ref<{ label: string; value: string }[]>([])

/** 核销候选发票，同客户未核销 ISSUED/PARTIAL 各取前 N 条，N 读 web.refdata.page.size，缺省 200 */
async function openAllocate(row: ReceiptView) {
  allocateReceipt.value = row
  allocateInvoiceId.value = null
  allocateAmount.value = null
  const allocSize = await refdataPageSize()
  const [a, b] = await Promise.all([
    arApi.pageInvoices({ page: 1, size: allocSize, companyId: appStore.companyId ?? undefined, customerId: row.customerId, status: 'ISSUED' }),
    arApi.pageInvoices({ page: 1, size: allocSize, companyId: appStore.companyId ?? undefined, customerId: row.customerId, status: 'PARTIAL' }),
  ])
  invoiceOptions.value = [...a.items, ...b.items].map((inv: ArInvoiceView) => ({
    label: `${inv.invoiceNo}（${formatAmount(inv.total)}）`,
    value: inv.id,
  }))
  showAllocate.value = true
}

async function onAllocate() {
  const r = allocateReceipt.value
  if (!r || !allocateInvoiceId.value || !allocateAmount.value || allocateAmount.value <= 0) {
    message.warning('请选择发票并填写核销金额（大于 0）')
    return
  }
  allocating.value = true
  try {
    const alloc = await arApi.allocate(r.id, { invoiceId: allocateInvoiceId.value, amount: allocateAmount.value })
    message.success(`已核销发票 ${alloc.invoiceNo} ${formatAmount(alloc.amount)}`)
    showAllocate.value = false
    await load()
  } catch (e) {
    // 全局已提示（http.ts），本地仅兜底
    if (e instanceof Error) message.error(e.message)
  } finally {
    allocating.value = false
  }
}

// 冲销弹窗，reason 可空，审计追溯
const showReverse = ref(false)
const reversing = ref(false)
const reverseReceipt = ref<ReceiptView | null>(null)
const reverseReason = ref('')

function openReverse(row: ReceiptView) {
  reverseReceipt.value = row
  reverseReason.value = ''
  showReverse.value = true
}

async function onReverse() {
  const r = reverseReceipt.value
  if (!r) return
  reversing.value = true
  try {
    await arApi.reverseReceipt(r.id, reverseReason.value || undefined)
    message.success('收款单已冲销')
    showReverse.value = false
    await load()
  } catch (e) {
    // 全局已提示（http.ts），本地仅兜底
    if (e instanceof Error) message.error(e.message)
  } finally {
    reversing.value = false
  }
}

async function onDelete(row: ReceiptView) {
  await arApi.deleteReceipt(row.id)
  message.success('收款单已删除')
  await load()
}

function renderAmount(v: number) {
  return h('span', { class: 'num' }, formatAmount(v))
}

const columns: FmsColumn<ReceiptView>[] = [
  { key: 'customerName', title: '客户', width: 150 },
  { key: 'receiptDate', title: '收款日期', width: 110, render: (r) => formatDate(r.receiptDate) },
  { key: 'bankName', title: '收款账户', width: 140, hideBelow: 'md', render: (r) => r.bankName || '未指定' },
  { key: 'amount', title: '金额', width: 120, align: 'right', render: (r) => renderAmount(r.amount) },
  { key: 'status', title: '状态', width: 100, render: (r) => h(StatusTag, { status: r.status }) },
  {
    key: 'actions',
    title: '操作',
    width: 160,
    align: 'center',
    render: (r) => {
      if (!canWrite.value) return ''
      const nodes = [
        h(NButton, { text: true, type: 'primary', size: 'small', disabled: r.status !== 'RECEIVED', onClick: () => openAllocate(r) }, { default: () => '核销' }),
        h(NButton, { text: true, type: 'warning', size: 'small', disabled: r.status === 'REVERSED', onClick: () => openReverse(r) }, { default: () => '冲销' }),
      ]
      if (r.status === 'RECEIVED') {
        nodes.push(
          h(NPopconfirm, { onPositiveClick: () => onDelete(r) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
            default: () => '确认删除该收款单？',
          }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px' }, nodes)
    },
  },
]
</script>

<template>
  <div class="receipt-list">
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
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">收款单列表</span>
      <n-button v-if="canWrite" type="primary" size="small" @click="openCreate">登记收款</n-button>
    </div>

    <DataTable v-model:page="page" v-model:size="size" :columns="columns" :data="rows" :loading="loading" :total="total" :scroll-x="900">
      <template #empty>
        <span>暂无收款单数据</span>
      </template>
    </DataTable>

    <!-- 登记收款弹窗 -->
    <n-modal v-model:show="showCreate" preset="card" title="登记收款" style="width: 520px; max-width: 92vw">
      <FormGrid>
        <n-form-item label="客户">
          <n-select v-model:value="receiptForm.customerId" :options="customerOptions" filterable placeholder="选择客户" style="width: 100%" />
        </n-form-item>
        <n-form-item label="收款日期">
          <n-date-picker v-model:value="receiptDateTs" type="date" :clearable="false" style="width: 100%" @update:value="(ts: number | null) => ts && (receiptForm.receiptDate = dayjs(ts).format('YYYY-MM-DD'))" />
        </n-form-item>
        <n-form-item label="收款金额">
          <n-input-number v-model:value="receiptForm.amount" :min="0.01" :precision="2" style="width: 100%" />
        </n-form-item>
      </FormGrid>
      <template #footer>
        <div class="modal-actions">
          <n-button @click="showCreate = false">取消</n-button>
          <n-button v-if="canWrite" type="primary" :loading="saving" @click="onCreate">保存</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 核销弹窗，收款单核销未核销发票 -->
    <n-modal v-model:show="showAllocate" preset="card" title="核销收款" style="width: 520px; max-width: 92vw">
      <p v-if="allocateReceipt" class="allocate-tip">
        收款单：{{ allocateReceipt.customerName }} · {{ formatAmount(allocateReceipt.amount) }}
      </p>
      <FormGrid>
        <n-form-item label="发票">
          <n-select v-model:value="allocateInvoiceId" :options="invoiceOptions" filterable placeholder="选择未核销发票" style="width: 100%" />
        </n-form-item>
        <n-form-item label="核销金额">
          <n-input-number v-model:value="allocateAmount" :min="0.01" :precision="2" style="width: 100%" />
        </n-form-item>
      </FormGrid>
      <template #footer>
        <div class="modal-actions">
          <n-button @click="showAllocate = false">取消</n-button>
          <n-button v-if="canWrite" type="primary" :loading="allocating" @click="onAllocate">核销</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 冲销弹窗，reason 审计追溯，可空 -->
    <n-modal v-model:show="showReverse" preset="card" title="冲销收款单" style="width: 480px; max-width: 92vw">
      <n-form-item label="冲销原因">
        <n-input v-model:value="reverseReason" maxlength="256" placeholder="可选" type="textarea" :rows="2" />
      </n-form-item>
      <template #footer>
        <div class="modal-actions">
          <n-button @click="showReverse = false">取消</n-button>
          <n-button v-if="canWrite" type="error" :loading="reversing" @click="onReverse">确认冲销</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.receipt-list {
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
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.allocate-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--fms-text-muted);
}
</style>
