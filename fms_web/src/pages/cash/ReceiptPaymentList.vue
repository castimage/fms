<script setup lang="ts">
/**
 * 收付款单列表，现金银行，账户/方向/匹配状态筛选 + 服务端分页，
 * 登记弹窗新建流水，初始 UNMATCHED，仅 UNMATCHED 可删，已配对 40900。
 */
import { h, onMounted, ref, watch } from 'vue'
import { NButton, NDatePicker, NInput, NInputNumber, NModal, NPopconfirm, NRadio, NRadioGroup, NSelect, NTag, useMessage } from 'naive-ui'
import dayjs from 'dayjs'
import { cashApi } from '@/api/cash'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { BankAccountView, CashDirection, CashTransactionView, MatchStatus } from '@/types/cash'
import { formatDate } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'

const message = useMessage()
const appStore = useAppStore()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const bankAccountId = ref('')
const direction = ref<CashDirection | ''>('')
const matchStatus = ref<MatchStatus | ''>('')
const page = ref(1)
const size = ref(20)
const rows = ref<CashTransactionView[]>([])
const total = ref(0)
const loading = ref(false)

const DIRECTION_OPTIONS = [
  { label: '全部', value: '' },
  { label: '收入', value: 'IN' },
  { label: '支出', value: 'OUT' },
]
const MATCH_OPTIONS = [
  { label: '全部', value: '' },
  { label: '未匹配', value: 'UNMATCHED' },
  { label: '已匹配', value: 'MATCHED' },
]

// 账户下拉，分页大小取大，兼顾筛选与登记选择
const accountOptions = ref<{ label: string; value: string }[]>([])
async function loadAccounts() {
  const res = await cashApi.pageBankAccounts({ page: 1, size: await refdataPageSize() })
  accountOptions.value = res.items.map((r: BankAccountView) => ({
    label: `${r.bankName}（${r.accountNoMasked ?? '—'}）`,
    value: r.id,
  }))
}

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await cashApi.pageTransactions({
      page: page.value,
      size: size.value,
      bankAccountId: bankAccountId.value || undefined,
      direction: direction.value || undefined,
      matchStatus: matchStatus.value || undefined,
      companyId: appStore.companyId ?? undefined,
    })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

watch([bankAccountId, direction, matchStatus], () => {
  // 页码已为 1 时 page watcher 不触发，需直接重查
  page.value = 1
  void load()
})
watch([page, size], () => void load())
watch(() => appStore.companyId, () => {
  page.value = 1
  void load()
})

const showForm = ref(false)
const saving = ref(false)
const form = ref({
  bankAccountId: '',
  direction: 'IN' as CashDirection,
  amount: null as number | null,
  partner: '',
  summary: '',
  trxDate: dayjs().valueOf(),
})

function openCreate() {
  form.value = { bankAccountId: accountOptions.value[0]?.value ?? '', direction: 'IN', amount: null, partner: '', summary: '', trxDate: dayjs().valueOf() }
  showForm.value = true
}

