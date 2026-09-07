<script setup lang="ts">
/**
 * 科目表，左树，listAccounts 经 buildAccountTree 建树 + 右侧详情/编辑区，搜索防抖 300ms
 * 服务端 keyword 过滤，结果保留祖先路径，新增根/子科目、编辑，code/path 禁改、停用/启用、
 * 删除，有子科目/被引用 40900 提示，编码 ^[A-Za-z0-9_]{1,32}$、外币科目 currency 必填。
 */
import { computed, onMounted, ref } from 'vue'
import {
  NButton,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NPopconfirm,
  NSelect,
  NSwitch,
  NTree,
  useMessage,
} from 'naive-ui'
import type { FormInst, FormRules, TreeOption } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import { glApi } from '@/api/gl'
import { useAppStore } from '@/stores/app'
import PageCard from '@/components/common/PageCard.vue'
import { buildAccountTree, isValidAccountCode } from '@/utils/tree'
import type { AccountTreeNode } from '@/utils/tree'
import type { AccountRequest, AccountType, AccountView } from '@/types/gl'
import type { BalanceDirection } from '@/types/gl'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const appStore = useAppStore()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const treeData = ref<TreeOption[]>([])
const selectedId = ref<string | null>(null)
const selected = ref<AccountView | null>(null)
const loading = ref(false)
const keyword = ref('')

/** id 到科目映射，树与搜索结果共用 */
const nodeMap = new Map<string, AccountView>()

async function loadTree(kw?: string) {
  loading.value = true
  try {
    const list = await glApi.listAccounts(appStore.companyId ?? undefined, kw || undefined)
    nodeMap.clear()
    for (const acc of list) nodeMap.set(acc.id, acc)
    treeData.value = toOptions(buildAccountTree(list))
  } finally {
    loading.value = false
  }
}
onMounted(() => void loadTree())

const onSearch = useDebounceFn((v: string) => {
  void loadTree(v)
}, 300)

function toOptions(nodes: AccountTreeNode[]): TreeOption[] {
  return nodes.map((n) => ({
    key: n.id,
    label: `${n.code} ${n.name}`,
    isLeaf: n.isLeaf,
    children: n.children && n.children.length ? toOptions(n.children) : undefined,
  }))
}

function onSelect(keys: Array<string | number>) {
  selectedId.value = String(keys[0] ?? '')
  selected.value = nodeMap.get(selectedId.value) ?? null
  if (selectedId.value) void loadDetail(selectedId.value)
}

async function loadDetail(id: string) {
  const acc = await glApi.getAccount(id)
  if (acc) {
    nodeMap.set(id, acc)
    selected.value = acc
  }
}

// 新增/编辑表单
const showForm = ref(false)
const formRef = ref<FormInst | null>(null)
const formLoading = ref(false)
const editing = ref(false)
const form = ref({
  code: '',
  name: '',
  parentId: null as string | null,
  accountType: 'ASSET' as AccountType,
  direction: 'DEBIT' as BalanceDirection,
  isForeignCurrency: false,
  currency: '',
  status: 'ACTIVE' as 'ACTIVE' | 'DISABLED',
})

const TYPE_OPTIONS = [
  { label: '资产 ASSET', value: 'ASSET' },
  { label: '负债 LIABILITY', value: 'LIABILITY' },
  { label: '权益 EQUITY', value: 'EQUITY' },
  { label: '收入 REVENUE', value: 'REVENUE' },
  { label: '费用 EXPENSE', value: 'EXPENSE' },
]
const DIR_OPTIONS = [
  { label: '借 DEBIT', value: 'DEBIT' },
  { label: '贷 CREDIT', value: 'CREDIT' },
]
const STATUS_OPTIONS = [
  { label: '启用', value: 'ACTIVE' },
  { label: '停用', value: 'DISABLED' },
]

