<script setup lang="ts">
/**
 * 流程定义管理，admin 专属，web 配置审批节点编排，DRAFT 可编辑，
 * 状态流 DRAFT 启用 ACTIVE，可停用 DISABLED，DISABLED 可回草稿（无 RUNNING 实例），
 * 节点行编辑器，node_code/type/assignee_role 可增删排序。
 */
import { h, onMounted, ref } from 'vue'
import {
  NButton,
  NInput,
  NModal,
  NSelect,
  NTag,
  useMessage,
} from 'naive-ui'
import type { TagProps } from 'naive-ui'
import { wfApi } from '@/api/wf'
import { listRoles } from '@/api/org'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { WfDefinitionRequest, WfDefinitionStatus, WfDefinitionView } from '@/types/wf'
import { formatDateTime } from '@/utils/format'

const message = useMessage()
const appStore = useAppStore()

// 列表
const page = ref(1)
const size = ref(20)
const rows = ref<WfDefinitionView[]>([])
const total = ref(0)
const loading = ref(false)

let reqSeq = 0
async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await wfApi.pageDefinitions({
      page: page.value,
      size: size.value,
      companyId: appStore.companyId ?? undefined,
    })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

const STATUS_TAG: Record<WfDefinitionStatus, { label: string; type: TagProps['type'] }> = {
  DRAFT: { label: '草稿', type: 'default' },
  ACTIVE: { label: '已启用', type: 'success' },
  DISABLED: { label: '已停用', type: 'warning' },
}

/** 节点数，node_json 数组长度，非法 JSON 记 0 */
function nodeCount(row: WfDefinitionView): number {
  try {
    const arr = JSON.parse(row.nodeJson)
    return Array.isArray(arr) ? arr.length : 0
  } catch {
    return 0
  }
}

type RowAction = 'edit' | 'activate' | 'disable' | 'revise' | 'delete'

/** 行操作按钮，查看任何状态，编辑仅 DRAFT，删除仅非 ACTIVE */
function renderAction(row: WfDefinitionView) {
  const btns: Array<[string, () => void]> = []
  btns.push(['查看', () => openPreview(row)])
  if (row.status === 'DRAFT') btns.push(['编辑', () => openEditor(row)])
  if (row.status === 'DRAFT') btns.push(['启用', () => run('activate', row.id)])
  if (row.status === 'ACTIVE') btns.push(['停用', () => run('disable', row.id)])
  if (row.status === 'DISABLED') btns.push(['回草稿', () => run('revise', row.id)])
  if (row.status !== 'ACTIVE') btns.push(['删除', () => run('delete', row.id)])
  return h(
    'div',
    { style: 'white-space: nowrap' },
    btns.map(([label, fn]) =>
      h(
        NButton,
        { text: true, type: label === '删除' ? 'error' : 'primary', size: 'small', style: 'margin-right:8px', onClick: fn },
        { default: () => label },
      ),
    ),
  )
}

async function run(action: RowAction, id: string) {
  try {
    if (action === 'delete') await wfApi.deleteDefinition(id)
    if (action === 'activate') await wfApi.activateDefinition(id)
    if (action === 'disable') await wfApi.disableDefinition(id)
    if (action === 'revise') await wfApi.reviseDefinition(id)
    message.success('操作成功')
    void load()
  } catch (e: any) {
    message.error(e?.message ?? '操作失败')
  }
}

const columns: FmsColumn<WfDefinitionView>[] = [
  { key: 'code', title: '流程编码', width: 150 },
  { key: 'name', title: '流程名称', width: 160 },
  { key: 'version', title: '版本', width: 70, render: (r) => r.version },
  { key: 'formType', title: '业务类型', width: 130, hideBelow: 'md', render: (r) => r.formType ?? '—' },
  { key: 'nodeCount', title: '节点数', width: 80, render: (r) => nodeCount(r) },
  {
    key: 'status',
    title: '状态',
    width: 90,
    render: (r) => {
      const t = STATUS_TAG[r.status]
      return h(NTag, { type: t.type, size: 'small', bordered: false }, { default: () => t.label })
    },
  },
  { key: 'updatedAt', title: '更新时间', width: 150, hideBelow: 'sm', render: (r) => formatDateTime(r.updatedAt) },
  { key: 'actions', title: '操作', width: 200, align: 'center', render: (r) => renderAction(r) },
]

// 角色下拉（assignee_role 候选，applicant = 申请人）
type NodeRow = { node_code: string; type: 'START' | 'APPROVE' | 'END'; assignee_role: string }

const showEditor = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const formCode = ref('')
const formName = ref('')
const formType = ref('')
const nodeRows = ref<NodeRow[]>([])

