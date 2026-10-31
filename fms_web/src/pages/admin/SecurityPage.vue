<script setup lang="ts">
/**
 * 安全中心，api Key 管理 + 登录日志两卡。
 * api Key，X-api-key 系统间集成，-2，签发明文仅一次返回，库内只存 hmac 盲索引，
 * 关闭弹窗后无法再取回，列表仅展示 key_prefix，fms_ 前 8 位，密钥本体不落任何响应。
 *
 * 登录日志，登录审计，um.login_log 按月分区，登录名/时间/成败/锁定过滤，
 * admin 经 rls 权限点 audit:read:all 全量可见。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NPopconfirm, NTag, useMessage } from 'naive-ui'
import type { TagProps } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import dayjs from 'dayjs'
import { adminApi } from '@/api/admin'
import PageCard from '@/components/common/PageCard.vue'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { ApiKeyScope, ApiKeyView, LoginLogView } from '@/types/admin'
import { formatDateTime } from '@/utils/format'

const message = useMessage()

// api Key 管理
const keys = ref<ApiKeyView[]>([])
const keysLoading = ref(false)
/** api Key 列表独立分页态，本地切片展示，与下方日志分页互不干扰 */
const keyPage = ref(1)
const keySize = ref(50)
let keySeq = 0

async function loadKeys() {
  const seq = ++keySeq
  keysLoading.value = true
  try {
    const res = await adminApi.listApiKeys()
    if (seq !== keySeq) return
    keys.value = res
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    if (seq === keySeq) keysLoading.value = false
  }
}

const KEY_STATUS_TYPES: Record<string, TagProps['type']> = {
  ACTIVE: 'success',
  DISABLED: 'default',
  REVOKED: 'error',
}
const KEY_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '停用',
  REVOKED: '已吊销',
}

const SCOPE_LABELS: Record<ApiKeyScope, string> = {
  read: '只读（fms_viewer）',
  read_write: '读写（fms_operator）',
}

/** 前缀打码展示，明文本体永不展示 */
function maskKey(p: string): string {
  return p.length >= 8 ? `${p.slice(0, 8)}${'•'.repeat(4)}` : p
}

// 签发
const showIssue = ref(false)
const issueLoading = ref(false)
const issueForm = ref<{ name: string; scope: ApiKeyScope; expiresAt: number | null }>({
  name: '',
  scope: 'read_write',
  expiresAt: null,
})

function openIssue() {
  issueForm.value = { name: '', scope: 'read_write', expiresAt: null }
  showIssue.value = true
}

async function submitIssue() {
  const name = issueForm.value.name.trim()
  if (!name) {
    message.warning('请填写密钥名称')
    return
  }
  if (name.length > 64) {
    message.warning('密钥名称最长 64 字符')
    return
  }
  issueLoading.value = true
  try {
    const res = await adminApi.createApiKey({
      name,
      scope: issueForm.value.scope,
      expiresAt: issueForm.value.expiresAt
        ? dayjs(issueForm.value.expiresAt).format('YYYY-MM-DD HH:mm:ss')
        : null,
    })
    showIssue.value = false
    issuedKey.value = res
    await loadKeys()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    issueLoading.value = false
  }
}

// 签发结果，明文仅一次返回
const issuedKey = ref<{ apiKey: string; keyPrefix: string } | null>(null)
const copyLoading = ref(false)

/** 结果弹窗，关闭即丢弃明文，之后无法再取回 */
const issuedKeyVisible = computed({
  get: () => issuedKey.value !== null,
  set: (v: boolean) => {
    if (!v) issuedKey.value = null
  },
})

async function copyKey() {
  if (!issuedKey.value) return
  try {
    await navigator.clipboard.writeText(issuedKey.value.apiKey)
    message.success('已复制，请妥善保存')
  } catch {
    message.error('复制失败，请手动选择复制')
  }
}

// 吊销
const revoking = ref<string | null>(null)

async function onRevoke(r: ApiKeyView) {
  revoking.value = r.id
  try {
    await adminApi.revokeApiKey(r.id)
    message.success('密钥已吊销')
    await loadKeys()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    revoking.value = null
  }
}

