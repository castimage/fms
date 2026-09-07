<script setup lang="ts">
/**
 * 站内通知，本人通知列表 + 未读数角标，isRead 三态筛选，已读动作走 REST。
 * WebSocket 实时推送本批不接入，连接方式见 api/notify.ts 的 NOTIFY_WS_URL。
 */
import { h, onMounted, ref, watch } from 'vue'
import { NButton, NTag, useMessage } from 'naive-ui'
import { NOTIFY_WS_URL, notifyApi } from '@/api/notify'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { NotificationView } from '@/types/notify'
import { formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()

/** 写操作权限，admin/operator 可见写按钮，viewer/auditor/report 只读 */
const canWrite = useCanWrite()

const READ_OPTIONS = [
  { label: '全部', value: '' },
  { label: '未读', value: 'unread' },
  { label: '已读', value: 'read' },
]

// 筛选状态
const readFilter = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<NotificationView[]>([])
const total = ref(0)
const loading = ref(false)
const unread = ref(0)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await notifyApi.pageList({
      page: page.value,
      size: size.value,
      isRead: readFilter.value === 'unread' ? false : readFilter.value === 'read' ? true : undefined,
    })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

/** 未读数角标，已读动作后一并刷新 */
async function refreshUnread() {
  unread.value = await notifyApi.unreadCount()
}

/** 筛选变化重置到第一页重查，page 未变时直接 load 避免 watch 漏触发 */
function onFilterChange() {
  if (page.value === 1) void load()
  else page.value = 1
}
watch(readFilter, onFilterChange)
watch([page, size], () => void load())

async function onMarkRead(id: string) {
  await notifyApi.markRead(id)
  message.success('已标记为已读')
  await Promise.all([load(), refreshUnread()])
}

async function onMarkAllRead() {
  const n = await notifyApi.markAllRead()
  message.success(`已全部标记为已读（${n} 条）`)
  await Promise.all([load(), refreshUnread()])
}

const columns: FmsColumn<NotificationView>[] = [
  { key: 'title', title: '标题', width: 190 },
  {
    key: 'type',
    title: '类型',
    width: 100,
    hideBelow: 'sm',
    render: (r) => h(NTag, { size: 'small', bordered: false }, { default: () => r.type || '—' }),
  },
  {
    key: 'isRead',
    title: '状态',
    width: 80,
    render: (r) =>
      r.isRead
        ? h(NTag, { size: 'small', bordered: false }, { default: () => '已读' })
        : h(NTag, { type: 'warning', size: 'small', bordered: false }, { default: () => '未读' }),
  },
  { key: 'content', title: '内容', width: 280, hideBelow: 'md' },
  { key: 'createdAt', title: '时间', width: 150, hideBelow: 'sm', render: (r) => formatDateTime(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 100,
    align: 'center',
    render: (r) =>
      r.isRead || !canWrite.value
        ? ''
        : h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => onMarkRead(r.id) }, { default: () => '标记已读' }),
  },
]

onMounted(() => {
  void load()
  void refreshUnread()
})
</script>

<template>
  <div class="notify-list">
    <n-alert type="info" :bordered="false" class="ws-hint">
      WebSocket 实时推送端点：{{ NOTIFY_WS_URL }}（连接方式见 api/notify.ts）。本页为 REST 轮询，未接入实时订阅。
    </n-alert>

    <FormGrid class="filter-grid">
      <n-form-item label="状态">
        <n-select v-model:value="readFilter" :options="READ_OPTIONS" style="width: 100%" />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">通知中心</span>
      <div class="toolbar-actions">
        <n-tag :type="unread > 0 ? 'warning' : 'default'" size="small" :bordered="false">未读 {{ unread }} 条</n-tag>
        <n-button v-if="canWrite" size="small" :disabled="unread === 0" @click="onMarkAllRead">全部已读</n-button>
      </div>
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
        <span>暂无通知</span>
      </template>
      <template #rowDetail="{ row }">
        <div class="notify-detail">
          <p class="notify-detail__title">{{ row.title }}</p>
          <p>{{ row.content }}</p>
          <p class="notify-detail__meta">
            类型：{{ row.type }} · 时间：{{ formatDateTime(row.createdAt) }}
          </p>
        </div>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.notify-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ws-hint {
  margin-bottom: 0;
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
  align-items: center;
  gap: 8px;
}
.notify-detail {
  padding: 4px 8px;
}
.notify-detail__title {
  font-weight: 600;
}
.notify-detail__meta {
  color: var(--fms-text-muted);
  font-size: 12px;
}
</style>
