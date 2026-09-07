<script setup lang="ts">
/**
 * 角色与权限，角色列表 + 新建/编辑/删除，is_system 禁改禁删，40300 由后端兜底，
 * 授权弹窗逐条 grantRolePermission，后端无角色已授权查询端点，仅增量授予。
 */
import { h, onMounted, ref, useTemplateRef } from 'vue'
import { NButton, NPopconfirm, NTag, useMessage } from 'naive-ui'
import type { FormInst, FormRules, TreeOption } from 'naive-ui'
import { adminApi } from '@/api/admin'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { ModuleNode, RoleView } from '@/types/admin'

const message = useMessage()

const rows = ref<RoleView[]>([])
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function loadRoles() {
  const seq = ++reqSeq
  loading.value = true
  try {
    rows.value = await adminApi.listRoles()
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

// 新建 / 编辑弹窗
const showForm = ref(false)
const formRef = useTemplateRef<FormInst>('formRef')
const formLoading = ref(false)
const editing = ref(false)
const form = ref<{ id: string; code: string; name: string }>({ id: '', code: '', name: '' })

const rules: FormRules = {
  code: [{ required: true, message: '角色编码必填（小写字母/数字/下划线）', trigger: ['blur'] }],
  name: [{ required: true, message: '角色名称必填', trigger: ['blur'] }],
}

function openCreate() {
  editing.value = false
  form.value = { id: '', code: '', name: '' }
  showForm.value = true
}
function openEdit(r: RoleView) {
  editing.value = true
  form.value = { id: r.id, code: r.code, name: r.name }
  showForm.value = true
}

async function submitForm() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  formLoading.value = true
  try {
    if (editing.value) await adminApi.updateRole(form.value.id, { code: form.value.code, name: form.value.name })
    else await adminApi.createRole({ code: form.value.code, name: form.value.name })
    message.success(editing.value ? '已保存' : '已创建')
    showForm.value = false
    await loadRoles()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    formLoading.value = false
  }
}

async function onDelete(id: string) {
  await adminApi.deleteRole(id)
  message.success('已删除')
  await loadRoles()
}

// 授权，perm-tree 勾选授予
const showPerms = ref(false)
const permRole = ref<RoleView | null>(null)
const treeData = ref<TreeOption[]>([])
const checkedKeys = ref<string[]>([])
const grantLoading = ref(false)

/** 模块树转 n-tree 数据，权限挂为叶子节点，key 前缀 p: 与模块节点区分 */
function toTreeOptions(nodes: ModuleNode[]): TreeOption[] {
  return nodes.map((n) => ({
    key: n.id,
    label: n.name,
    children: [
      ...n.permissions.map((p) => ({ key: `p:${p.code}`, label: `${p.code} ${p.name}` })),
      ...toTreeOptions(n.children ?? []),
    ],
  }))
}

async function openPerms(r: RoleView) {
  permRole.value = r
  checkedKeys.value = []
  treeData.value = toTreeOptions(await adminApi.permTree())
  showPerms.value = true
}

async function submitPerms() {
  if (!permRole.value) return
  // 仅增量授予，取消勾选不回收，后端无已授权查询无法安全 diff
  const codes = checkedKeys.value.filter((k) => k.startsWith('p:')).map((k) => k.slice(2))
  grantLoading.value = true
  try {
    for (const code of codes) await adminApi.grantRolePermission(permRole.value.id, code)
    message.success(`已授予 ${codes.length} 个权限`)
    showPerms.value = false
    await loadRoles()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    grantLoading.value = false
  }
}

const columns: FmsColumn<RoleView>[] = [
  { key: 'code', title: '角色编码', width: 130 },
  { key: 'name', title: '角色名称', width: 160 },
  {
    key: 'isSystem',
    title: '内置',
    width: 80,
    render: (r) =>
      r.isSystem
        ? h(NTag, { type: 'primary', size: 'small', bordered: false }, { default: () => '系统' })
        : h(NTag, { size: 'small', bordered: false }, { default: () => '自定义' }),
  },
  { key: 'permissionCount', title: '权限数', width: 90, align: 'right' },
  {
    key: 'actions',
    title: '操作',
    width: 150,
    align: 'center',
    render: (r) =>
      h(
        'span',
        { style: 'display:inline-flex;gap:8px' },
        [
          h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openPerms(r) }, { default: () => '授权' }),
          h(
            NButton,
            { text: true, size: 'small', disabled: r.isSystem, onClick: () => openEdit(r) },
            { default: () => '编辑' },
          ),
          h(
            NPopconfirm,
            { onPositiveClick: () => onDelete(r.id) },
            {
              trigger: () =>
                h(NButton, { text: true, type: 'error', size: 'small', disabled: r.isSystem }, { default: () => '删除' }),
              default: () => '确认删除该角色？',
            },
          ),
        ],
      ),
  },
]

onMounted(() => void loadRoles())
</script>

<template>
  <div class="role-list">
    <div class="toolbar">
      <span class="toolbar-title">角色与权限</span>
      <n-button type="primary" size="small" @click="openCreate">新建角色</n-button>
    </div>

    <DataTable :columns="columns" :data="rows" :loading="loading" :total="rows.length" :scroll-x="700" :local-pagination="true">
      <template #empty>
        <span>暂无角色数据</span>
      </template>
    </DataTable>

    <!-- 新建 / 编辑角色 -->
    <n-modal v-model:show="showForm" preset="card" :title="editing ? '编辑角色' : '新建角色'" style="width: min(420px, calc(100vw - 32px))" :bordered="false">
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" size="small">
        <n-form-item label="角色编码" path="code">
          <n-input v-model:value="form.code" maxlength="32" :disabled="editing" placeholder="小写字母/数字/下划线，唯一" />
        </n-form-item>
        <n-form-item label="角色名称" path="name">
          <n-input v-model:value="form.name" maxlength="64" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showForm = false">取消</n-button>
        <n-button size="small" type="primary" :loading="formLoading" @click="submitForm">保存</n-button>
      </template>
    </n-modal>

    <!-- 授权 -->
    <n-modal v-model:show="showPerms" preset="card" :title="`授权 - ${permRole?.name ?? ''}`" style="width: min(520px, calc(100vw - 32px))" :bordered="false">
      <n-alert type="info" :bordered="false" class="perm-hint">
        勾选即授予；取消勾选不回收（后端未提供角色已授权查询端点）。
      </n-alert>
      <n-tree :data="treeData" checkable cascade default-expand-all v-model:checked-keys="checkedKeys" />
      <template #action>
        <n-button size="small" @click="showPerms = false">取消</n-button>
        <n-button size="small" type="primary" :loading="grantLoading" @click="submitPerms">保存授权</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.role-list {
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
.perm-hint {
  margin-bottom: 8px;
}
</style>
