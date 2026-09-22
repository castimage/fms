<script setup lang="ts">
/**
 * 报销申请列表（/expense/reimbursements），状态/关键词/只看我提交筛选 + 服务端分页，
 * 我的列表走 my-reimbursements 端点，rls is_self 兜底，DRAFT/REJECTED 行可编辑/删除/提交，
 * REJECTED 退回后申请人可修改再提交，行点击进详情页。
 */
import { h, onMounted, ref, watch } from 'vue'
import { NButton, NInput, NPopconfirm, NSelect, NSwitch, useMessage } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import { useRouter } from 'vue-router'
import { expenseApi } from '@/api/expense'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { ReimbursementStatus, ReimbursementView } from '@/types/expense'
import { formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const router = useRouter()
/** 当前会话是否业务写角色，viewer/auditor/report 只读，写按钮统一 v-if="canWrite" */
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '草稿', value: 'DRAFT' },
  { label: '待审批', value: 'SUBMITTED' },
  { label: '已审批', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已付款', value: 'PAID' },
  { label: '已过账', value: 'POSTED' },
  { label: '已冲销', value: 'REVERSED' },
]

const status = ref<ReimbursementStatus | ''>('')
const keyword = ref('')
const myOnly = ref(false)
const page = ref(1)
const size = ref(20)
const rows = ref<ReimbursementView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const q = {
      page: page.value,
      size: size.value,
      status: status.value || undefined,
      keyword: keyword.value || undefined,
    }
    const res = myOnly.value ? await expenseApi.pageMyReimbursements(q) : await expenseApi.pageReimbursements(q)
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

/** 筛选变化重置到第一页并重查，page 未变时直接 load，避免 watch 漏触发 */
function onFilterChange() {
  if (page.value === 1) void load()
  else page.value = 1
}
const onKeywordSearch = useDebounceFn(onFilterChange, 500)
watch(keyword, () => onKeywordSearch())
watch([status, myOnly], onFilterChange)
watch([page, size], () => void load())
onMounted(() => void load())

function goDetail(id: string) {
  void router.push(`/expense/reimbursements/${id}`)
}
function goEdit(id: string) {
  void router.push(`/expense/reimbursements/${id}?edit=1`)
}
function goNew() {
  void router.push('/expense/reimbursements/new')
}
async function onSubmit(id: string) {
  await expenseApi.submitReimbursement(id)
  message.success('已提交审批')
  await load()
}
async function onDelete(id: string) {
  await expenseApi.deleteReimbursement(id)
  message.success('已删除')
  await load()
}

/** 可编辑态，草稿/被驳回退回 */
function editable(r: ReimbursementView): boolean {
  return r.status === 'DRAFT' || r.status === 'REJECTED'
}

const columns: FmsColumn<ReimbursementView>[] = [
  { key: 'title', title: '标题', width: 180 },
  { key: 'applicantName', title: '申请人', width: 90, hideBelow: 'sm' },
  { key: 'deptName', title: '部门', width: 100, hideBelow: 'sm' },
  {
    key: 'amount',
    title: '报销金额',
    width: 130,
    align: 'right',
    render: (r) => h(MoneyText, { value: r.amount, symbol: r.currency === 'CNY' ? '¥' : '' }),
  },
  { key: 'status', title: '状态', width: 90, render: (r) => h(StatusTag, { status: r.status }) },
  { key: 'createdAt', title: '提交时间', width: 150, hideBelow: 'md', render: (r) => formatDateTime(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 160,
    align: 'center',
    render: (r) => {
      const nodes = [h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => goDetail(r.id) }, { default: () => '查看' })]
      // 编辑/提交/删除仅业务写角色可见，render 内取 canWrite.value
      if (editable(r) && canWrite.value) {
        nodes.push(
          h(NButton, { text: true, size: 'small', onClick: () => goEdit(r.id) }, { default: () => '编辑' }),
          h(NButton, { text: true, size: 'small', onClick: () => onSubmit(r.id) }, { default: () => '提交' }),
          h(NPopconfirm, { onPositiveClick: () => onDelete(r.id) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
            default: () => '确认删除该报销单？',
          }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px;flex-wrap:wrap' }, nodes)
    },
  },
]
</script>

<template>
  <div class="expense-list">
    <!-- 筛选区，FormGrid 手机塌缩单列 -->
    <FormGrid class="filter-grid">
      <n-form-item label="状态">
        <n-select v-model:value="status" :options="STATUS_OPTIONS" style="width: 100%" />
      </n-form-item>
      <n-form-item label="关键词">
        <n-input v-model:value="keyword" placeholder="标题" clearable />
      </n-form-item>
      <n-form-item label="只看我提交的">
        <n-switch v-model:value="myOnly" />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">报销申请</span>
      <n-button v-if="canWrite" type="primary" size="small" @click="goNew">新建报销单</n-button>
    </div>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="columns"
      :data="rows"
      :loading="loading"
      :total="total"
      :scroll-x="1000"
      :on-row-click="goDetail"
    >
      <template #empty>
        <span>暂无报销单数据</span>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.expense-list {
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
