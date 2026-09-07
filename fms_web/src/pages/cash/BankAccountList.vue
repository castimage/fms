<script setup lang="ts">
/**
 * 银行账户列表，现金银行，分页 + 新建/编辑/详情，账号解密唯一出口/软删，
 * 底部资金日报卡片，期初/收付/期末 + 分账户收付 + 未来 7 天预测。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NDatePicker, NDescriptions, NDescriptionsItem, NInput, NModal, NPopconfirm, NSelect, NSwitch, useMessage } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import dayjs from 'dayjs'
import { cashApi } from '@/api/cash'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import PageCard from '@/components/common/PageCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { BankAccountRequest, BankAccountType, BankAccountView, DailyReportView } from '@/types/cash'
import { formatDate, formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const appStore = useAppStore()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const keyword = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<BankAccountView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await cashApi.pageBankAccounts({ page: page.value, size: size.value, keyword: keyword.value || undefined })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

const onKeywordSearch = useDebounceFn(() => {
  page.value = 1
  void load()
}, 500)
watch(keyword, () => onKeywordSearch())
watch([page, size], () => void load())
watch(() => appStore.companyId, () => {
  page.value = 1
  void load()
  void loadDaily()
})

interface AccountForm {
  bankName: string
  accountNo: string
  accountNoMasked: string
  accountType: BankAccountType
  currency: string
  status: BankAccountView['status']
  isActive: boolean
}

const TYPE_OPTIONS = [
  { label: '对公', value: 'CORPORATE' },
  { label: '个人', value: 'PERSONAL' },
]
const STATUS_OPTIONS = [
  { label: '启用', value: 'ACTIVE' },
  { label: '冻结', value: 'FROZEN' },
  { label: '已销户', value: 'CLOSED' },
]

function emptyForm(): AccountForm {
  return { bankName: '', accountNo: '', accountNoMasked: '', accountType: 'CORPORATE', currency: 'CNY', status: 'ACTIVE', isActive: true }
}

const showForm = ref(false)
const editingId = ref('')
const saving = ref(false)
const form = ref<AccountForm>(emptyForm())
const formTitle = computed(() => (editingId.value ? '编辑账户' : '新建账户'))

function openCreate() {
  editingId.value = ''
  form.value = emptyForm()
  showForm.value = true
}

/** 编辑回填，账号变更=新建，后端忽略更新的 accountNo，故禁改 */
function openEdit(r: BankAccountView) {
  editingId.value = r.id
  form.value = {
    bankName: r.bankName,
    accountNo: '',
    accountNoMasked: r.accountNoMasked ?? '',
    accountType: r.accountType,
    currency: r.currency,
    status: r.status,
    isActive: r.isActive,
  }
  showForm.value = true
}

async function save() {
  if (!form.value.bankName) {
    message.warning('开户行必填')
    return
  }
  if (!editingId.value && !form.value.accountNo) {
    message.warning('银行账号必填')
    return
  }
  const req: BankAccountRequest = {
    bankName: form.value.bankName,
    accountNo: form.value.accountNo,
    accountNoMasked: form.value.accountNoMasked || undefined,
    accountType: form.value.accountType,
    currency: form.value.currency,
    status: form.value.status,
    isActive: form.value.isActive,
  }
  saving.value = true
  try {
    if (editingId.value) {
      await cashApi.updateBankAccount(editingId.value, req)
    } else {
      await cashApi.createBankAccount(req)
    }
    message.success('已保存')
    showForm.value = false
    await load()
  } finally {
    saving.value = false
  }
}

// 详情为账号解密唯一出口，rls 越权时后端返回 null
const showDetail = ref(false)
const detailLoading = ref(false)
const detail = ref<BankAccountView | null>(null)

async function openDetail(r: BankAccountView) {
  detailLoading.value = true
  try {
    const res = await cashApi.getBankAccount(r.id)
    if (!res) {
      message.error('账户不存在或无权查看')
      return
    }
    detail.value = res
    showDetail.value = true
  } finally {
    detailLoading.value = false
  }
}

async function onDelete(r: BankAccountView) {
  await cashApi.deleteBankAccount(r.id)
  message.success('已删除')
  await load()
}

