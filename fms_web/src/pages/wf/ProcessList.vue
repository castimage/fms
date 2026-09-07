<script setup lang="ts">
/**
 * 流程实例，我发起的 / 全部两页签，状态/业务类型过滤 + 详情弹窗，
 * 他人 RUNNING 实例经 rls 收敛，越权详情返回 data:null，不泄露存在性。
 */
import { h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NDescriptions,
  NDescriptionsItem,
  NInput,
  NModal,
  NSelect,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  useMessage,
} from 'naive-ui'
import type { TagProps } from 'naive-ui'
import { wfApi } from '@/api/wf'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { WfInstanceStatus, WfInstanceView } from '@/types/wf'
import { formatDateTime } from '@/utils/format'

const message = useMessage()
const appStore = useAppStore()

// 页签与筛选状态
const scope = ref<'my' | 'all'>('my')
const status = ref<WfInstanceStatus | ''>('')
const businessType = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<WfInstanceView[]>([])
const total = ref(0)
const loading = ref(false)

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '进行中', value: 'RUNNING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
]

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0
async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const q = { page: page.value, size: size.value, status: status.value || undefined }
    // 全部页签带公司/业务类型过滤，我发起页签走 rls 专用端点
    const res =
      scope.value === 'my'
        ? await wfApi.myInstances(q)
        : await wfApi.pageInstances({
            ...q,
            companyId: appStore.companyId ?? undefined,
            businessType: businessType.value || undefined,
          })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

function onFilterChange() {
  page.value = 1
}
watch([scope, status, businessType], onFilterChange)
watch([page, size, scope, status, businessType, () => appStore.companyId], () => void load())

const INST_TAG: Record<WfInstanceStatus, { label: string; type: TagProps['type'] }> = {
  RUNNING: { label: '进行中', type: 'info' },
  APPROVED: { label: '已通过', type: 'success' },
  REJECTED: { label: '已驳回', type: 'error' },
}

// 详情弹窗，他人 RUNNING 实例 data:null 时不提示具体原因
const showDetail = ref(false)
const detail = ref<WfInstanceView | null>(null)
const detailLoading = ref(false)

async function openDetail(row: WfInstanceView) {
  showDetail.value = true
  detailLoading.value = true
  try {
    const d = await wfApi.getInstance(row.id)
    if (!d) {
      message.error('无权查看该实例或实例不存在')
      showDetail.value = false
      return
    }
    detail.value = d
  } finally {
    detailLoading.value = false
  }
}

const columns: FmsColumn<WfInstanceView>[] = [
  { key: 'definitionCode', title: '流程编码', width: 140, hideBelow: 'md' },
  { key: 'definitionName', title: '流程名称', width: 150 },
  { key: 'businessType', title: '业务类型', width: 100, hideBelow: 'sm', render: (r) => r.businessType ?? '—' },
  { key: 'businessId', title: '业务单号', width: 110, hideBelow: 'md', render: (r) => r.businessId ?? '—' },
  { key: 'initiatorName', title: '发起人', width: 100, hideBelow: 'sm' },
  { key: 'currentNode', title: '当前节点', width: 110, render: (r) => r.currentNode ?? '—' },
  {
    key: 'status',
    title: '状态',
    width: 90,
    render: (r) => {
      const t = INST_TAG[r.status]
      return h(NTag, { type: t.type, size: 'small', bordered: false }, { default: () => t.label })
    },
  },
  { key: 'createdAt', title: '创建时间', width: 150, hideBelow: 'sm', render: (r) => formatDateTime(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 80,
    align: 'center',
    render: (r) => h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openDetail(r) }, { default: () => '详情' }),
  },
]

onMounted(() => void load())
</script>

<template>
  <div class="process-list">
    <n-tabs v-model:value="scope" type="line">
      <n-tab-pane name="my" tab="我发起的" />
      <n-tab-pane name="all" tab="全部实例" />
    </n-tabs>

    <!-- 筛选区，全部页签多业务类型过滤 -->
    <FormGrid class="filter-grid">
      <n-form-item label="状态">
        <n-select v-model:value="status" :options="STATUS_OPTIONS" style="width: 100%" />
      </n-form-item>
      <n-form-item v-if="scope === 'all'" label="业务类型">
        <n-input v-model:value="businessType" placeholder="如 REIMBURSEMENT" clearable />
      </n-form-item>
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
    </FormGrid>

    <!-- 工具栏 -->
    <div class="toolbar">
      <span class="toolbar-title">{{ scope === 'my' ? '我发起的流程' : '全部流程实例' }}</span>
      <n-button size="small" @click="load">刷新</n-button>
    </div>

    <DataTable v-model:page="page" v-model:size="size" :columns="columns" :data="rows" :loading="loading" :total="total" :scroll-x="1050">
      <template #empty>
        <span>暂无流程实例</span>
      </template>
    </DataTable>

    <!-- 实例详情弹窗 -->
    <n-modal v-model:show="showDetail" preset="card" title="实例详情" style="max-width: 560px">
      <n-spin :show="detailLoading">
        <n-descriptions v-if="detail" :column="2" label-placement="left" size="small" bordered>
          <n-descriptions-item label="流程编码">{{ detail.definitionCode }}</n-descriptions-item>
          <n-descriptions-item label="流程名称">{{ detail.definitionName }}</n-descriptions-item>
          <n-descriptions-item label="业务类型">{{ detail.businessType ?? '—' }}</n-descriptions-item>
          <n-descriptions-item label="业务单号">{{ detail.businessId ?? '—' }}</n-descriptions-item>
          <n-descriptions-item label="发起人">{{ detail.initiatorName }}</n-descriptions-item>
          <n-descriptions-item label="当前节点">{{ detail.currentNode ?? '—' }}</n-descriptions-item>
          <n-descriptions-item label="状态">
            <n-tag size="small" :bordered="false">{{ detail.status }}</n-tag>
          </n-descriptions-item>
          <n-descriptions-item label="创建时间">{{ formatDateTime(detail.createdAt) }}</n-descriptions-item>
        </n-descriptions>
      </n-spin>
    </n-modal>
  </div>
</template>

<style scoped>
.process-list {
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