function openCreate(parent: AccountView | null) {
  editing.value = false
  form.value = {
    code: '',
    name: '',
    parentId: parent?.id ?? null,
    accountType: parent?.accountType ?? 'ASSET',
    direction: parent?.direction ?? 'DEBIT',
    isForeignCurrency: false,
    currency: '',
    status: 'ACTIVE',
  }
  showForm.value = true
}
function openEdit(acc: AccountView) {
  editing.value = true
  form.value = {
    code: acc.code,
    name: acc.name,
    parentId: acc.parentId,
    accountType: acc.accountType,
    direction: acc.direction,
    isForeignCurrency: acc.isForeignCurrency,
    currency: acc.currency ?? '',
    status: acc.status,
  }
  showForm.value = true
}

const rules: FormRules = {
  code: [
    {
      validator: (_r, v) => isValidAccountCode(String(v)),
      message: '编码仅允许字母/数字/下划线，最长 32 字符',
      trigger: 'blur',
    },
  ],
  name: [{ required: true, message: '科目名称必填', trigger: 'blur' }],
  accountType: [{ required: true, message: '科目类型必填' }],
  direction: [{ required: true, message: '余额方向必填' }],
  currency: [
    {
      validator: (_r, v) => !form.value.isForeignCurrency || /^[A-Z]{3}$/.test(String(v)),
      message: '外币科目币种必填（3 位 ISO 代码）',
      trigger: 'blur',
    },
  ],
}

async function submitForm() {
  if (!formRef.value) return
  const ok = await formRef.value
    .validate()
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  formLoading.value = true
  try {
    const req: AccountRequest = {
      companyId: appStore.companyId ?? undefined,
      code: form.value.code,
      name: form.value.name,
      parentId: form.value.parentId,
      accountType: form.value.accountType,
      direction: form.value.direction,
      isForeignCurrency: form.value.isForeignCurrency,
      currency: form.value.isForeignCurrency ? form.value.currency : undefined,
      status: form.value.status,
    }
    if (editing.value && selected.value) {
      await glApi.updateAccount(selected.value.id, req)
      message.success('科目已更新')
    } else {
      await glApi.createAccount(req)
      message.success('科目已创建')
    }
    showForm.value = false
    await loadTree()
    if (selectedId.value) await loadDetail(selectedId.value)
  } catch {
    // 40900，重码/被引用等错误 http.ts 已全局提示
  } finally {
    formLoading.value = false
  }
}

async function toggleStatus() {
  const a = selected.value
  if (!a) return
  const next = a.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const req: AccountRequest = {
    companyId: a.companyId,
    code: a.code,
    name: a.name,
    parentId: a.parentId,
    accountType: a.accountType,
    direction: a.direction,
    isForeignCurrency: a.isForeignCurrency,
    currency: a.currency ?? undefined,
    status: next,
  }
  await glApi.updateAccount(a.id, req)
  message.success(next === 'ACTIVE' ? '科目已启用' : '科目已停用')
  await loadTree()
  await loadDetail(a.id)
}

async function onDelete() {
  const a = selected.value
  if (!a) return
  await glApi.deleteAccount(a.id)
  message.success('科目已删除')
  selectedId.value = null
  selected.value = null
  await loadTree()
}

/** 详情字段展示 */
const detailRows = computed(() => {
  const a = selected.value
  if (!a) return []
  return [
    { label: '编码', value: a.code },
    { label: '名称', value: a.name },
    { label: '类型', value: a.accountType },
    { label: '余额方向', value: a.direction === 'DEBIT' ? '借' : '贷' },
    { label: '外币科目', value: a.isForeignCurrency ? '是' : '否' },
    { label: '币种', value: a.currency ?? '—' },
    { label: '状态', value: a.status === 'ACTIVE' ? '启用' : '停用' },
    { label: '末级', value: a.isLeaf ? '是' : '否' },
  ]
})
</script>

