<script setup lang="ts">
/**
 * 用户管理，关键词/状态筛选 + 服务端分页，新建，初始密码，companyId 取顶栏当前公司、
 * 禁用/启用，status 白名单 ACTIVE/LOCKED/DISABLED、重置密码、重置双因子（只吊销不接管，
 * 原因必填并留审计）、分配角色，按 roleId 幂等、数据范围全量替换，邮箱列由后端按身份
 * 下发，管理员见全部，其余账号仅见本人。
 */
import { computed, h, onMounted, ref, useTemplateRef, watch } from 'vue'
import { NButton, NPopconfirm, NTag, useMessage } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import { adminApi } from '@/api/admin'
import { pageCompanies, pageDepartments } from '@/api/org'
import { useAppStore } from '@/stores/app'
import { useConfigStore } from '@/stores/config'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { UserStatus, UserView } from '@/types/admin'
import { formatDateTime } from '@/utils/format'

const message = useMessage()
const appStore = useAppStore()
const configStore = useConfigStore()
void configStore.load() // 密码长度边界经 /api/config 下发，前端快速校验跟随，权威兜底在后端

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '启用', value: 'ACTIVE' },
  { label: '锁定', value: 'LOCKED' },
  { label: '停用', value: 'DISABLED' },
]

// 筛选状态
const keyword = ref('')
const status = ref<UserStatus | ''>('')
const page = ref(1)
const size = ref(20)
const rows = ref<UserView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await adminApi.pageUsers({
      page: page.value,
      size: size.value,
      companyId: appStore.companyId ?? undefined,
      keyword: keyword.value || undefined,
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
const onKeywordSearch = useDebounceFn(onFilterChange, 500)
watch(keyword, () => onKeywordSearch())
watch(status, onFilterChange)
watch([page, size, () => appStore.companyId], () => void load())

// 新建用户弹窗
const showCreate = ref(false)
const createRef = useTemplateRef<FormInst>('createRef')
const createLoading = ref(false)
const createForm = ref<{ loginName: string; password: string; displayName: string; email: string; mustChangePwd: boolean }>({
  loginName: '',
  password: '',
  displayName: '',
  email: '',
  mustChangePwd: true,
})

/** 密码长度提示，随 fms.security.password-min/max-length 配置变化 */
const pwdLengthMsg = computed(
  () => `密码长度须在 ${configStore.cfg.passwordMinLength}-${configStore.cfg.passwordMaxLength} 位之间`,
)
const createRules = computed<FormRules>(() => ({
  loginName: [{ required: true, message: '登录名必填', trigger: ['blur'] }],
  password: [
    { required: true, message: '初始密码必填', trigger: ['blur'] },
    {
      min: configStore.cfg.passwordMinLength,
      max: configStore.cfg.passwordMaxLength,
      message: pwdLengthMsg.value,
      trigger: ['blur'],
    },
  ],
  email: [{ type: 'email', message: '邮箱格式错误', trigger: ['blur'] }],
}))

function openCreate() {
  createForm.value = { loginName: '', password: '', displayName: '', email: '', mustChangePwd: true }
  showCreate.value = true
}

async function submitCreate() {
  try {
    await createRef.value?.validate()
  } catch {
    return
  }
  if (!appStore.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  createLoading.value = true
  try {
    await adminApi.createUser({
      loginName: createForm.value.loginName,
      password: createForm.value.password,
      displayName: createForm.value.displayName || undefined,
      email: createForm.value.email || undefined,
      companyId: appStore.companyId,
      mustChangePwd: createForm.value.mustChangePwd,
    })
    message.success('用户已创建')
    showCreate.value = false
    await load()
  } catch (e) {
    // 错误已由 http 拦截器统一提示，本地仅兜底
    if (e instanceof Error) message.error(e.message)
  } finally {
    createLoading.value = false
  }
}

// 禁用 / 启用
async function onToggleStatus(r: UserView) {
  await adminApi.updateUser(r.id, { status: r.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' })
  message.success(r.status === 'ACTIVE' ? '已停用' : '已启用')
  await load()
}

// 重置密码弹窗
const showResetPwd = ref(false)
const resetUser = ref<UserView | null>(null)
const newPassword = ref('')
const resetLoading = ref(false)

function openResetPwd(r: UserView) {
  resetUser.value = r
  newPassword.value = ''
  showResetPwd.value = true
}

async function submitResetPwd() {
  if (!resetUser.value) return
  const { passwordMinLength, passwordMaxLength } = configStore.cfg
  if (newPassword.value.length < passwordMinLength || newPassword.value.length > passwordMaxLength) {
    message.warning(pwdLengthMsg.value)
    return
  }
  resetLoading.value = true
  try {
    await adminApi.resetPassword(resetUser.value.id, newPassword.value)
    message.success('密码已重置（下次登录须改密）')
    showResetPwd.value = false
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    resetLoading.value = false
  }
}

// 重置双因子弹窗，只吊销不接管，吊销后本人下次登录须重新扫码登记，原因必填
const showResetMfa = ref(false)
const mfaUser = ref<UserView | null>(null)
const mfaReason = ref('')
const mfaLoading = ref(false)

function openResetMfa(r: UserView) {
  mfaUser.value = r
  mfaReason.value = ''
  showResetMfa.value = true
}

async function submitResetMfa() {
  if (!mfaUser.value) return
  if (!mfaReason.value.trim()) {
    message.warning('请填写重置原因')
    return
  }
  mfaLoading.value = true
  try {
    await adminApi.resetUserMfa(mfaUser.value.id, mfaReason.value.trim())
    message.success('双因子已重置（下次登录须重新登记）')
    showResetMfa.value = false
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    mfaLoading.value = false
  }
}

// 分配角色弹窗
const showAssign = ref(false)
const assignUser = ref<UserView | null>(null)
const assignRoleId = ref<string | null>(null)
const assignLoading = ref(false)
const roleOptions = computed(() => roles.map((r) => ({ label: `${r.name}（${r.code}）`, value: r.id })))

let roles: { id: string; code: string; name: string }[] = []

async function openAssign(r: UserView) {
  assignUser.value = r
  assignRoleId.value = null
  showAssign.value = true
}

async function submitAssign() {
  if (!assignUser.value || !assignRoleId.value) return
  assignLoading.value = true
  try {
    await adminApi.assignRole(assignUser.value.id, assignRoleId.value)
    message.success('角色已分配')
    showAssign.value = false
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    assignLoading.value = false
  }
}

// 数据范围弹窗，公司范围与部门范围均全量替换，部门含子树开关统一施加
const showScope = ref(false)
const scopeUser = ref<UserView | null>(null)
const scopeLoading = ref(false)
const scopeSaving = ref(false)
const scopeOrgIds = ref<string[]>([])
const scopeDeptIds = ref<string[]>([])
const scopeSubtree = ref(true)
const companyOptions = ref<{ label: string; value: string }[]>([])
const deptOptions = ref<{ label: string; value: string }[]>([])

async function openScope(r: UserView) {
  scopeUser.value = r
  scopeOrgIds.value = []
  scopeDeptIds.value = []
  scopeSubtree.value = true
  showScope.value = true
  scopeLoading.value = true
  try {
    const [companies, depts, cur] = await Promise.all([
      pageCompanies({ page: 1, size: 200 }),
      pageDepartments({ page: 1, size: 500, companyId: appStore.companyId ?? undefined }),
      adminApi.getUserScopes(r.id),
    ])
    companyOptions.value = companies.items.map((c) => ({ label: `${c.name}（${c.code}）`, value: c.id }))
    deptOptions.value = depts.items.map((d) => ({ label: d.name, value: d.id }))
    scopeOrgIds.value = cur.orgIds
    scopeDeptIds.value = cur.deptIds
    scopeSubtree.value = cur.includeSubtree
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    scopeLoading.value = false
  }
}

async function submitScope() {
  if (!scopeUser.value) return
  scopeSaving.value = true
  try {
    await adminApi.setUserScopes(scopeUser.value.id, {
      orgIds: scopeOrgIds.value,
      deptIds: scopeDeptIds.value,
      includeSubtree: scopeSubtree.value,
    })
    message.success('数据范围已保存')
    showScope.value = false
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    scopeSaving.value = false
  }
}

/** 用户状态标签，LOCKED 不在公共 StatusTag 映射内，本地补锁定 */
function renderStatus(s: UserStatus) {
  if (s === 'LOCKED') return h(NTag, { type: 'warning', size: 'small', bordered: false }, { default: () => '锁定' })
  return h(StatusTag, { status: s })
}

const columns: FmsColumn<UserView>[] = [
  { key: 'loginName', title: '登录名', width: 120 },
  { key: 'displayName', title: '显示名', width: 120, render: (r) => r.displayName || '—' },
  {
    // 邮箱由后端闸口按身份下发，管理员可见全部、其余账号仅可见本人，空值与越权同为 '—'
    key: 'email',
    title: '邮箱',
    width: 200,
    hideBelow: 'md',
    render: (r) => r.email || '—',
  },
  { key: 'status', title: '状态', width: 90, render: (r) => renderStatus(r.status as UserStatus) },
  {
    key: 'mfaEnabled',
    title: '双因子',
    width: 80,
    hideBelow: 'md',
    render: (r) => h('span', r.mfaEnabled ? '已启用' : '未启用'),
  },
  {
    key: 'mustChangePwd',
    title: '待改密',
    width: 90,
    hideBelow: 'md',
    render: (r) => (r.mustChangePwd ? h(NTag, { type: 'warning', size: 'small', bordered: false }, { default: () => '待改密' }) : '否'),
  },
  { key: 'lastLoginAt', title: '最近登录', width: 150, hideBelow: 'sm', render: (r) => formatDateTime(r.lastLoginAt) },
  { key: 'roles', title: '角色', width: 170, render: (r) => (r.roles.length ? r.roles.join('、') : '—') },
  {
    key: 'actions',
    title: '操作',
    width: 240,
    align: 'center',
    render: (r) =>
      h(
        'span',
        { style: 'display:inline-flex;gap:8px' },
        [
          h(NButton, { text: true, size: 'small', onClick: () => openResetPwd(r) }, { default: () => '重置密码' }),
          h(
            NButton,
            {
              text: true,
              type: 'warning',
              size: 'small',
              disabled: !r.mfaEnabled,
              onClick: () => openResetMfa(r),
            },
            { default: () => '重置双因子' },
          ),
          h(NButton, { text: true, size: 'small', onClick: () => openAssign(r) }, { default: () => '分配角色' }),
          h(NButton, { text: true, size: 'small', onClick: () => openScope(r) }, { default: () => '数据范围' }),
          h(
            NPopconfirm,
            {
              onPositiveClick: () => onToggleStatus(r),
              positiveText: r.status === 'ACTIVE' ? '停用' : '启用',
            },
            {
              trigger: () =>
                h(
                  NButton,
                  { text: true, type: r.status === 'ACTIVE' ? 'error' : 'success', size: 'small' },
                  { default: () => (r.status === 'ACTIVE' ? '禁用' : '启用') },
                ),
              default: () => (r.status === 'ACTIVE' ? '确认停用该用户？' : '确认启用该用户？'),
            },
          ),
        ],
      ),
  },
]

onMounted(async () => {
  roles = await adminApi.listRoles()
  void load()
})
</script>

<template>
  <div class="user-list">
    <FormGrid class="filter-grid">
      <n-form-item label="关键词">
        <n-input v-model:value="keyword" placeholder="登录名 / 显示名" clearable />
      </n-form-item>
      <n-form-item label="状态">
        <n-select v-model:value="status" :options="STATUS_OPTIONS" style="width: 100%" />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">用户管理</span>
      <n-button type="primary" size="small" @click="openCreate">新建用户</n-button>
    </div>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="columns"
      :data="rows"
      :loading="loading"
      :total="total"
      :scroll-x="1200"
    >
      <template #empty>
        <span>暂无用户数据</span>
      </template>
    </DataTable>

    <!-- 新建用户 -->
    <n-modal v-model:show="showCreate" preset="card" title="新建用户" style="width: min(480px, calc(100vw - 32px))" :bordered="false">
      <n-form ref="createRef" :model="createForm" :rules="createRules" label-placement="top" size="small">
        <n-form-item label="登录名" path="loginName">
          <n-input v-model:value="createForm.loginName" maxlength="64" placeholder="≤64 字符，全局唯一" />
        </n-form-item>
        <n-form-item label="初始密码" path="password">
          <n-input v-model:value="createForm.password" type="password" show-password-on="click" maxlength="64" placeholder="8-64 位" />
        </n-form-item>
        <n-form-item label="显示名">
          <n-input v-model:value="createForm.displayName" maxlength="128" />
        </n-form-item>
        <n-form-item label="邮箱" path="email">
          <n-input v-model:value="createForm.email" maxlength="128" placeholder="加密存储（可选）" />
        </n-form-item>
        <n-form-item label="公司">
          <n-input :value="appStore.companyName || '未选择公司'" readonly />
        </n-form-item>
        <n-form-item label="首次登录须改密">
          <n-switch v-model:value="createForm.mustChangePwd" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showCreate = false">取消</n-button>
        <n-button size="small" type="primary" :loading="createLoading" @click="submitCreate">创建</n-button>
      </template>
    </n-modal>

    <!-- 重置密码 -->
    <n-modal v-model:show="showResetPwd" preset="card" :title="`重置密码 - ${resetUser?.loginName ?? ''}`" style="width: min(420px, calc(100vw - 32px))" :bordered="false">
      <n-form label-placement="top" size="small">
        <n-form-item label="新密码">
          <n-input v-model:value="newPassword" type="password" show-password-on="click" maxlength="64" placeholder="8-64 位；重置后下次登录须改密" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showResetPwd = false">取消</n-button>
        <n-button size="small" type="primary" :loading="resetLoading" @click="submitResetPwd">重置</n-button>
      </template>
    </n-modal>

    <!-- 重置双因子 -->
    <n-modal v-model:show="showResetMfa" preset="card" :title="`重置双因子 - ${mfaUser?.loginName ?? ''}`" style="width: min(440px, calc(100vw - 32px))" :bordered="false">
      <n-form label-placement="top" size="small">
        <n-form-item label="重置原因（必填，留审计）">
          <n-input v-model:value="mfaReason" type="textarea" maxlength="200" :rows="2" placeholder="吊销该用户现有验证器，本人下次登录须重新扫码登记" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showResetMfa = false">取消</n-button>
        <n-button size="small" type="primary" :loading="mfaLoading" :disabled="!mfaReason.trim()" @click="submitResetMfa">重置</n-button>
      </template>
    </n-modal>

    <!-- 分配角色 -->
    <n-modal v-model:show="showAssign" preset="card" :title="`分配角色 - ${assignUser?.loginName ?? ''}`" style="width: min(420px, calc(100vw - 32px))" :bordered="false">
      <n-form label-placement="top" size="small">
        <n-form-item label="角色">
          <n-select v-model:value="assignRoleId" :options="roleOptions" placeholder="选择角色（幂等分配）" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showAssign = false">取消</n-button>
        <n-button size="small" type="primary" :loading="assignLoading" :disabled="!assignRoleId" @click="submitAssign">分配</n-button>
      </template>
    </n-modal>

    <!-- 数据范围 -->
    <n-modal v-model:show="showScope" preset="card" :title="`数据范围 - ${scopeUser?.loginName ?? ''}`" style="width: min(520px, calc(100vw - 32px))" :bordered="false">
      <n-form label-placement="top" size="small">
        <n-form-item label="可见公司（可多选，含他公司即授予跨公司范围）">
          <n-select
            v-model:value="scopeOrgIds"
            :options="companyOptions"
            :loading="scopeLoading"
            multiple
            filterable
            clearable
            placeholder="选择公司"
          />
        </n-form-item>
        <n-form-item label="可见部门（可多选）">
          <n-select
            v-model:value="scopeDeptIds"
            :options="deptOptions"
            multiple
            filterable
            clearable
            placeholder="选择部门"
          />
        </n-form-item>
        <n-form-item label="部门范围含子树">
          <n-switch v-model:value="scopeSubtree" />
        </n-form-item>
        <n-alert type="info" :bordered="false" :show-icon="false" style="margin-top: 4px">
          保存为全量替换：清单外的公司或部门将被移除；清空表示该维度无可见范围。
        </n-alert>
      </n-form>
      <template #action>
        <n-button size="small" @click="showScope = false">取消</n-button>
        <n-button size="small" type="primary" :loading="scopeSaving" @click="submitScope">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.user-list {
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