const dailyDate = ref(dayjs().valueOf())
const daily = ref<DailyReportView | null>(null)
const dailyLoading = ref(false)

async function loadDaily() {
  if (!appStore.companyId) {
    daily.value = null
    return
  }
  dailyLoading.value = true
  try {
    daily.value = await cashApi.dailyReport(appStore.companyId, dayjs(dailyDate.value).format('YYYY-MM-DD'))
  } finally {
    dailyLoading.value = false
  }
}
watch(dailyDate, () => void loadDaily())

const bankNameOf = computed(() => new Map(rows.value.map((r) => [r.id, r.bankName])))

const renderStatus = (r: BankAccountView) => h(StatusTag, { status: r.status })

const columns: FmsColumn<BankAccountView>[] = [
  { key: 'bankName', title: '开户行', width: 180 },
  { key: 'accountNoMasked', title: '账号掩码', width: 140, render: (r) => r.accountNoMasked ?? '—' },
  { key: 'accountType', title: '类型', width: 90, hideBelow: 'md', render: (r) => (r.accountType === 'CORPORATE' ? '对公' : '个人') },
  { key: 'currency', title: '币种', width: 80, hideBelow: 'sm' },
  { key: 'status', title: '状态', width: 90, render: renderStatus },
  { key: 'isActive', title: '启用', width: 70, hideBelow: 'sm', render: (r) => (r.isActive ? '是' : '否') },
  { key: 'createdAt', title: '创建时间', width: 150, hideBelow: 'md', render: (r) => formatDateTime(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 170,
    align: 'center',
    render: (r) => {
      const nodes = [h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openDetail(r) }, { default: () => '详情' })]
      if (canWrite.value) {
        nodes.push(
          h(NButton, { text: true, size: 'small', onClick: () => openEdit(r) }, { default: () => '编辑' }),
          h(NPopconfirm, { onPositiveClick: () => onDelete(r) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
            default: () => '确认删除该账户？',
          }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px' }, nodes)
    },
  },
]

/** 日报统计项（label + MoneyText） */
const dailyStats = computed(() => {
  const d = daily.value
  if (!d) return []
  return [
    { label: '期初余额', value: d.openingBalance },
    { label: '当日收入', value: d.income },
    { label: '当日支出', value: d.expense },
    { label: '当日净额', value: d.net },
    { label: '期末余额', value: d.closingBalance },
  ]
})

onMounted(() => {
  void load()
  void loadDaily()
})
</script>

<template>
  <div class="bank-account-list">
    <FormGrid class="filter-grid">
      <n-form-item label="关键词">
        <n-input v-model:value="keyword" placeholder="开户行 / 账号掩码" clearable />
      </n-form-item>
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">银行账户</span>
      <n-button v-if="canWrite" type="primary" size="small" @click="openCreate">新建账户</n-button>
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
        <span>暂无账户数据</span>
      </template>
    </DataTable>

    <PageCard title="资金日报" :loading="dailyLoading">
      <template #extra>
        <n-date-picker v-model:value="dailyDate" type="date" size="small" style="width: 160px" />
      </template>
      <n-alert v-if="!appStore.companyId" type="warning" :bordered="false">
        请先在顶栏选择公司后查看资金日报
      </n-alert>
      <template v-else-if="daily">
        <div class="daily-stats">
          <div v-for="s in dailyStats" :key="s.label" class="daily-stat">
            <span class="daily-stat-label">{{ s.label }}</span>
            <MoneyText :value="s.value" />
          </div>
        </div>
        <div v-if="daily.byAccount.length" class="daily-section">
          <div class="daily-section-title">分账户收付（{{ formatDate(daily.date) }}）</div>
          <div v-for="row in daily.byAccount" :key="row.bankAccountId" class="daily-account-row">
            <span class="daily-account-name">{{ bankNameOf.get(row.bankAccountId) ?? '—' }}</span>
            <span>收 <MoneyText :value="row.income" /></span>
            <span>支 <MoneyText :value="row.expense" /></span>
          </div>
        </div>
        <div v-if="daily.forecast.length" class="daily-section">
          <div class="daily-section-title">未来 7 天资金预测（应收 - 应付）</div>
          <div v-for="f in daily.forecast" :key="f.date" class="daily-forecast-row">
            <span class="daily-account-name">{{ formatDate(f.date) }}</span>
            <span>应收 <MoneyText :value="f.arDue" /></span>
            <span>应付 <MoneyText :value="f.apDue" /></span>
            <span>净额 <MoneyText :value="f.netDue" /></span>
          </div>
        </div>
      </template>
    </PageCard>

    <!-- 新建/编辑弹窗 -->
    <n-modal v-model:show="showForm" preset="card" :title="formTitle" style="width: 520px; max-width: 94vw">
      <n-form label-placement="top">
        <n-form-item label="开户行">
          <n-input v-model:value="form.bankName" maxlength="100" placeholder="开户行名称" />
        </n-form-item>
        <n-form-item label="银行账号">
          <n-input v-model:value="form.accountNo" :disabled="!!editingId" placeholder="仅创建时生效，更新忽略" />
        </n-form-item>
        <n-form-item label="账号掩码">
          <n-input v-model:value="form.accountNoMasked" maxlength="20" placeholder="如 ****1234（仅展示，不落库）" />
        </n-form-item>
        <FormGrid>
          <n-form-item label="账户类型">
            <n-select v-model:value="form.accountType" :options="TYPE_OPTIONS" />
          </n-form-item>
          <n-form-item label="币种">
            <n-input v-model:value="form.currency" maxlength="3" placeholder="CNY" />
          </n-form-item>
          <n-form-item label="状态">
            <n-select v-model:value="form.status" :options="STATUS_OPTIONS" />
          </n-form-item>
        </FormGrid>
        <n-form-item label="启用">
          <n-switch v-model:value="form.isActive" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="showForm = false">取消</n-button>
        <n-button v-if="canWrite" type="primary" :loading="saving" @click="save">保存</n-button>
      </template>
    </n-modal>

    <!-- 详情弹窗，解密账号 -->
    <n-modal v-model:show="showDetail" preset="card" title="账户详情" style="width: 480px; max-width: 94vw" :loading="detailLoading">
      <n-descriptions v-if="detail" :column="1" label-placement="left" bordered size="small">
        <n-descriptions-item label="开户行">{{ detail.bankName }}</n-descriptions-item>
        <n-descriptions-item label="银行账号">{{ detail.accountNo ?? '—' }}</n-descriptions-item>
        <n-descriptions-item label="账号掩码">{{ detail.accountNoMasked ?? '—' }}</n-descriptions-item>
        <n-descriptions-item label="账户类型">{{ detail.accountType === 'CORPORATE' ? '对公' : '个人' }}</n-descriptions-item>
        <n-descriptions-item label="币种">{{ detail.currency }}</n-descriptions-item>
        <n-descriptions-item label="状态">{{ detail.status }}</n-descriptions-item>
        <n-descriptions-item label="启用">{{ detail.isActive ? '是' : '否' }}</n-descriptions-item>
        <n-descriptions-item label="创建时间">{{ formatDateTime(detail.createdAt) }}</n-descriptions-item>
      </n-descriptions>
    </n-modal>
  </div>
</template>

<style scoped>
.bank-account-list {
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
.daily-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.daily-stat {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.daily-stat-label {
  color: var(--fms-text-muted);
}
.daily-section {
  margin-top: 14px;
}
.daily-section-title {
  font-weight: 600;
  margin-bottom: 8px;
}
.daily-account-row,
.daily-forecast-row {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 13px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--n-divider-color, #eee);
}
html.dark .daily-account-row,
html.dark .daily-forecast-row {
  border-bottom-color: rgba(255, 255, 255, 0.09);
}
.daily-account-name {
  min-width: 160px;
}
/* 手机，日报统计纵向堆叠 */
@media (max-width: 767px) {
  .daily-stats {
    flex-direction: column;
    gap: 10px;
  }
  .daily-account-row,
  .daily-forecast-row {
    flex-wrap: wrap;
    gap: 8px 16px;
  }
}
</style>