const roleOptions = ref<Array<{ label: string; value: string }>>([{ label: '申请人', value: 'applicant' }])

async function loadRoles() {
  try {
    const roles = await listRoles()
    const codes = roles.map((r) => ({ label: r.name || r.code, value: r.code }))
    roleOptions.value = [{ label: '申请人', value: 'applicant' }, ...codes]
  } catch {
    /* 角色列表拉取失败不阻塞编辑 */
  }
}

const TYPE_OPTIONS = [
  { label: '开始', value: 'START' },
  { label: '审批', value: 'APPROVE' },
  { label: '结束', value: 'END' },
]

function newNodeRow(): NodeRow {
  return { node_code: '', type: 'APPROVE', assignee_role: '' }
}

// 只读预览，任意状态均可查看节点编排
const showPreview = ref(false)
const previewRow = ref<WfDefinitionView | null>(null)
const previewNodes = ref<NodeRow[]>([])

function typeLabel(t: NodeRow['type']): string {
  return TYPE_OPTIONS.find((o) => o.value === t)?.label ?? t
}

function openPreview(row: WfDefinitionView) {
  previewRow.value = row
  previewNodes.value = []
  try {
    const arr = JSON.parse(row.nodeJson)
    if (Array.isArray(arr)) {
      previewNodes.value = arr.map((n: any) => ({
        node_code: String(n.node_code ?? ''),
        type: (['START', 'APPROVE', 'END'].includes(n.type) ? n.type : 'APPROVE') as NodeRow['type'],
        assignee_role: String(n.assignee_role ?? ''),
      }))
    }
  } catch {
    /* 非法 JSON 展示为空 */
  }
  showPreview.value = true
}

function openEditor(row?: WfDefinitionView) {
  editingId.value = row ? row.id : null
  formCode.value = row ? row.code : ''
  formName.value = row ? row.name : ''
  formType.value = row?.formType ?? ''
  nodeRows.value = []
  if (row) {
    try {
      const arr = JSON.parse(row.nodeJson)
      if (Array.isArray(arr)) {
        nodeRows.value = arr.map((n: any) => ({
          node_code: String(n.node_code ?? ''),
          type: (['START', 'APPROVE', 'END'].includes(n.type) ? n.type : 'APPROVE') as NodeRow['type'],
          assignee_role: String(n.assignee_role ?? ''),
        }))
      }
    } catch {
      /* 非法 JSON 从空编辑 */
    }
  }
  if (!row || !nodeRows.value.length) {
    nodeRows.value = [newNodeRow()]
  }
  showEditor.value = true
}

function validateRows(): string | null {
  const codes = new Set<string>()
  let start = 0
  let end = 0
  let approve = 0
  for (const r of nodeRows.value) {
    const c = (r.node_code || '').trim()
    if (!c) return '节点编码不能为空'
    if (codes.has(c)) return `节点编码重复：${c}`
    codes.add(c)
    if (r.type === 'START') start++
    if (r.type === 'END') end++
    if (r.type === 'APPROVE') approve++
    if (r.type === 'APPROVE' && !r.assignee_role) return `审批节点 ${c} 未指定处理人`
  }
  if (start !== 1) return '须有且仅有一个开始节点'
  if (end !== 1) return '须有且仅有一个结束节点'
  if (approve < 1) return '须至少一个审批节点'
  return null
}