async function save() {
  if (!form.value.bankAccountId) {
    message.warning('请选择银行账户')
    return
  }
  if (!form.value.amount || form.value.amount <= 0) {
    message.warning('金额必须大于 0')
    return
  }
  saving.value = true
  try {
    await cashApi.createTransaction({
      bankAccountId: form.value.bankAccountId,
      direction: form.value.direction,
      amount: form.value.amount,
      partner: form.value.partner || undefined,
      summary: form.value.summary || undefined,
      trxDate: dayjs(form.value.trxDate).format('YYYY-MM-DD'),
    })
    message.success('已登记')
    showForm.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onDelete(r: CashTransactionView) {
  await cashApi.deleteTransaction(r.id)
  message.success('已删除')
  await load()
}

function renderDirection(r: CashTransactionView) {
  const isIn = r.direction === 'IN'
  return h(NTag, { size: 'small', bordered: false, type: isIn ? 'success' : 'error' }, { default: () => (isIn ? '收入' : '支出') })
}

const renderMatch = (r: CashTransactionView) => h(StatusTag, { status: r.matchStatus })

const columns: FmsColumn<CashTransactionView>[] = [
  { key: 'bankName', title: '银行账户', width: 160, hideBelow: 'md' },
  { key: 'direction', title: '方向', width: 70, render: renderDirection },
  { key: 'amount', title: '金额', width: 130, align: 'right', render: (r) => h(MoneyText, { value: r.amount }) },
  { key: 'partner', title: '交易对手', width: 130, hideBelow: 'sm', render: (r) => r.partner || '—' },
  {
    key: 'summary',
    title: '摘要',
    width: 180,
    render: (r) => (r.summary ? (r.summary.length > 20 ? `${r.summary.slice(0, 20)}…` : r.summary) : '—'),
  },
  { key: 'trxDate', title: '交易日期', width: 110, render: (r) => formatDate(r.trxDate) },
  { key: 'matchStatus', title: '匹配状态', width: 90, render: renderMatch },
  { key: 'similarityScore', title: '相似度', width: 90, hideBelow: 'md', render: (r) => (r.similarityScore !== null ? r.similarityScore.toFixed(2) : '—') },
  {
    key: 'actions',
    title: '操作',
    width: 100,
    align: 'center',
    render: (r) =>
      r.matchStatus === 'UNMATCHED' && canWrite.value
        ? h(NPopconfirm, { onPositiveClick: () => onDelete(r) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
            default: () => '确认删除该流水？',
          })
        : h('span', { class: 'muted' }, '—'),
  },
]

onMounted(() => {
  void loadAccounts()
  void load()
})
</script>

<template>
  <div class="receipt-payment-list">
    <FormGrid class="filter-grid">
      <n-form-item label="银行账户">
        <n-select v-model:value="bankAccountId" :options="accountOptions" placeholder="全部" clearable />
      </n-form-item>
      <n-form-item label="方向">
        <n-select v-model:value="direction" :options="DIRECTION_OPTIONS" />
      </n-form-item>
      <n-form-item label="匹配状态">
        <n-select v-model:value="matchStatus" :options="MATCH_OPTIONS" />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">收付款单</span>
      <n-button v-if="canWrite" type="primary" size="small" @click="openCreate">登记收付款</n-button>
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
        <span>暂无流水数据</span>
      </template>
    </DataTable>

    <!-- 登记弹窗 -->
    <n-modal v-model:show="showForm" preset="card" title="登记收付款" style="width: 480px; max-width: 94vw">
      <n-form label-placement="top">
        <n-form-item label="银行账户">
          <n-select v-model:value="form.bankAccountId" :options="accountOptions" placeholder="选择银行账户" />
        </n-form-item>
        <n-form-item label="方向">
          <n-radio-group v-model:value="form.direction">
            <n-radio value="IN">收入</n-radio>
            <n-radio value="OUT">支出</n-radio>
          </n-radio-group>
        </n-form-item>
        <FormGrid>
          <n-form-item label="金额">
            <n-input-number v-model:value="form.amount" :min="0.01" :precision="2" placeholder="大于 0" style="width: 100%" />
          </n-form-item>
          <n-form-item label="交易日期">
            <n-date-picker v-model:value="form.trxDate" type="date" :clearable="false" style="width: 100%" />
          </n-form-item>
        </FormGrid>
        <n-form-item label="交易对手">
          <n-input v-model:value="form.partner" maxlength="100" placeholder="对方单位/个人（可选）" />
        </n-form-item>
        <n-form-item label="摘要">
          <n-input v-model:value="form.summary" maxlength="500" placeholder="对账模糊匹配数据源（可选）" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="showForm = false">取消</n-button>
        <n-button v-if="canWrite" type="primary" :loading="saving" @click="save">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.receipt-payment-list {
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
.muted {
  color: var(--n-text-color-3, var(--fms-text-muted));
}
</style>
