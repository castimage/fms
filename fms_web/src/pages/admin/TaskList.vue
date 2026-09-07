<script setup lang="ts">
/**
 * 任务监控，pg_cron 任务执行记录，jobName/status 过滤 + 服务端分页，
 * detail 为 jsonb 文本，截断展示、tooltip 看全量。
 */
import { h, onMounted, ref, watch } from 'vue'
import { NTag } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import { adminApi } from '@/api/admin'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { TaskRunStatus, TaskRunView } from '@/types/admin'
import { formatDateTime } from '@/utils/format'

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '成功', value: 'OK' },
  { label: '失败', value: 'FAIL' },
]

const jobName = ref('')
const status = ref<TaskRunStatus | ''>('')
const page = ref(1)
const size = ref(20)
const rows = ref<TaskRunView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await adminApi.pageTaskRuns({
      page: page.value,
      size: size.value,
      jobName: jobName.value || undefined,
      status: status.value || undefined,
    })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

/** 筛选变化重置到第一页重查，page 未变时直接 load 避免 watch 漏触发 */
function onFilterChange() {
  if (page.value === 1) void load()
  else page.value = 1
}
const onJobNameSearch = useDebounceFn(onFilterChange, 500)
watch(jobName, () => onJobNameSearch())
watch(status, onFilterChange)
watch([page, size], () => void load())

function renderStatus(s: TaskRunStatus) {
  const isOk = s === 'OK'
  return h(
    NTag,
    { type: isOk ? 'success' : 'error', size: 'small', bordered: false },
    { default: () => (isOk ? '成功' : '失败') },
  )
}

const columns: FmsColumn<TaskRunView>[] = [
  { key: 'jobName', title: '任务名称', width: 180 },
  { key: 'status', title: '状态', width: 80, render: (r) => renderStatus(r.status) },
  { key: 'startedAt', title: '开始时间', width: 150, render: (r) => formatDateTime(r.startedAt) },
  { key: 'finishedAt', title: '结束时间', width: 150, hideBelow: 'sm', render: (r) => formatDateTime(r.finishedAt) },
  { key: 'detail', title: '执行详情', width: 260, hideBelow: 'md' },
]

onMounted(() => void load())
</script>

<template>
  <div class="task-list">
    <FormGrid class="filter-grid">
      <n-form-item label="任务名称">
        <n-input v-model:value="jobName" placeholder="jobName 模糊" clearable />
      </n-form-item>
      <n-form-item label="状态">
        <n-select v-model:value="status" :options="STATUS_OPTIONS" style="width: 100%" />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">任务监控</span>
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
        <span>暂无任务记录</span>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.task-list {
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
