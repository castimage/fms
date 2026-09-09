<script setup lang="ts">
/**
 * 流程实例，我发起的 / 全部两页签，状态/业务类型过滤 + 详情弹窗（流程图展示进度），
 * 写角色可测试发起 DEMO 实例验证编排，
 * 他人 RUNNING 实例经 rls 收敛，越权详情返回 data:null，不泄露存在性。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
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
import { getUser, listRoles } from '@/api/org'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import WfGraph from '@/components/wf/WfGraph.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type {
  WfDefinitionView,
  WfGraphStep,
  WfInstanceStatus,
  WfInstanceView,
  WfNodeType,
} from '@/types/wf'
import { formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const appStore = useAppStore()

/** 写操作仅 admin/operator 可见，viewer/auditor/report 只读 */
const canWrite = useCanWrite()

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

// 详情弹窗，他人 RUNNING 实例 data:null 时不提示具体原因，附图展示定义链条与进度
const showDetail = ref(false)
const detail = ref<WfInstanceView | null>(null)
const detailLoading = ref(false)
const detailSteps = ref<WfGraphStep[]>([])

/** 处理人名称映射，角色编码与用户 id 各一表，缺失时退化显示编码 */
const roleNames = ref<Record<string, string>>({})
const userNames = ref<Record<string, string>>({})

async function loadRoleNames() {
  try {
    const roles = await listRoles()
    roleNames.value = Object.fromEntries(roles.map((r) => [r.code, r.name || r.code]))
  } catch {
    /* 角色名拉取失败，流程图退化显示角色编码 */
  }
}

/** node_json 解析为流程图节点，处理人来源文本化 */
function toSteps(nodeJson: string): WfGraphStep[] {
  try {
    const arr = JSON.parse(nodeJson)
    if (!Array.isArray(arr)) return []
    return arr.map((n: any) => ({
      node_code: String(n?.node_code ?? ''),
      type: (['START', 'APPROVE', 'END'].includes(n?.type) ? n.type : 'APPROVE') as WfNodeType,
      strategy: n?.strategy,
      handlerLabel: handlerText(n),
    }))
  } catch {
    return []
  }
}

/** 处理人来源文本，多来源顿号连接 */
function handlerText(n: any): string {
  const hs = Array.isArray(n?.handlers) ? n.handlers : []
  if (!hs.length) return ''
  return hs
    .map((x: any) => {
      if (x?.source === 'applicant') return '申请人'
      if (x?.source === 'user') return userNames.value[x.userId] ?? '指定用户'
      return roleNames.value[x.code] ?? String(x?.code ?? '角色')
    })
    .join('、')
}

function collectUserIds(nodeJson: string): string[] {
  try {
    const arr = JSON.parse(nodeJson)
    if (!Array.isArray(arr)) return []
    return arr.flatMap((n: any) =>
      Array.isArray(n?.handlers) ? n.handlers.map((x: any) => String(x?.userId ?? '')).filter(Boolean) : [],
    )
  } catch {
    return []
  }
}

/** 补齐流程图上的用户显示名，未缓存的 id 逐个补查 */
async function ensureUserNames(ids: string[]) {
  const missing = ids.filter((id) => id && !userNames.value[id])
  await Promise.all(
    missing.map(async (id) => {
      try {
        const u = await getUser(id)
        if (u) userNames.value[id] = u.displayName || u.loginName
      } catch {
        /* 单个用户查询失败保留占位文本 */
      }
    }),
  )
}

async function openDetail(row: WfInstanceView) {
  showDetail.value = true
  detailLoading.value = true
  detailSteps.value = []
  try {
    const d = await wfApi.getInstance(row.id)
    if (!d) {
      message.error('无权查看该实例或实例不存在')
      showDetail.value = false
      return
    }
    detail.value = d
    const def = await wfApi.getDefinition(d.definitionId)
    if (def) {
      await ensureUserNames(collectUserIds(def.nodeJson))
      detailSteps.value = toSteps(def.nodeJson)
    }
  } finally {
    detailLoading.value = false
  }
}

