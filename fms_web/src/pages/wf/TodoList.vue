<script setup lang="ts">
/**
 * 我的待办，当前用户 PENDING/CLAIMED 任务，按截止时间升序。
 * PENDING 可领取，SKIP LOCKED 并发仅一人成功，失败 40900 后端兜底，审批/驳回走备注弹窗，驳回原因必填。
 */
import { h, onMounted, ref, watch } from 'vue'
import { NButton, NInput, NModal, NSpin, NTag, useMessage } from 'naive-ui'
import type { TagProps } from 'naive-ui'
import { wfApi } from '@/api/wf'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { WfTaskEventView, WfTaskStatus, WfTaskView } from '@/types/wf'
import { formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()

/** 写操作仅 admin/operator 可见，viewer/auditor/report 只读 */
const canWrite = useCanWrite()

const page = ref(1)
const size = ref(20)
const rows = ref<WfTaskView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0
async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await wfApi.myTodos({ page: page.value, size: size.value })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}
watch([page, size], () => void load())

const TASK_TAG: Record<WfTaskStatus, { label: string; type: TagProps['type'] }> = {
  PENDING: { label: '待处理', type: 'warning' },
  CLAIMED: { label: '已领取', type: 'info' },
  APPROVED: { label: '已通过', type: 'success' },
  REJECTED: { label: '已驳回', type: 'error' },
  TRANSFERRED: { label: '已转办', type: 'default' },
}

/** 任务类型，会签与或签票在待办中可辨 */
const TASK_TYPE_TAG: Record<string, { label: string; type: TagProps['type'] }> = {
  APPROVE: { label: '单审', type: 'default' },
  COUNTERSIGN: { label: '会签', type: 'info' },
  OR_SIGN: { label: '或签', type: 'warning' },
}

// 领取仅限 PENDING 任务，领取与办结均委托 pg 函数原子完成
async function onClaim(task: WfTaskView) {
  await wfApi.claimTask(task.id)
  message.success('已领取，请及时处理')
  await load()
}

// 审批/驳回走备注弹窗，驳回原因必填，审批意见可选
const showAction = ref(false)
const actionTask = ref<WfTaskView | null>(null)
const actionType = ref<'approve' | 'reject' | null>(null)
const remark = ref('')
const submitting = ref(false)

function openAction(task: WfTaskView, type: 'approve' | 'reject') {
  actionTask.value = task
  actionType.value = type
  remark.value = ''
  showAction.value = true
}
function closeAction() {
  showAction.value = false
  actionTask.value = null
  actionType.value = null
}

async function confirmAction() {
  const task = actionTask.value
  if (!task || !actionType.value) return
  if (actionType.value === 'reject' && !remark.value.trim()) {
    message.warning('请填写驳回原因')
    return
  }
  submitting.value = true
  try {
    const req = { remark: remark.value.trim() || undefined }
    if (actionType.value === 'approve') await wfApi.approveTask(task.id, req)
    else await wfApi.rejectTask(task.id, req)
    message.success('操作成功')
    closeAction()
    await load()
  } finally {
    submitting.value = false
  }
}

// 任务事件流水仅追加，弹窗展示
const showEvents = ref(false)
const eventTask = ref<WfTaskView | null>(null)
const events = ref<WfTaskEventView[]>([])
const eventsLoading = ref(false)

async function openEvents(task: WfTaskView) {
  eventTask.value = task
  events.value = []
  showEvents.value = true
  eventsLoading.value = true
  try {
    events.value = await wfApi.taskEvents(task.id)
  } finally {
    eventsLoading.value = false
  }
}