const keyColumns: FmsColumn<ApiKeyView>[] = [
  { key: 'name', title: '名称', width: 160 },
  {
    key: 'keyPrefix',
    title: '密钥前缀',
    width: 140,
    render: (r) => h('code', { class: 'key-prefix' }, maskKey(r.keyPrefix)),
  },
  {
    key: 'scope',
    title: '作用域',
    width: 170,
    render: (r) => h(NTag, { type: r.scope === 'read' ? 'info' : 'warning', size: 'small', bordered: false }, { default: () => SCOPE_LABELS[r.scope] }),
  },
  {
    key: 'status',
    title: '状态',
    width: 90,
    render: (r) => h(NTag, { type: KEY_STATUS_TYPES[r.status] ?? 'default', size: 'small', bordered: false }, { default: () => KEY_STATUS_LABELS[r.status] ?? r.status }),
  },
  { key: 'expiresAt', title: '过期时间', width: 150, hideBelow: 'md', render: (r) => formatDateTime(r.expiresAt) },
  { key: 'lastUsedAt', title: '最近使用', width: 150, hideBelow: 'md', render: (r) => formatDateTime(r.lastUsedAt) },
  { key: 'createdAt', title: '创建时间', width: 150, hideBelow: 'sm', render: (r) => formatDateTime(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 110,
    align: 'center',
    render: (r) =>
      r.status === 'ACTIVE'
        ? h(
            NPopconfirm,
            { onPositiveClick: () => onRevoke(r) },
            {
              trigger: () =>
                h(
                  NButton,
                  { text: true, size: 'small', type: 'error', loading: revoking.value === r.id },
                  { default: () => '吊销' },
                ),
              default: () => `确认吊销「${r.name}」？吊销后立即失效且无法恢复。`,
            },
          )
        : h('span', { style: 'color:var(--n-text-color-3, var(--fms-text-muted))' }, '—'),
  },
]

onMounted(() => void loadKeys())

// 登录日志，登录审计，真实 um.login_log 列表
/** 成功/失败过滤选项 */
const RESULT_OPTIONS = [
  { label: '全部', value: '' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'fail' },
]
/** 锁定触发过滤选项，locked_until 是否落库 */
const LOCKED_OPTIONS = [
  { label: '全部', value: '' },
  { label: '是（触发锁定）', value: 'yes' },
  { label: '否', value: 'no' },
]
const loginName = ref('')
const fromTs = ref<number | null>(null)
const toTs = ref<number | null>(null)
const result = ref('')
const locked = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<LoginLogView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await adminApi.pageLoginLogs({
      page: page.value,
      size: size.value,
      loginName: loginName.value || undefined,
      from: fromTs.value ? dayjs(fromTs.value).format('YYYY-MM-DDTHH:mm:ss') : undefined,
      to: toTs.value ? dayjs(toTs.value).format('YYYY-MM-DDTHH:mm:ss') : undefined,
      success: result.value === '' ? undefined : result.value === 'success',
      locked: locked.value === '' ? undefined : locked.value === 'yes',
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
const onLoginNameSearch = useDebounceFn(onFilterChange, 500)
watch(loginName, () => onLoginNameSearch())
watch([fromTs, toTs, result, locked], onFilterChange)
watch([page, size], () => void load())

/** 失败原因文案，AuthServiceImpl.recordLogin 落库口径 */
const FAIL_REASON_LABELS: Record<string, string> = {
  user_not_found: '用户不存在',
  locked: '账户锁定',
  disabled: '账户停用',
  bad_password: '密码错误',
  bad_totp: '验证码错误',
  password_changed: '改密事件',
}

const columns: FmsColumn<LoginLogView>[] = [
  { key: 'loginName', title: '登录名', width: 130 },
  {
    key: 'success',
    title: '结果',
    width: 80,
    render: (r) =>
      h(NTag, { type: r.success ? 'success' : 'error', size: 'small', bordered: false }, { default: () => (r.success ? '成功' : '失败') }),
  },
  { key: 'ip', title: 'IP', width: 140, render: (r) => r.ip ?? '—' },
  {
    key: 'failReason',
    title: '失败原因',
    width: 110,
    hideBelow: 'md',
    render: (r) => (r.failReason ? (FAIL_REASON_LABELS[r.failReason] ?? r.failReason) : '—'),
  },
  {
    key: 'lockedUntil',
    title: '锁定到期',
    width: 150,
    hideBelow: 'md',
    render: (r) => (r.lockedUntil ? formatDateTime(r.lockedUntil) : '—'),
  },
  { key: 'userAgent', title: 'User-Agent', width: 220, hideBelow: 'lg', render: (r) => r.userAgent ?? '—' },
  { key: 'occurredAt', title: '发生时间', width: 160, render: (r) => formatDateTime(r.occurredAt) },
]

onMounted(() => void load())
</script>

<template>
  <div class="security-page">
    <!-- api Key 管理，-2，列表/签发/吊销，签发明文仅一次返回 -->
    <PageCard title="API Key 管理" :loading="keysLoading">
      <template #extra>
        <n-button size="small" type="primary" @click="openIssue">签发密钥</n-button>
      </template>
      <div class="toolbar-hint">
        API Key 用于系统间集成（X-API-Key 头，网关透传后端校验）：read -> 只读（fms_viewer），read_write -> 读写（fms_operator）。
        密钥仅当前管理员本人可见/管理；吊销为软删除（行保留），吊销后立即失效。
      </div>
      <DataTable
        v-model:page="keyPage"
        v-model:size="keySize"
        :columns="keyColumns"
        :data="keys"
        :loading="keysLoading"
        :total="keys.length"
        :scroll-x="900"
        :local-pagination="true"
      >
        <template #empty>
          <span>暂无 API 密钥，点击右上角「签发密钥」创建</span>
        </template>
      </DataTable>
    </PageCard>

    <!-- 签发弹窗 -->
    <n-modal v-model:show="showIssue" preset="card" title="签发 API 密钥" style="width: min(480px, calc(100vw - 32px))" :bordered="false">
      <n-alert type="info" :bordered="false" class="issue-hint">
        签发后明文仅展示一次（库内只存 HMAC 盲索引），请立即复制保存；scope 决定注入角色，过期时间留空 = 永不过期。
      </n-alert>
      <n-form label-placement="top" size="small">
        <n-form-item label="密钥名称">
          <n-input v-model:value="issueForm.name" placeholder="如：数据同步服务" maxlength="64" clearable />
        </n-form-item>
        <n-form-item label="作用域">
          <n-select
            v-model:value="issueForm.scope"
            :options="[
              { label: '只读（read -> fms_viewer）', value: 'read' },
              { label: '读写（read_write -> fms_operator）', value: 'read_write' },
            ]"
          />
        </n-form-item>
        <n-form-item label="过期时间（可空 = 永不过期）">
          <n-date-picker v-model:value="issueForm.expiresAt" type="datetime" clearable style="width: 100%" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showIssue = false">取消</n-button>
        <n-button size="small" type="primary" :loading="issueLoading" @click="submitIssue">签发</n-button>
      </template>
    </n-modal>

    <!-- 签发结果，明文仅此一次 -->
    <n-modal v-model:show="issuedKeyVisible" preset="card" title="签发成功 - 请立即保存" style="width: min(560px, calc(100vw - 32px))" :bordered="false">
      <n-alert type="warning" :bordered="false" class="issue-hint">
        明文密钥仅此一次展示，关闭后无法再取回（库内仅存 HMAC 盲索引）。请立即复制并妥善保管。
      </n-alert>
      <div class="key-result">
        <code class="key-text">{{ issuedKey?.apiKey }}</code>
        <n-button size="small" :loading="copyLoading" @click="copyKey">复制</n-button>
      </div>
      <template #action>
        <n-button size="small" type="primary" @click="issuedKey = null">我已保存，关闭</n-button>
      </template>
    </n-modal>

    <!-- 登录日志，登录审计，真实 um.login_log 列表 -->
    <PageCard title="登录日志" :loading="loading">
      <div class="toolbar-hint">
        登录尝试审计（um.login_log 按月分区，仅追加）：登录名模糊 + 时间范围 + 成功失败 + 锁定触发过滤；
        失败原因口径：用户不存在/账户锁定/账户停用/密码错误/验证码错误，改密成功亦记一条「改密事件」。
      </div>
      <FormGrid class="filter-grid">
        <n-form-item label="登录名">
          <n-input v-model:value="loginName" placeholder="登录名模糊" clearable />
        </n-form-item>
        <n-form-item label="结果">
          <n-select v-model:value="result" :options="RESULT_OPTIONS" style="width: 100%" />
        </n-form-item>
        <n-form-item label="触发锁定">
          <n-select v-model:value="locked" :options="LOCKED_OPTIONS" style="width: 100%" />
        </n-form-item>
        <n-form-item label="开始时间">
          <n-date-picker v-model:value="fromTs" type="datetime" clearable style="width: 100%" />
        </n-form-item>
        <n-form-item label="结束时间">
          <n-date-picker v-model:value="toTs" type="datetime" clearable style="width: 100%" />
        </n-form-item>
      </FormGrid>
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
          <span>暂无登录日志</span>
        </template>
      </DataTable>
    </PageCard>
  </div>
</template>

<style scoped>
.security-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar-hint {
  font-size: 12px;
  color: var(--n-text-color-3, var(--fms-text-muted));
  margin-bottom: 8px;
}
.key-prefix {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}
.issue-hint {
  margin-bottom: 12px;
}
.key-result {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--n-color, #fafafa);
  border: 1px dashed var(--n-border-color, #d9d9d9);
  border-radius: 6px;
  padding: 10px 12px;
}
html.dark .key-result {
  background: var(--n-color, #1e1e1e);
}
.key-text {
  flex: 1;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
  word-break: break-all;
  user-select: all;
}
.filter-grid {
  background: var(--n-color, #fff);
  border-radius: 8px;
  padding: 12px 16px 0;
  margin-bottom: 4px;
}
html.dark .filter-grid {
  background: var(--n-color, #1e1e1e);
}
</style>