<template>
  <div class="account-tree">
    <!-- 左，科目树，手机全宽 40vh 内滚 -->
    <div class="tree-pane">
      <div class="tree-toolbar">
        <n-input
          v-model:value="keyword"
          placeholder="搜索编码 / 名称"
          clearable
          size="small"
          @update:value="onSearch"
        />
        <n-button v-if="canWrite" size="small" type="primary" @click="openCreate(null)">新增根科目</n-button>
      </div>
      <n-tree
        :data="treeData"
        :selected-keys="selectedId ? [selectedId] : []"
        block-line
        expand-on-click
        :loading="loading"
        class="tree-scroll"
        @update:selected-keys="onSelect"
      />
    </div>

    <!-- 右，详情/编辑 -->
    <PageCard :title="selected ? `${selected.code} ${selected.name}` : '科目详情'" :loading="loading" class="detail-pane">
      <template #extra>
        <n-button v-if="canWrite" size="tiny" :disabled="!selected" @click="selected ? openEdit(selected) : undefined">编辑</n-button>
      </template>
      <n-empty v-if="!selected" description="请选择左侧科目" />
      <div v-else class="detail-grid">
        <div v-for="row in detailRows" :key="row.label" class="detail-item">
          <label>{{ row.label }}</label>
          <span>{{ row.value }}</span>
        </div>
      </div>
      <div v-if="selected" class="detail-actions">
        <n-button v-if="canWrite" size="small" @click="openCreate(selected)">新增子科目</n-button>
        <n-button v-if="canWrite" size="small" @click="toggleStatus">
          {{ selected.status === 'ACTIVE' ? '停用' : '启用' }}
        </n-button>
        <n-popconfirm v-if="canWrite" @positive-click="onDelete">
          <template #trigger>
            <n-button size="small" type="error">删除</n-button>
          </template>
          删除科目需无子科目且未被凭证引用，确认删除？
        </n-popconfirm>
      </div>
    </PageCard>
  </div>

  <!-- 新增/编辑弹窗，编辑模式 code 禁改 -->
  <n-modal
    v-model:show="showForm"
    preset="card"
    :title="editing ? '编辑科目' : '新增科目'"
    style="width: min(480px, calc(100vw - 32px))"
    :bordered="false"
  >
    <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" size="small">
      <n-form-item label="科目编码" path="code">
        <n-input v-model:value="form.code" :disabled="editing" placeholder="字母/数字/下划线，≤32" />
      </n-form-item>
      <n-form-item label="科目名称" path="name">
        <n-input v-model:value="form.name" maxlength="128" />
      </n-form-item>
      <n-form-item label="科目类型" path="accountType">
        <n-select v-model:value="form.accountType" :options="TYPE_OPTIONS" />
      </n-form-item>
      <n-form-item label="余额方向" path="direction">
        <n-select v-model:value="form.direction" :options="DIR_OPTIONS" />
      </n-form-item>
      <n-form-item label="外币科目" path="isForeignCurrency">
        <n-switch v-model:value="form.isForeignCurrency" />
      </n-form-item>
      <n-form-item v-if="form.isForeignCurrency" label="币种（ISO 3 位）" path="currency">
        <n-input v-model:value="form.currency" maxlength="3" placeholder="如 USD" />
      </n-form-item>
      <n-form-item v-if="editing" label="状态" path="status">
        <n-select v-model:value="form.status" :options="STATUS_OPTIONS" />
      </n-form-item>
    </n-form>
    <template #action>
      <n-button size="small" @click="showForm = false">取消</n-button>
      <n-button v-if="canWrite" size="small" type="primary" :loading="formLoading" @click="submitForm">保存</n-button>
    </template>
  </n-modal>
</template>

<style scoped>
/* 桌面，左 320px 树 + 右详情，手机，上下堆叠 */
.account-tree {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}
@media (min-width: 768px) {
  .account-tree {
    grid-template-columns: 320px 1fr;
    align-items: start;
  }
}
.tree-pane {
  padding: 12px;
  border-radius: 8px;
  background: var(--n-color, #fff);
}
html.dark .tree-pane {
  background: var(--n-color, #1e1e1e);
}
.tree-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.tree-scroll {
  max-height: 40vh;
  overflow: auto;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 8px 16px;
}
.detail-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
}
.detail-item label {
  color: var(--fms-text-muted);
  font-size: 12px;
}
.detail-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  flex-wrap: wrap;
}
</style>
