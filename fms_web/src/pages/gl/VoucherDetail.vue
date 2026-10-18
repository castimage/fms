<script setup lang="ts">
/**
 * 凭证详情，头卡 + 只读分录表，合计行 + 状态时间线，手机 vertical + 审计轨迹折叠
 * /api/audit/logs 按 tableName=gl.vouchers + recordId 精查，rls 仅 auditor/admin 可见
 * + 状态机操作区，编辑/删除/提交/审批/驳回，下一批次/过账/冲销，data:null
 * 他人 DRAFT/越权显示"凭证不存在或无权查看"，复用 404 文案，不报错。
 */
import { computed, onMounted, ref } from 'vue'
import {
  NButton,
  NCollapse,
  NCollapseItem,
  NDataTable,
  NEmpty,
  NInput,
  NModal,
  NPopconfirm,
  NStep,
  NSteps,
  NTimeline,
  NTimelineItem,
  useMessage,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import { auditApi } from '@/api/audit'
import { glApi } from '@/api/gl'
import PageCard from '@/components/common/PageCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoint } from '@/utils/breakpoint'
import { formatAmount, formatDate, formatDateTime } from '@/utils/format'
import { ApiError, ErrorCode } from '@/types/api'
import type { AuditLogView } from '@/types/audit'
import type { VoucherDetailView } from '@/types/gl'
import { useCanWrite } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'

const props = defineProps<{ id?: string }>()
const route = useRoute()
/** 路由未配 props:true 时回退取 route.params.id */
const id = computed(() => props.id ?? (typeof route.params.id === 'string' ? route.params.id : ''))

const router = useRouter()
const message = useMessage()
const { isMobile } = useBreakpoint()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const detail = ref<VoucherDetailView | null>(null)
const loading = ref(true)
const notFound = ref(false)
const actionLoading = ref(false)

/** 冲销弹窗 */
const showReverse = ref(false)
const reverseReason = ref('')

/** 驳回弹窗，原因必填 */
const showReject = ref(false)
const rejectReason = ref('')

async function load() {
  if (!id.value) return
  loading.value = true
  try {
    const d = await glApi.getVoucher(id.value)
    if (!d) {
      // 他人 DRAFT / 越权，不泄露存在性
      notFound.value = true
      return
    }
    detail.value = d
  } finally {
    loading.value = false
  }
}
onMounted(() => void load())

// 审计轨迹，/api/audit/logs，tableName=gl.vouchers + recordId=凭证 id
const auth = useAuthStore()
/** 审计轨迹仅 auditor/admin 可见，与后端 requireAuditRole 同口径 */
const canViewAudit = computed(() => auth.user?.role === 'auditor' || auth.user?.role === 'admin')

const auditLogs = ref<AuditLogView[]>([])
const auditLoading = ref(false)
/** 请求被 40300 拒绝，本地角色与后端不一致等时降级为无权提示 */
const auditDenied = ref(false)

/** n-timeline-item type 联合，与 naive-ui 声明一致，防类型拓宽报错 */
type TimelineItemType = 'default' | 'success' | 'error' | 'warning' | 'info'

/** 动作节点类型，与审计列表页 AuditLogList 同表 */
function auditType(action: string): TimelineItemType {
  return (
    ({ INSERT: 'info', UPDATE: 'warning', DELETE: 'error', APPROVE: 'success', POST: 'success', REVERSE: 'error', REJECT: 'error' } as Record<string, TimelineItemType>)[
      action
    ] ?? 'default'
  )
}

/** 长 id 截断展示，紧凑时间线 */
function shortId(v?: string | null): string {
  return v && v.length > 8 ? `${v.slice(0, 8)}…` : (v ?? '—')
}

/** 审计行摘要，ip 缺省时只显示时间，不拼出 undefined */
function auditLine(l: AuditLogView): string {
  const at = formatDateTime(l.occurredAt)
  return l.ip ? `${at} · IP ${l.ip}` : at
}

async function loadAudit() {
  if (!id.value || !canViewAudit.value) return
  auditLoading.value = true
  try {
    const res = await auditApi.pageLogs({ tableName: 'gl.vouchers', recordId: id.value, page: 1, size: await refdataPageSize() })
    auditLogs.value = res.items
  } catch (e) {
    // 40300，角色变更等则无权提示，其余错误已由 http.ts toast
    auditDenied.value = e instanceof ApiError && e.code === ErrorCode.FORBIDDEN
    auditLogs.value = []
  } finally {
    auditLoading.value = false
  }
}
onMounted(() => void loadAudit())

/** 当前登录人，无登录态时视为他人 */
const currentUserId = computed(() => {
  const raw = localStorage.getItem('fms_user')
  try {
    return raw ? (JSON.parse(raw) as { id?: string }).id ?? null : null
  } catch {
    return null
  }
})

/** 制单人是否当前用户，审批 SoD，审批人须非制单人 */
const isCreator = computed(() => !!detail.value && !!currentUserId.value && detail.value.createdBy === currentUserId.value)

// 分录列
const lineColumns: DataTableColumns = [
  { key: 'lineNo', title: '行号', width: 64 },
  { key: 'account', title: '科目', render: (r: any) => `${r.accountCode} ${r.accountName}` },
  { key: 'summary', title: '摘要', ellipsis: { tooltip: true } },
  { key: 'debit', title: '借方', align: 'right', render: (r: any) => formatAmount(r.debit) },
  { key: 'credit', title: '贷方', align: 'right', render: (r: any) => formatAmount(r.credit) },
]

const totalDebit = computed(() => detail.value?.totalDebit ?? 0)
const totalCredit = computed(() => detail.value?.totalCredit ?? 0)

// 状态时间线
interface StepItem {
  title: string
  description: string
  status: 'wait' | 'process' | 'finish' | 'error'
}
const timelineSteps = computed<StepItem[]>(() => {
  const d = detail.value
  if (!d) return []
  const createdDesc = `${d.createdByName} · ${formatDateTime(d.createdAt)}`
  const steps: StepItem[] = [
    { title: '创建', description: createdDesc, status: 'finish' },
    {
      // 提交时刻字段后端未返回，缺失显示 '—'
      title: '提交',
      description: d.status === 'DRAFT' ? '等待提交' : '—',
      status: d.status === 'DRAFT' ? 'wait' : 'finish',
    },
    {
      title: '审批',
      description: d.approvedAt
        ? formatDateTime(d.approvedAt)
        : d.status === 'SUBMITTED'
          ? '待审批'
          : d.status === 'REJECTED'
            ? '已驳回（下一批次退回修改）'
            : '—',
      status:
        d.status === 'SUBMITTED'
          ? 'process'
          : d.status === 'REJECTED'
            ? 'error'
            : d.status === 'APPROVED' || d.status === 'POSTED' || d.status === 'REVERSED'
              ? 'finish'
              : 'wait',
    },
    {
      title: '过账',
      description: d.postedAt ? formatDateTime(d.postedAt) : d.status === 'APPROVED' ? '待过账' : '—',
      status: d.status === 'APPROVED' ? 'process' : d.status === 'POSTED' || d.status === 'REVERSED' ? 'finish' : 'wait',
    },
  ]
  if (d.status === 'REJECTED') {
    steps.push({ title: '驳回', description: '下一批次退回修改后重新提交', status: 'error' })
  }
  if (d.status === 'REVERSED') {
    steps.push({ title: '冲销', description: '已生成反向凭证', status: 'error' })
  }
  return steps
})

// 操作
async function doAction(
  action: () => Promise<VoucherDetailView>,
  successMsg: string,
): Promise<VoucherDetailView | null> {
  actionLoading.value = true
  try {
    const res = await action()
    detail.value = res
    message.success(successMsg)
    return res
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
    return null
  } finally {
    actionLoading.value = false
  }
}

async function onDelete() {
  if (!id.value) return
  await glApi.deleteVoucher(id.value)
  message.success('凭证已删除')
  void router.replace('/gl/vouchers')
}
async function onSubmit() {
  if (!id.value) return
  await doAction(() => glApi.submitVoucher(id.value), '已提交审核')
}
async function onApprove() {
  if (!id.value) return
  await doAction(() => glApi.approveVoucher(id.value), '已审批通过')
}
async function onPost() {
  if (!id.value) return
  await doAction(() => glApi.postVoucher(id.value), '已过账')
}
async function onReject() {
  if (!id.value) return
  // 驳回原因必填，后端 @NotBlank + pg 函数 btrim 双保险，前端先行拦截
  if (!rejectReason.value.trim()) {
    message.error('请填写驳回原因')
    return
  }
  const res = await doAction(() => glApi.rejectVoucher(id.value, rejectReason.value.trim()), '已驳回（下一批次退回修改）')
  if (res) {
    showReject.value = false
    rejectReason.value = ''
  }
}
async function onReverse() {
  if (!id.value) return
  const res = await doAction(() => glApi.reverseVoucher(id.value, reverseReason.value || undefined), '已生成冲销凭证')
  if (res) void router.replace(`/gl/vouchers/${res.id}`)
  showReverse.value = false
  reverseReason.value = ''
}
</script>

<template>
  <!-- 越权/不存在，复用 404 文案，不报错 -->
  <div v-if="notFound" class="not-found">
    <n-empty description="凭证不存在或无权查看">
      <template #extra>
        <n-button size="small" @click="router.replace('/gl/vouchers')">返回凭证列表</n-button>
      </template>
    </n-empty>
  </div>

  <div v-else-if="detail" class="voucher-detail">
    <!-- 头卡 -->
    <PageCard :loading="loading">
      <template #header>
        <div class="head">
          <span class="head-title">凭证 {{ detail.voucherNo ?? '（草稿未编号）' }}</span>
          <StatusTag :status="detail.status" />
        </div>
      </template>
      <template #extra>
        <span class="head-note">{{ detail.note || '无摘要' }}</span>
      </template>
      <div class="head-grid">
        <div><label>期间</label>{{ detail.period }}</div>
        <div><label>日期</label>{{ formatDate(detail.voucherDate) }}</div>
        <div><label>来源</label>{{ detail.sourceType }}</div>
        <div><label>制单人</label>{{ detail.createdByName }}</div>
        <div><label>审批</label>{{ detail.approvedAt ? `${detail.approvedBy ?? ''} · ${formatDateTime(detail.approvedAt)}` : '—' }}</div>
        <div><label>过账</label>{{ detail.postedAt ? formatDateTime(detail.postedAt) : '—' }}</div>
        <div v-if="detail.reversalOfVoucherId"><label>冲销自</label>{{ detail.reversalOfVoucherId }}</div>
        <div v-if="detail.reversedByVoucherId">
          <label>被冲销</label>
          <n-button text type="primary" size="tiny" @click="router.push(`/gl/vouchers/${detail!.reversedByVoucherId}`)">
            查看反向凭证 {{ detail.reversedByVoucherId }}
          </n-button>
        </div>
      </div>

      <!-- 分录表 -->
      <n-data-table :columns="lineColumns" :data="detail.lines" size="small" :bordered="false" />
      <div class="sum-row">
        <span>合计</span>
        <span class="num">借 {{ formatAmount(totalDebit) }}</span>
        <span class="num">贷 {{ formatAmount(totalCredit) }}</span>
      </div>

      <!-- 状态时间线，手机 vertical -->
      <n-steps :direction="isMobile ? 'vertical' : 'horizontal'" class="timeline" size="small">
        <n-step
          v-for="(s, i) in timelineSteps"
          :key="i"
          :title="s.title"
          :description="s.description"
          :status="s.status"
        />
      </n-steps>

      <!-- 审计轨迹，rls 仅 auditor/admin 可见 -->
      <n-collapse class="audit-collapse">
        <n-collapse-item title="审计轨迹" name="audit">
          <n-empty
            v-if="!canViewAudit || auditDenied"
            description="审计日志仅审计员/管理员可见"
            size="small"
          />
          <div v-else-if="auditLoading" class="audit-tip">审计轨迹加载中…</div>
          <n-empty v-else-if="auditLogs.length === 0" description="暂无审计记录" size="small" />
          <n-timeline v-else class="audit-timeline">
            <n-timeline-item
              v-for="(l, i) in auditLogs"
              :key="i"
              :type="auditType(l.action)"
              :title="`${l.action} · ${shortId(l.actorId)}`"
              :content="auditLine(l)"
            />
          </n-timeline>
        </n-collapse-item>
      </n-collapse>
    </PageCard>

    <!-- 操作区，按状态机渲染 -->
    <div class="action-bar">
      <template v-if="detail.status === 'DRAFT'">
        <n-button v-if="canWrite" size="small" @click="router.push(`/gl/vouchers/${id}/edit`)">编辑</n-button>
        <n-popconfirm v-if="canWrite" @positive-click="onDelete">
          <template #trigger>
            <n-button size="small" type="error" :loading="actionLoading">删除</n-button>
          </template>
          确认删除该草稿凭证？
        </n-popconfirm>
        <n-button v-if="canWrite" size="small" type="primary" :loading="actionLoading" @click="onSubmit">提交</n-button>
      </template>
      <template v-else-if="detail.status === 'SUBMITTED'">
        <n-button
          v-if="canWrite"
          size="small"
          type="primary"
          :disabled="isCreator"
          :loading="actionLoading"
          @click="onApprove"
        >
          审批
        </n-button>
        <n-button
          v-if="canWrite"
          size="small"
          type="error"
          :disabled="isCreator"
          title="驳回后凭证进入下一批次，退回修改后重新提交"
          @click="showReject = true"
        >
          驳回（下一批次）
        </n-button>
      </template>
      <template v-else-if="detail.status === 'APPROVED'">
        <n-popconfirm v-if="canWrite" @positive-click="onPost">
          <template #trigger>
            <n-button size="small" type="primary" :loading="actionLoading">过账</n-button>
          </template>
          过账后凭证不可修改，确认过账？
        </n-popconfirm>
      </template>
      <template v-else-if="detail.status === 'REJECTED'">
        <span class="reversed-tip">已驳回：下一批次退回修改后重新提交</span>
      </template>
      <template v-else-if="detail.status === 'POSTED'">
        <n-button v-if="canWrite" size="small" type="error" @click="showReverse = true">冲销</n-button>
      </template>
      <template v-else-if="detail.status === 'REVERSED'">
        <span class="reversed-tip">该凭证已冲销，不可操作</span>
      </template>
    </div>

    <!-- 冲销原因弹窗，可空 = 全量冲销 -->
    <n-modal
      v-model:show="showReverse"
      preset="card"
      title="红字冲销"
      style="width: min(440px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-input v-model:value="reverseReason" type="textarea" placeholder="冲销原因（可空 = 全量冲销）" :rows="3" />
      <template #action>
        <n-button size="small" @click="showReverse = false">取消</n-button>
        <n-button v-if="canWrite" size="small" type="primary" :loading="actionLoading" @click="onReverse">确认冲销</n-button>
      </template>
    </n-modal>

    <!-- 驳回原因弹窗，原因必填，驳回后凭证进入 REJECTED，下一批次退回修改 -->
    <n-modal
      v-model:show="showReject"
      preset="card"
      title="驳回凭证（下一批次）"
      style="width: min(440px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-input v-model:value="rejectReason" type="textarea" placeholder="驳回原因（必填，最长 500 字符）" :rows="3" maxlength="500" show-count />
      <template #action>
        <n-button size="small" @click="showReject = false">取消</n-button>
        <n-button v-if="canWrite" size="small" type="primary" :loading="actionLoading" @click="onReject">确认驳回</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.voucher-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.not-found {
  padding: 64px 0;
  text-align: center;
}
.head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.head-title {
  font-weight: 600;
}
.head-note {
  color: var(--fms-text-muted);
  font-size: 12px;
}
.head-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 8px 16px;
  margin-bottom: 16px;
  font-size: 13px;
}
.head-grid label {
  color: var(--fms-text-muted);
  margin-right: 6px;
}
.sum-row {
  display: flex;
  justify-content: flex-end;
  gap: 24px;
  padding: 8px 12px;
  font-weight: 600;
  font-size: 13px;
  border-top: 1px solid var(--n-border-color, #f0f0f0);
}
.timeline {
  margin: 20px 0 8px;
}
.audit-collapse {
  margin-top: 8px;
}
.audit-timeline {
  margin: 4px 0 0 4px;
}
.audit-tip {
  padding: 12px 0;
  color: var(--fms-text-muted);
  font-size: 13px;
  text-align: center;
}
.action-bar {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 12px 16px;
  background: var(--n-color, #fff);
  border-radius: 8px;
}
html.dark .action-bar {
  background: var(--n-color, #1e1e1e);
}
.reversed-tip {
  color: var(--fms-text-muted);
  font-size: 13px;
}
</style>