async function save() {
  if (!formName.value.trim()) {
    message.error('请填写流程名称')
    return
  }
  const err = validateRows()
  if (err) {
    message.error(err)
    return
  }
  const req: WfDefinitionRequest = {
    companyId: appStore.companyId ?? '',
    code: formCode.value.trim(),
    name: formName.value.trim(),
    formType: formType.value.trim() || undefined,
    nodeJson: JSON.stringify(nodeRows.value.map((r) => ({ node_code: r.node_code.trim(), type: r.type, assignee_role: r.assignee_role }))),
  }
  if (!req.companyId) {
    message.error('请先选择公司')
    return
  }
  saving.value = true
  try {
    if (editingId.value) await wfApi.updateDefinition(editingId.value, req)
    else await wfApi.createDefinition(req)
    message.success('保存成功')
    showEditor.value = false
    void load()
  } catch (e: any) {
    message.error(e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

function moveNode(i: number, dir: -1 | 1) {
  const j = i + dir
  if (j < 0 || j >= nodeRows.value.length) return
  const t = nodeRows.value[i]
  nodeRows.value[i] = nodeRows.value[j]
  nodeRows.value[j] = t
}

function removeNode(i: number) {
  nodeRows.value.splice(i, 1)
}

onMounted(() => {
  void load()
  void loadRoles()
})
</script>

<template>
  <div class="def-list">
    <div class="toolbar">
      <span class="toolbar-title">流程定义</span>
      <div class="toolbar-actions">
        <n-button size="small" @click="load">刷新</n-button>
        <n-button size="small" type="primary" @click="openEditor()">新增流程</n-button>
      </div>
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
        <span>暂无流程定义，点右上角新增流程开始配置</span>
      </template>
    </DataTable>

    <!-- 定义编辑弹窗，节点行编辑器 -->
    <n-modal v-model:show="showEditor" preset="card" :title="editingId ? '编辑流程' : '新增流程'" style="max-width: 720px">
      <div class="editor">
        <FormGrid>
          <n-form-item label="流程名称">
            <n-input v-model:value="formName" placeholder="如 差旅报销审批" />
          </n-form-item>
          <n-form-item label="流程编码">
            <n-input v-model:value="formCode" placeholder="如 expense_reimburse" :disabled="!!editingId" />
          </n-form-item>
          <n-form-item label="业务类型">
            <n-input v-model:value="formType" placeholder="如 REIMBURSEMENT，发起时对应业务类型" clearable />
          </n-form-item>
        </FormGrid>

        <div class="node-title">审批节点编排</div>
        <div class="node-rows">
          <div v-for="(r, i) in nodeRows" :key="i" class="node-row">
            <n-input v-model:value="r.node_code" size="small" placeholder="节点编码" style="width: 130px" />
            <n-select v-model:value="r.type" size="small" :options="TYPE_OPTIONS" style="width: 110px" />
            <n-select
              v-model:value="r.assignee_role"
              size="small"
              :options="roleOptions"
              clearable
              filterable
              placeholder="处理人角色"
              :disabled="r.type !== 'APPROVE'"
              style="flex: 1"
            />
            <n-button text type="primary" size="small" :disabled="i === 0" @click="moveNode(i, -1)">上移</n-button>
            <n-button text type="primary" size="small" :disabled="i === nodeRows.length - 1" @click="moveNode(i, 1)">下移</n-button>
            <n-button text type="error" size="small" @click="removeNode(i)">删除</n-button>
          </div>
          <n-button size="small" dashed block style="margin-top: 8px" @click="nodeRows.push(newNodeRow())">+ 添加节点</n-button>
        </div>
      </div>
      <template #footer>
        <div class="editor-footer">
          <n-button size="small" @click="showEditor = false">取消</n-button>
          <n-button size="small" type="primary" :loading="saving" @click="save">保存草稿</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 流程只读预览，任意状态均可查看节点编排 -->
    <n-modal v-model:show="showPreview" preset="card" :title="`流程详情 · ${previewRow?.name ?? ''}`" style="max-width: 640px">
      <template v-if="previewRow">
        <div class="preview-head">
          <span class="preview-item">编码：{{ previewRow.code }}</span>
          <span class="preview-item">版本：{{ previewRow.version }}</span>
          <span class="preview-item">状态：{{ STATUS_TAG[previewRow.status].label }}</span>
          <span v-if="previewRow.formType" class="preview-item">业务类型：{{ previewRow.formType }}</span>
        </div>
        <div class="node-title">审批节点编排（共 {{ previewNodes.length }} 个）</div>
        <div class="node-rows">
          <div v-for="(r, i) in previewNodes" :key="i" class="node-row">
            <span class="preview-node-code">{{ r.node_code }}</span>
            <span class="preview-node-type">{{ typeLabel(r.type) }}</span>
            <span class="preview-node-role">
              {{ r.type === 'START' ? '申请人发起' : r.type === 'END' ? '流程结束' : r.assignee_role ? '处理人：' + r.assignee_role : '未指定处理人' }}
            </span>
          </div>
          <div v-if="!previewNodes.length" class="preview-empty">暂无节点编排</div>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.def-list {
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
.toolbar-actions {
  display: flex;
  gap: 8px;
}
.editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.node-title {
  font-size: 13px;
  color: var(--fms-text-muted, #666);
}
.node-rows {
  display: flex;
  flex-direction: column;
}
.node-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}
.editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.preview-head {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-bottom: 12px;
  font-size: 13px;
}
.preview-node-code {
  font-weight: 600;
  width: 130px;
}
.preview-node-type {
  width: 60px;
  color: var(--fms-text-muted, #666);
}
.preview-node-role {
  flex: 1;
  color: var(--fms-text-muted, #666);
}
.preview-empty {
  color: var(--fms-text-muted, #666);
  font-size: 13px;
  padding: 8px 0;
}
</style>