const columns: FmsColumn<WfTaskView>[] = [
  { key: 'businessType', title: '业务类型', width: 100, hideBelow: 'sm', render: (r) => r.businessType ?? '—' },
  { key: 'businessId', title: '业务单号', width: 110, hideBelow: 'md', render: (r) => r.businessId ?? '—' },
  { key: 'definitionName', title: '流程', width: 140 },
  { key: 'nodeCode', title: '当前节点', width: 100, hideBelow: 'sm' },
  {
    key: 'taskType',
    title: '任务类型',
    width: 90,
    render: (r) => {
      const t = TASK_TYPE_TAG[r.taskType] ?? { label: r.taskType, type: 'default' as const }
      return h(NTag, { type: t.type, size: 'small', bordered: false }, { default: () => t.label })
    },
  },
  { key: 'assigneeName', title: '处理人', width: 100, hideBelow: 'md' },
  { key: 'dueAt', title: '截止时间', width: 150, hideBelow: 'sm', render: (r) => formatDateTime(r.dueAt) },
  {
    key: 'status',
    title: '状态',
    width: 90,
    render: (r) => {
      const t = TASK_TAG[r.status]
      return h(NTag, { type: t.type, size: 'small', bordered: false }, { default: () => t.label })
    },
  },
  {
    key: 'actions',
    title: '操作',
    width: 200,
    align: 'center',
    render: (r) => {
      // 事件流水只读、任何人可见，写操作仅 admin/operator 可见
      const nodes = [h(NButton, { text: true, size: 'small', onClick: () => openEvents(r) }, { default: () => '事件' })]
      if (canWrite.value) {
        if (r.status === 'PENDING') {
          nodes.push(h(NButton, { text: true, size: 'small', onClick: () => onClaim(r) }, { default: () => '领取' }))
        }
        nodes.push(
          h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openAction(r, 'approve') }, { default: () => '审批' }),
          h(NButton, { text: true, type: 'error', size: 'small', onClick: () => openAction(r, 'reject') }, { default: () => '驳回' }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px' }, nodes)
    },
  },
]

onMounted(() => void load())
</script>

<template>
  <div class="todo-list">
    <!-- 工具栏 -->
    <div class="toolbar">
      <span class="toolbar-title">我的待办（按截止时间升序）</span>
      <n-button size="small" @click="load">刷新</n-button>
    </div>

    <DataTable v-model:page="page" v-model:size="size" :columns="columns" :data="rows" :loading="loading" :total="total" :scroll-x="1090">
      <template #empty>
        <span>暂无待办任务</span>
      </template>
    </DataTable>

    <!-- 审批/驳回备注弹窗 -->
    <n-modal v-model:show="showAction" preset="card" :title="actionType === 'approve' ? '审批通过' : '驳回任务'" style="max-width: 480px">
      <n-input
        v-model:value="remark"
        type="textarea"
        :rows="3"
        :placeholder="actionType === 'reject' ? '请填写驳回原因（必填）' : '审批意见（可选）'"
      />
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <n-button size="small" @click="closeAction">取消</n-button>
          <n-button v-if="canWrite" size="small" type="primary" :loading="submitting" @click="confirmAction">确认</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 任务事件流水弹窗 -->
    <n-modal v-model:show="showEvents" preset="card" title="任务事件流水" style="max-width: 560px">
      <n-spin :show="eventsLoading">
        <div v-if="events.length === 0 && !eventsLoading" class="empty-tip">暂无事件</div>
        <div v-for="ev in events" :key="ev.id" class="event-item">
          <div class="event-head">
            <n-tag size="small" :bordered="false">{{ ev.action }}</n-tag>
            <span class="event-operator">{{ ev.operatorName }}</span>
            <span class="event-time">{{ formatDateTime(ev.occurredAt) }}</span>
          </div>
          <div class="event-detail">{{ ev.fromNode ?? '—' }} -> {{ ev.toNode ?? '—' }}<span v-if="ev.remark">：{{ ev.remark }}</span></div>
        </div>
      </n-spin>
    </n-modal>
  </div>
</template>

<style scoped>
.todo-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.toolbar-title {
  font-weight: 600;
}
.empty-tip {
  padding: 24px 0;
  text-align: center;
  color: var(--n-text-color-3, var(--fms-text-muted));
}
.event-item {
  padding: 8px 0;
  border-bottom: 1px solid var(--n-divider-color, #eee);
}
.event-item:last-child {
  border-bottom: none;
}
.event-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.event-operator {
  font-size: 13px;
}
.event-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--n-text-color-3, var(--fms-text-muted));
}
.event-detail {
  margin-top: 4px;
  font-size: 12px;
  color: var(--n-text-color-3, var(--fms-text-muted));
}
</style>
