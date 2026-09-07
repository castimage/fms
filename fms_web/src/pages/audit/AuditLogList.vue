<script setup lang="ts">
/**
 * 审计日志，分页 + 多条件筛选，顶部链完整性状态卡，audit_log_chain_check，prevOk=false
 * 展示首断链点与详情，csv 导出携带同筛选条件，手机端隐藏次要列，行展开看哈希链/变更值全文。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NDatePicker, NInput, NSelect, NTag, useMessage } from 'naive-ui'
import type { TagProps } from 'naive-ui'
import dayjs from 'dayjs'
import { auditApi } from '@/api/audit'
import DataTable from '@/components/common/DataTable.vue'
import PageCard from '@/components/common/PageCard.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { AuditLogView, ChainCheckView } from '@/types/audit'
import { formatDateTime } from '@/utils/format'

const message = useMessage()

const ACTION_OPTIONS = [
  { label: '全部', value: '' },
  { label: 'INSERT', value: 'INSERT' },
  { label: 'UPDATE', value: 'UPDATE' },
  { label: 'DELETE', value: 'DELETE' },
  { label: 'APPROVE', value: 'APPROVE' },
  { label: 'POST', value: 'POST' },
  { label: 'REVERSE', value: 'REVERSE' },
]

/** 动作标签色，未知动作兜底 default  */
const ACTION_TYPE: Record<string, TagProps['type']> = {
  INSERT: 'info',
  UPDATE: 'warning',
  DELETE: 'error',
  APPROVE: 'success',
  POST: 'success',
  REVERSE: 'error',
}

// 筛选与分页
const tableName = ref('')
const action = ref('')
const actorId = ref('')
/** 时间范围，datetimerange 时间戳对 */
const rangeTs = ref<[number, number] | null>(null)
const page = ref(1)
const size = ref(20)
const rows = ref<AuditLogView[]>([])
const total = ref(0)
const loading = ref(false)

/** 后端 from/to 为 ISO 日期时间（YYYY-MM-DDTHH:mm:ss） */
const from = computed(() => (rangeTs.value?.[0] ? dayjs(rangeTs.value[0]).format('YYYY-MM-DDTHH:mm:ss') : undefined))
const to = computed(() => (rangeTs.value?.[1] ? dayjs(rangeTs.value[1]).format('YYYY-MM-DDTHH:mm:ss') : undefined))

/** 筛选变化重置到第一页重查，page 未变时直接 load 避免 watch 漏触发 */
function onFilterChange() {
  if (page.value === 1) void load()
  else page.value = 1
}

/** 哈希列截断展示，全文见行展开/提示 */
function shortHash(v?: string | null): string {
  return v && v.length > 16 ? `${v.slice(0, 16)}…` : (v ?? '—')
}

const columns: FmsColumn<AuditLogView>[] = [
  { key: 'occurredAt', title: '时间', width: 150, render: (r) => formatDateTime(r.occurredAt) },
  { key: 'actorId', title: '操作人', width: 110, hideBelow: 'sm' },
  {
    key: 'action',
    title: '动作',
    width: 90,
    render: (r) => h(NTag, { type: ACTION_TYPE[r.action] ?? 'default', size: 'small', bordered: false }, { default: () => r.action }),
  },
  { key: 'tableName', title: '数据表', width: 110 },
  { key: 'recordId', title: '记录ID', width: 150, hideBelow: 'md', render: (r) => shortHash(r.recordId) },
  { key: 'ip', title: '来源IP', width: 110, hideBelow: 'sm' },
  { key: 'requestId', title: '请求ID', width: 150, hideBelow: 'sm', render: (r) => shortHash(r.requestId) },
  { key: 'rowHash', title: '行哈希', width: 130, hideBelow: 'md', render: (r) => shortHash(r.rowHash) },
]

async function load() {
  loading.value = true
  try {
    const res = await auditApi.pageLogs({
      page: page.value,
      size: size.value,
      tableName: tableName.value || undefined,
      action: action.value || undefined,
      actorId: actorId.value || undefined,
      from: from.value,
      to: to.value,
    })
    rows.value = res.items
    total.value = res.total
  } finally {
    loading.value = false
  }
}

