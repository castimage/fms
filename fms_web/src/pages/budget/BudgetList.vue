<script setup lang="ts">
/**
 * 预算编制，计划 + 调整单两页签，计划 CRUD + 提交/审批状态机，仅 DRAFT 可改删、SUBMITTED 可审批，
 * 调整单新建，正=追加 负=调减，审批/驳回，期间绑定 appStore.period，金额>0 前端预检，后端 CHECK 兜底。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NDatePicker,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NTabPane,
  NTabs,
  NTag,
  useMessage,
} from 'naive-ui'
import type { TagProps } from 'naive-ui'
import dayjs from 'dayjs'
import { budgetApi } from '@/api/budget'
import { glApi } from '@/api/gl'
import * as orgApi from '@/api/org'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { AccountView } from '@/types/gl'
import type { DepartmentView } from '@/types/org'
import type { BudgetAdjustmentStatus, BudgetAdjustmentView, BudgetPlanStatus, BudgetPlanView } from '@/types/budget'
import { formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'

const message = useMessage()
const appStore = useAppStore()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const activeTab = ref<'plans' | 'adjusts'>('plans')
const planStatus = ref<BudgetPlanStatus | ''>('')
const adjStatus = ref<BudgetAdjustmentStatus | ''>('')
const page = ref(1)
const size = ref(20)
const planRows = ref<BudgetPlanView[]>([])
const adjRows = ref<BudgetAdjustmentView[]>([])
const total = ref(0)
const loading = ref(false)

/**
 * 预算编制与预算调整共用同一张表，两 Tab 行类型不同，直接写三目表达式只会推到第一个分支的类型，
 * 故先按联合类型收口再交给 DataTable，其行类型由 data 推导。
 */
type BudgetRow = BudgetPlanView | BudgetAdjustmentView
const tableRows = computed<BudgetRow[]>(() => (activeTab.value === 'plans' ? planRows.value : adjRows.value))

const PLAN_STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '已提交', value: 'SUBMITTED' },
  { label: '已审批', value: 'APPROVED' },
]
const ADJ_STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '已审批', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
]

/** 期间，绑定 appStore.period，月选择器时间戳与 YYYY-MM 互转 */
const periodTs = computed(() => (appStore.period ? dayjs(appStore.period + '-01').valueOf() : null))
function onPeriodChange(ts: number | null) {
  appStore.setPeriod(ts ? dayjs(ts).format('YYYY-MM') : dayjs().format('YYYY-MM'))
}

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0
async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    if (activeTab.value === 'plans') {
      const res = await budgetApi.pagePlans({
        page: page.value,
        size: size.value,
        companyId: appStore.companyId ?? undefined,
        period: appStore.period,
        status: planStatus.value || undefined,
      })
      if (seq !== reqSeq) return
      planRows.value = res.items
      total.value = res.total
    } else {
      const res = await budgetApi.pageAdjustments({
        page: page.value,
        size: size.value,
        companyId: appStore.companyId ?? undefined,
        status: adjStatus.value || undefined,
      })
      if (seq !== reqSeq) return
      adjRows.value = res.items
      total.value = res.total
    }
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

function onFilterChange() {
  page.value = 1
}
watch([planStatus, adjStatus, activeTab], onFilterChange)
watch([page, size, activeTab, planStatus, adjStatus, () => appStore.period], () => void load())
watch(
  () => appStore.companyId,
  () => {
    page.value = 1
    void load()
    void loadOptions()
    void loadPlanOptions()
  },
)

interface PlanForm {
  period: string
  accountId: string | null
  deptId: string | null
  amount: number | null
}
const showPlanModal = ref(false)
const editingPlan = ref<BudgetPlanView | null>(null)
const planForm = ref<PlanForm>({ period: appStore.period, accountId: null, deptId: null, amount: null })
const saving = ref(false)

const planPeriodTs = computed(() => (planForm.value.period ? dayjs(planForm.value.period + '-01').valueOf() : null))
function onPlanPeriodChange(ts: number | null) {
  planForm.value.period = ts ? dayjs(ts).format('YYYY-MM') : ''
}

