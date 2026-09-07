<script setup lang="ts">
/**
 * 凭证列表，期间/状态/关键词/只看我创建筛选，服务端分页，状态标签、断号提示
 * GET /api/gl/voucher-gaps 后端权威结果，失败/空时回退页内推断，DRAFT 行可编辑/删除，行点击进详情。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NDatePicker, NInput, NPopconfirm, NSelect, NSwitch } from 'naive-ui'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { useDebounceFn } from '@vueuse/core'
import { glApi } from '@/api/gl'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { VoucherGapView, VoucherListView } from '@/types/gl'
import type { VoucherStatus } from '@/types/gl'
import { formatAmount, formatDate } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const router = useRouter()
const appStore = useAppStore()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '已提交', value: 'SUBMITTED' },
  { label: '已审批', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已过账', value: 'POSTED' },
  { label: '已冲销', value: 'REVERSED' },
]

// 筛选状态
const status = ref<VoucherStatus | ''>('')
const keyword = ref('')
const createdByMe = ref(false)
const page = ref(1)
const size = ref(20)
const rows = ref<VoucherListView[]>([])
const total = ref(0)
const loading = ref(false)

/** 期间，appStore.period（YYYY-MM）转月选择器时间戳 */
const periodTs = computed(() => (appStore.period ? dayjs(appStore.period + '-01').valueOf() : null))
function onPeriodChange(ts: number | null) {
  appStore.setPeriod(ts ? dayjs(ts).format('YYYY-MM') : dayjs().format('YYYY-MM'))
}

/** 筛选变化重置到第一页并重查，page 未变时直接 load，防 watch 漏触发 */
function onFilterChange() {
  if (page.value === 1) void load()
  else page.value = 1
}

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

/** 服务端分页查询 */
async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await glApi.pageVouchers({
      page: page.value,
      size: size.value,
      period: appStore.period,
      status: status.value || undefined,
      companyId: appStore.companyId ?? undefined,
      keyword: keyword.value || undefined,
      createdByMe: createdByMe.value || undefined,
    })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
    await loadGaps()
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

/**
 * 断号，优先后端 /gl/voucher-gaps，与 cron 断号检查同口径，仅 POSTED/REVERSED 已取号凭证，
 * rls 收敛可见集合，空数组 = 无断号，失败或空时回退页内推断，当前页可见凭证号缺口，非权威。
 * 无公司上下文，未选公司时跳过后端查询，直接走推断。
 */
const serverGaps = ref<VoucherGapView[] | null>(null)

async function loadGaps() {
  if (!appStore.companyId) {
    serverGaps.value = null
    return
  }
  try {
    serverGaps.value = await glApi.voucherGaps(appStore.companyId, appStore.period)
  } catch {
    // 接口失败不阻断列表，回退页内推断
    serverGaps.value = null
  }
}

/** 断号提示，后端权威结果优先，gapFrom~gapTo 展开，为空/失败则回退页内推断 */
const gapAlert = computed(() => {
  if (serverGaps.value && serverGaps.value.length > 0) {
    const list: number[] = []
    for (const g of serverGaps.value) {
      for (let n = g.gapFrom; n <= g.gapTo; n += 1) list.push(n)
    }
    return list.slice(0, 5)
  }
  const nos = rows.value
    .filter((r) => r.voucherNo !== null)
    .map((r) => r.voucherNo as number)
    .sort((a, b) => a - b)
  if (nos.length < 2) return []
  const gaps: number[] = []
  for (let i = 1; i < nos.length; i += 1) {
    if (nos[i] !== nos[i - 1] + 1) gaps.push(nos[i - 1] + 1)
  }
  return gaps.slice(0, 5)
})

/** 断号提示来源，后端权威 / 页内推断兜底，用于文案区分 */
const gapSource = computed<'backend' | 'infer'>(() =>
  !!serverGaps.value && serverGaps.value.length > 0 ? 'backend' : 'infer',
)

/** 关键词防抖（500ms）后重查 */
const onKeywordSearch = useDebounceFn(() => {
  onFilterChange()
}, 500)
watch(keyword, () => onKeywordSearch())