// 链完整性
const chain = ref<ChainCheckView | null>(null)
const chainLoading = ref(false)

/** 链校验，调 pg 函数逐行重算比对，prevOk=false 时 brokenAt 为首断链点 */
async function checkChain() {
  chainLoading.value = true
  try {
    chain.value = await auditApi.chainCheck()
  } finally {
    chainLoading.value = false
  }
}

async function onExport() {
  try {
    await auditApi.exportCsv({
      tableName: tableName.value || undefined,
      action: action.value || undefined,
      actorId: actorId.value || undefined,
      from: from.value,
      to: to.value,
    })
    message.success('导出成功，请查看下载文件')
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  }
}

watch([tableName, action, actorId, rangeTs], onFilterChange)
watch([page, size], () => void load())
onMounted(() => {
  void load()
  void checkChain()
})
</script>

<template>
  <div class="audit-log-list">
    <!-- 链完整性状态卡 -->
    <PageCard title="审计链完整性" :loading="chainLoading">
      <template #extra>
        <n-button size="small" @click="checkChain">重新校验</n-button>
      </template>
      <n-alert v-if="chain" :type="chain.prevOk ? 'success' : 'error'" :bordered="false">
        <template v-if="chain.prevOk">审计链完整：自链首逐行重算一致（共 {{ total }} 条当前页记录）</template>
        <template v-else>
          审计链断链：首断链点记录 {{ chain.brokenAt ?? '未知' }}
          <div v-if="chain.detail" class="chain-detail">{{ chain.detail }}</div>
        </template>
      </n-alert>
      <n-empty v-else description="链校验结果待加载" />
    </PageCard>

    <!-- 筛选区，手机单列堆叠 -->
    <div class="filter-grid">
      <n-form-item label="操作人ID">
        <n-input v-model:value="actorId" placeholder="actorId" clearable style="width: 100%" />
      </n-form-item>
      <n-form-item label="数据表">
        <n-input v-model:value="tableName" placeholder="如 vouchers" clearable style="width: 100%" />
      </n-form-item>
      <n-form-item label="动作">
        <n-select v-model:value="action" :options="ACTION_OPTIONS" style="width: 100%" />
      </n-form-item>
      <n-form-item label="时间范围">
        <n-date-picker v-model:value="rangeTs" type="datetimerange" clearable style="width: 100%" />
      </n-form-item>
    </div>

    <!-- 工具栏 -->
    <div class="toolbar">
      <span class="toolbar-title">审计日志</span>
      <n-button size="small" @click="onExport">导出 CSV</n-button>
    </div>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="columns"
      :data="rows"
      :loading="loading"
      :total="total"
      :scroll-x="1000"
    >
      <template #empty>
        <span>暂无审计日志</span>
      </template>
      <!-- 手机端展开，哈希链两列 + 变更值全文 -->
      <template #rowDetail="{ row }">
        <div class="row-detail">
          <div><b>prevHash</b><span class="mono">{{ row.prevHash }}</span></div>
          <div><b>rowHash</b><span class="mono">{{ row.rowHash }}</span></div>
          <div><b>旧值</b><span class="mono">{{ row.oldValues ?? '—' }}</span></div>
          <div><b>新值</b><span class="mono">{{ row.newValues ?? '—' }}</span></div>
        </div>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.audit-log-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.filter-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0 16px;
  background: var(--n-color, #fff);
  border-radius: 8px;
  padding: 8px 16px 0;
}
html.dark .filter-grid {
  background: var(--n-color, #1e1e1e);
}
@media (min-width: 768px) {
  .filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (min-width: 992px) {
  .filter-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.toolbar-title {
  font-weight: 600;
}
.chain-detail {
  margin-top: 8px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  opacity: 0.85;
}
.row-detail {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  padding: 4px 0;
}
.row-detail div {
  display: flex;
  gap: 8px;
}
.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  word-break: break-all;
}
</style>