/** 科目/部门下拉选项，公司切换时重载 */
const accounts = ref<AccountView[]>([])
const depts = ref<DepartmentView[]>([])
const accountOptions = computed(() => accounts.value.map((a) => ({ label: `${a.code} ${a.name}`, value: a.id })))
const deptOptions = computed(() => depts.value.map((d) => ({ label: d.name, value: d.id })))
async function loadOptions() {
  const [acc, dep] = await Promise.all([
    glApi.listAccounts(appStore.companyId ?? undefined),
    orgApi.pageDepartments({ page: 1, size: await refdataPageSize(), companyId: appStore.companyId ?? undefined }),
  ])
  accounts.value = acc ?? []
  depts.value = dep.items
}

function openPlanCreate() {
  editingPlan.value = null
  planForm.value = { period: appStore.period, accountId: null, deptId: null, amount: null }
  showPlanModal.value = true
}
function openPlanEdit(row: BudgetPlanView) {
  editingPlan.value = row
  planForm.value = { period: row.period, accountId: row.accountId, deptId: row.deptId, amount: row.amount }
  showPlanModal.value = true
}

async function savePlan() {
  if (!appStore.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  const f = planForm.value
  if (!f.period || !f.accountId || !f.deptId || !f.amount || f.amount <= 0) {
    message.warning('请完整填写：期间、科目、部门、金额（> 0）')
    return
  }
  saving.value = true
  try {
    const req = {
      companyId: appStore.companyId,
      period: f.period,
      accountId: f.accountId,
      deptId: f.deptId,
      amount: f.amount,
    }
    if (editingPlan.value) await budgetApi.updatePlan(editingPlan.value.id, req)
    else await budgetApi.createPlan(req)
    message.success('已保存')
    showPlanModal.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onSubmitPlan(id: string) {
  await budgetApi.submitPlan(id)
  message.success('已提交审批')
  await load()
}
async function onApprovePlan(id: string) {
  await budgetApi.approvePlan(id)
  message.success('已审批通过')
  await load()
}
async function onDeletePlan(id: string) {
  await budgetApi.deletePlan(id)
  await load()
}

interface AdjForm {
  planId: string | null
  amount: number | null
  reason: string
}
const showAdjModal = ref(false)
const adjForm = ref<AdjForm>({ planId: null, amount: null, reason: '' })

/** 调整单关联计划下拉，独立拉取，不受当前列表筛选影响 */
const planOptions = ref<{ label: string; value: string }[]>([])
async function loadPlanOptions() {
  const res = await budgetApi.pagePlans({ page: 1, size: await refdataPageSize(), companyId: appStore.companyId ?? undefined })
  planOptions.value = res.items.map((p) => ({
    label: `${p.period} ${p.accountCode} ${p.accountName}（${p.deptName}）`,
    value: p.id,
  }))
}

function openAdjCreate() {
  adjForm.value = { planId: null, amount: null, reason: '' }
  showAdjModal.value = true
}

async function saveAdjustment() {
  if (!appStore.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  const f = adjForm.value
  if (!f.planId) {
    message.warning('请选择预算计划')
    return
  }
  if (!f.amount || f.amount === 0) {
    message.warning('调整金额不可为 0（正=追加 负=调减）')
    return
  }
  if (!f.reason.trim()) {
    message.warning('请填写调整原因')
    return
  }
  saving.value = true
  try {
    await budgetApi.createAdjustment({ planId: f.planId, amount: f.amount, reason: f.reason.trim() })
    message.success('已创建调整单')
    showAdjModal.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onApproveAdj(id: string) {
  await budgetApi.approveAdjustment(id)
  message.success('已审批通过')
  await load()
}
async function onRejectAdj(id: string) {
  await budgetApi.rejectAdjustment(id)
  message.success('已驳回')
  await load()
}
async function onDeleteAdj(id: string) {
  await budgetApi.deleteAdjustment(id)
  await load()
}

const ADJ_TAG: Record<BudgetAdjustmentStatus, { label: string; type: TagProps['type'] }> = {
  DRAFT: { label: '草稿', type: 'default' },
  APPROVED: { label: '已审批', type: 'success' },
  REJECTED: { label: '已驳回', type: 'error' },
}

const planColumns: FmsColumn<BudgetPlanView>[] = [
  { key: 'period', title: '期间', width: 90 },
  {
    key: 'account',
    title: '预算科目',
    width: 180,
    render: (r) => `${r.accountCode} ${r.accountName}`,
  },
  { key: 'deptName', title: '部门', width: 120, hideBelow: 'sm' },
  {
    key: 'amount',
    title: '预算金额',
    width: 130,
    align: 'right',
    render: (r) => h(MoneyText, { value: r.amount }),
  },
  { key: 'version', title: '版本', width: 70, hideBelow: 'sm', render: (r) => String(r.version) },
  { key: 'status', title: '状态', width: 90, render: (r) => h(StatusTag, { status: r.status }) },
  {
    key: 'actions',
    title: '操作',
    width: 190,
    align: 'center',
    render: (r) => {
      // 状态机，DRAFT 可编辑/提交/删除，SUBMITTED 可审批，已审批只读，仅写角色可见
      const nodes: ReturnType<typeof h>[] = []
      if (canWrite.value && r.status === 'DRAFT') {
        nodes.push(
          h(NButton, { text: true, size: 'small', onClick: () => openPlanEdit(r) }, { default: () => '编辑' }),
          h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => onSubmitPlan(r.id) }, { default: () => '提交' }),
        )
      } else if (canWrite.value && r.status === 'SUBMITTED') {
        nodes.push(h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => onApprovePlan(r.id) }, { default: () => '审批' }))
      }
      if (canWrite.value && r.status === 'DRAFT') {
        nodes.push(
          h(NPopconfirm, { onPositiveClick: () => onDeletePlan(r.id) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
            default: () => '确认删除该草稿计划？',
          }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px' }, nodes)
    },
  },
]

const adjColumns: FmsColumn<BudgetAdjustmentView>[] = [
  { key: 'period', title: '期间', width: 90 },
  {
    key: 'amount',
    title: '调整金额',
    width: 130,
    align: 'right',
    render: (r) => h(MoneyText, { value: r.amount }),
  },
  { key: 'reason', title: '原因', width: 220, hideBelow: 'sm' },
  {
    key: 'status',
    title: '状态',
    width: 90,
    render: (r) => {
      const t = ADJ_TAG[r.status]
      return h(NTag, { type: t.type, size: 'small', bordered: false }, { default: () => t.label })
    },
  },
  { key: 'createdAt', title: '创建时间', width: 150, hideBelow: 'md', render: (r) => formatDateTime(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 190,
    align: 'center',
    render: (r) => {
      // 调整单仅 DRAFT 可审批/驳回/删除，且仅写角色可见
      if (!canWrite.value || r.status !== 'DRAFT') return h('span')
      return h('span', { style: 'display:inline-flex;gap:8px' }, [
        h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => onApproveAdj(r.id) }, { default: () => '审批' }),
        h(NPopconfirm, { onPositiveClick: () => onRejectAdj(r.id) }, {
          trigger: () => h(NButton, { text: true, type: 'warning', size: 'small' }, { default: () => '驳回' }),
          default: () => '确认驳回该调整单？',
        }),
        h(NPopconfirm, { onPositiveClick: () => onDeleteAdj(r.id) }, {
          trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
          default: () => '确认删除该草稿调整单？',
        }),
      ])
    },
  },
]

onMounted(() => {
  void load()
  void loadOptions()
  void loadPlanOptions()
})
</script>

<template>
  <div class="budget-list">
    <n-tabs v-model:value="activeTab" type="line">
      <n-tab-pane name="plans" tab="预算计划" />
      <n-tab-pane name="adjusts" tab="调整单" />
    </n-tabs>

    <!-- 筛选区，计划页含期间，调整单仅状态 -->
    <FormGrid class="filter-grid">
      <template v-if="activeTab === 'plans'">
        <n-form-item label="期间">
          <n-date-picker type="month" :value="periodTs" :clearable="false" style="width: 100%" @update:value="onPeriodChange" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="planStatus" :options="PLAN_STATUS_OPTIONS" style="width: 100%" />
        </n-form-item>
      </template>
      <n-form-item v-else label="状态">
        <n-select v-model:value="adjStatus" :options="ADJ_STATUS_OPTIONS" style="width: 100%" />
      </n-form-item>
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">{{ activeTab === 'plans' ? '预算编制' : '预算调整' }}</span>
      <!-- 新建入口，仅写角色（admin/operator）可见 -->
      <template v-if="canWrite">
        <n-button v-if="activeTab === 'plans'" type="primary" size="small" @click="openPlanCreate">新建计划</n-button>
        <n-button v-else type="primary" size="small" @click="openAdjCreate">新建调整单</n-button>
      </template>
    </div>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="activeTab === 'plans' ? planColumns : adjColumns"
      :data="tableRows"
      :loading="loading"
      :total="total"
      :scroll-x="1000"
    >
      <template #empty>
        <span>暂无数据</span>
      </template>
    </DataTable>

    <!-- 计划新建/编辑弹窗 -->
    <n-modal v-model:show="showPlanModal" preset="card" :title="editingPlan ? '编辑预算计划' : '新建预算计划'" style="max-width: 560px">
      <FormGrid :cols="{ xs: 1, md: 2, lg: 2 }">
        <n-form-item label="公司">
          <n-input :value="appStore.companyName || '默认公司'" readonly />
        </n-form-item>
        <n-form-item label="期间">
          <n-date-picker type="month" :value="planPeriodTs" :clearable="false" style="width: 100%" @update:value="onPlanPeriodChange" />
        </n-form-item>
        <n-form-item label="预算科目">
          <n-select v-model:value="planForm.accountId" :options="accountOptions" filterable placeholder="选择科目" style="width: 100%" />
        </n-form-item>
        <n-form-item label="预算部门">
          <n-select v-model:value="planForm.deptId" :options="deptOptions" filterable placeholder="选择部门" style="width: 100%" />
        </n-form-item>
        <n-form-item label="预算金额">
          <n-input-number v-model:value="planForm.amount" :min="0.01" :precision="2" placeholder="> 0" style="width: 100%" />
        </n-form-item>
      </FormGrid>
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <n-button size="small" @click="showPlanModal = false">取消</n-button>
          <n-button v-if="canWrite" size="small" type="primary" :loading="saving" @click="savePlan">保存</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 调整单新建弹窗 -->
    <n-modal v-model:show="showAdjModal" preset="card" title="新建调整单" style="max-width: 560px">
      <FormGrid :cols="{ xs: 1, md: 2, lg: 2 }">
        <n-form-item label="预算计划" class="span-2">
          <n-select v-model:value="adjForm.planId" :options="planOptions" filterable placeholder="选择预算计划" style="width: 100%" />
        </n-form-item>
        <n-form-item label="调整金额">
          <n-input-number v-model:value="adjForm.amount" :precision="2" placeholder="正=追加 负=调减" style="width: 100%" />
        </n-form-item>
        <n-form-item label="调整原因">
          <n-input v-model:value="adjForm.reason" placeholder="调整原因（必填）" />
        </n-form-item>
      </FormGrid>
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <n-button size="small" @click="showAdjModal = false">取消</n-button>
          <n-button v-if="canWrite" size="small" type="primary" :loading="saving" @click="saveAdjustment">保存</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.budget-list {
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
/* 弹窗内跨两列的表单项，窄屏自动单列 */
.span-2 {
  grid-column: span 2;
}
@media (max-width: 767px) {
  .span-2 {
    grid-column: span 1;
  }
}
</style>