watch([status, createdByMe], onFilterChange)
watch([page, size, () => appStore.period, () => appStore.companyId], () => void load())

function goDetail(id: string) {
  void router.push(`/gl/vouchers/${id}`)
}
function goNew() {
  void router.push('/gl/vouchers/new')
}
function goEdit(id: string) {
  void router.push(`/gl/vouchers/${id}/edit`)
}
async function onDelete(id: string) {
  await glApi.deleteVoucher(id)
  await load()
}

/** 金额单元格，右对齐 + 千分位 */
function renderAmount(v: number) {
  return h('span', { class: 'num' }, formatAmount(v))
}

const columns: FmsColumn<VoucherListView>[] = [
  {
    key: 'voucherNo',
    title: '凭证号',
    width: 90,
    render: (r) => (r.voucherNo === null ? '草稿未编号' : String(r.voucherNo)),
  },
  { key: 'voucherDate', title: '凭证日期', width: 110, render: (r) => formatDate(r.voucherDate) },
  { key: 'period', title: '期间', width: 90, hideBelow: 'md' },
  {
    key: 'note',
    title: '摘要',
    width: 160,
    hideBelow: 'sm',
    render: (r) => (r.note ? (r.note.length > 20 ? `${r.note.slice(0, 20)}…` : r.note) : '—'),
  },
  { key: 'status', title: '状态', width: 90, render: (r) => h(StatusTag, { status: r.status }) },
  { key: 'totalDebit', title: '借方合计', width: 120, align: 'right', hideBelow: 'md', render: (r) => renderAmount(r.totalDebit) },
  { key: 'totalCredit', title: '贷方合计', width: 120, align: 'right', hideBelow: 'md', render: (r) => renderAmount(r.totalCredit) },
  { key: 'createdByName', title: '制单人', width: 100, hideBelow: 'sm' },
  {
    key: 'actions',
    title: '操作',
    width: 150,
    align: 'center',
    render: (r) => {
      const nodes = [h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => goDetail(r.id) }, { default: () => '查看' })]
      if (r.status === 'DRAFT' && canWrite.value) {
        nodes.push(
          h(NButton, { text: true, size: 'small', onClick: () => goEdit(r.id) }, { default: () => '编辑' }),
          h(NPopconfirm, { onPositiveClick: () => onDelete(r.id) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
            default: () => '确认删除该草稿凭证？',
          }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px' }, nodes)
    },
  },
]

onMounted(() => void load())
</script>

<template>
  <div class="voucher-list">
    <!-- 筛选区 -->
    <FormGrid class="filter-grid">
      <n-form-item label="期间">
        <n-date-picker
          type="month"
          :value="periodTs"
          :clearable="false"
          style="width: 100%"
          @update:value="onPeriodChange"
        />
      </n-form-item>
      <n-form-item label="状态">
        <n-select v-model:value="status" :options="STATUS_OPTIONS" style="width: 100%" />
      </n-form-item>
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
      <n-form-item label="关键词">
        <n-input v-model:value="keyword" placeholder="备注 / 摘要" clearable />
      </n-form-item>
      <n-form-item label="只看我创建的">
        <n-switch v-model:value="createdByMe" />
      </n-form-item>
    </FormGrid>

    <!-- 工具栏，断号提示，后端 /gl/voucher-gaps 权威结果，失败/空时页内推断兜底 + 新建 -->
    <n-alert v-if="gapAlert.length" type="warning" :bordered="false" class="gap-alert">
      检测到凭证号断号（{{ gapSource === 'backend' ? '后端断号检测，与 cron 检查同口径' : '页内推断，仅供参考' }}）：
      {{ gapAlert.join('、') }}
    </n-alert>
    <div class="toolbar">
      <span class="toolbar-title">凭证列表</span>
      <n-button v-if="canWrite" type="primary" size="small" @click="goNew">新建凭证</n-button>
    </div>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="columns"
      :data="rows"
      :loading="loading"
      :total="total"
      :scroll-x="1000"
      :on-row-click="(row: VoucherListView) => goDetail(row.id)"
    >
      <template #empty>
        <span>暂无凭证数据</span>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.voucher-list {
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
.gap-alert {
  margin-bottom: 0;
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