// 测试发起，选 ACTIVE 定义生成 DEMO 业务单，用于验证多节点编排
const showStart = ref(false)
const starting = ref(false)
const activeDefs = ref<WfDefinitionView[]>([])
const startDefId = ref<string | null>(null)
const startBusinessId = ref('')

const defOptions = computed(() => activeDefs.value.map((d) => ({ label: `${d.name}（${d.code}）`, value: d.id })))

async function openStart() {
  startDefId.value = null
  startBusinessId.value = crypto.randomUUID()
  showStart.value = true
  try {
    const res = await wfApi.pageDefinitions({
      page: 1,
      size: 100,
      companyId: appStore.companyId ?? undefined,
      status: 'ACTIVE',
    })
    activeDefs.value = res.items
  } catch {
    /* 定义列表拉取失败，弹窗内下拉为空 */
  }
}

async function confirmStart() {
  if (!startDefId.value) {
    message.warning('请选择流程定义')
    return
  }
  starting.value = true
  try {
    await wfApi.startInstance({
      definitionId: startDefId.value,
      businessType: 'DEMO',
      businessId: startBusinessId.value,
    })
    message.success('已发起，可在「我的待办」查看任务')
    showStart.value = false
    void load()
  } catch (e: any) {
    message.error(e?.message ?? '发起失败')
  } finally {
    starting.value = false
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

onMounted(() => {
  void load()
  void loadRoleNames()
})
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
      <div class="toolbar-actions">
        <n-button v-if="canWrite" size="small" type="primary" @click="openStart">测试发起</n-button>
        <n-button size="small" @click="load">刷新</n-button>
      </div>
    </div>

    <DataTable v-model:page="page" v-model:size="size" :columns="columns" :data="rows" :loading="loading" :total="total" :scroll-x="1050">
      <template #empty>
        <span>暂无流程实例</span>
      </template>
    </DataTable>

    <!-- 实例详情弹窗，附流程图展示节点链条与当前进度 -->
    <n-modal v-model:show="showDetail" preset="card" title="实例详情" style="max-width: 680px">
      <n-spin :show="detailLoading">
        <template v-if="detail">
          <n-descriptions :column="2" label-placement="left" size="small" bordered>
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
          <div class="detail-graph-title">流程进度</div>
          <WfGraph :steps="detailSteps" :current-code="detail.currentNode ?? undefined" :status="detail.status" />
        </template>
      </n-spin>
    </n-modal>

    <!-- 测试发起弹窗，选 ACTIVE 定义生成 DEMO 业务单 -->
    <n-modal v-model:show="showStart" preset="card" title="测试发起流程实例" style="max-width: 480px">
      <div class="start-form">
        <n-form-item label="流程定义">
          <n-select v-model:value="startDefId" :options="defOptions" filterable placeholder="选择已启用的流程定义" />
        </n-form-item>
        <n-form-item label="业务类型">
          <n-input value="DEMO" readonly />
        </n-form-item>
        <n-form-item label="业务单号">
          <n-input :value="startBusinessId" readonly />
        </n-form-item>
        <div class="start-tip">测试发起用于验证审批编排，业务类型记为 DEMO，不关联真实单据。</div>
      </div>
      <template #footer>
        <div class="editor-footer">
          <n-button size="small" @click="showStart = false">取消</n-button>
          <n-button size="small" type="primary" :loading="starting" @click="confirmStart">发起</n-button>
        </div>
      </template>
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
.toolbar-actions {
  display: flex;
  gap: 8px;
}
.detail-graph-title {
  margin: 12px 0 8px;
  font-size: 13px;
  color: var(--fms-text-muted, #666);
}
.start-form {
  display: flex;
  flex-direction: column;
}
.start-tip {
  font-size: 12px;
  color: var(--fms-text-muted, #666);
}
.editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
