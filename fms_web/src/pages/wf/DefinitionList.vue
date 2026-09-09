<script setup lang="ts">
/**
 * 流程定义管理，admin 专属，web 配置审批节点编排，DRAFT 可编辑，
 * 状态流 DRAFT 启用 ACTIVE，可停用 DISABLED，DISABLED 可回草稿（无 RUNNING 实例），
 * 节点行编辑器，节点编码与类型可增删排序，审批节点配策略与处理人来源。
 */
import { computed, h, onMounted, ref } from 'vue'
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
import { getUser, listRoles, pageUsers } from '@/api/org'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import WfGraph from '@/components/wf/WfGraph.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type {
  WfDefinitionRequest,
  WfDefinitionStatus,
  WfDefinitionView,
  WfGraphStep,
  WfHandlerSource,
  WfNodeDef,
  WfNodeStrategy,
  WfNodeType,
} from '@/types/wf'
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

// 节点行编辑态，START/END 忽略 strategy 与 handlers

/** 处理人来源项，按 source 保留对应取值 */
type HandlerRow = { source: WfHandlerSource; code: string; userId: string }

type NodeRow = { node_code: string; type: WfNodeType; strategy: WfNodeStrategy; handlers: HandlerRow[] }

const TYPE_OPTIONS = [
  { label: '开始', value: 'START' },
  { label: '审批', value: 'APPROVE' },
  { label: '结束', value: 'END' },
]

const STRATEGY_OPTIONS = [
  { label: '单审（一人通过即过）', value: 'approve' },
  { label: '会签（全部通过）', value: 'countersign' },
  { label: '或签（任一通过）', value: 'orsign' },
]

const STRATEGY_LABEL: Record<WfNodeStrategy, string> = {
  approve: '单审',
  countersign: '会签',
  orsign: '或签',
}

const SOURCE_OPTIONS = [
  { label: '申请人', value: 'applicant' },
  { label: '角色', value: 'role' },
  { label: '指定用户', value: 'user' },
]

function newNodeRow(): NodeRow {
  return { node_code: '', type: 'APPROVE', strategy: 'approve', handlers: [newHandlerRow()] }
}

function newHandlerRow(): HandlerRow {
  return { source: 'role', code: '', userId: '' }
}

/** 切换来源清空原取值，避免残留旧值参与保存 */
function onSourceChange(h: HandlerRow) {
  h.code = ''
  h.userId = ''
}

function addHandler(r: NodeRow) {
  r.handlers.push(newHandlerRow())
}

function removeHandler(r: NodeRow, j: number) {
  r.handlers.splice(j, 1)
}

/** 解析 node_json 为编辑行，缺字段回默认值 */
function parseNodes(nodeJson: string): NodeRow[] {
  try {
    const arr = JSON.parse(nodeJson)
    if (!Array.isArray(arr)) return []
    return arr.map((n: any) => {
      const type = (['START', 'APPROVE', 'END'].includes(n?.type) ? n.type : 'APPROVE') as WfNodeType
      const hs = Array.isArray(n?.handlers) ? n.handlers : []
      return {
        node_code: String(n?.node_code ?? ''),
        type,
        strategy: (['approve', 'countersign', 'orsign'].includes(n?.strategy)
          ? n.strategy
          : 'approve') as WfNodeStrategy,
        handlers: hs.length
          ? hs.map((x: any) => ({
              source: (['role', 'user', 'applicant'].includes(x?.source) ? x.source : 'role') as WfHandlerSource,
              code: String(x?.code ?? ''),
              userId: String(x?.userId ?? ''),
            }))
          : [newHandlerRow()],
      }
    })
  } catch {
    return []
  }
}

/** 节点行转 node_json 元素，START/END 只出 node_code 与 type */
function toNodeDef(r: NodeRow): WfNodeDef {
  const node: WfNodeDef = { node_code: r.node_code.trim(), type: r.type }
  if (r.type === 'APPROVE') {
    node.strategy = r.strategy
    node.handlers = r.handlers.map((x) => {
      if (x.source === 'role') return { source: 'role' as const, code: x.code.trim() }
      if (x.source === 'user') return { source: 'user' as const, userId: x.userId }
      return { source: 'applicant' as const }
    })
  }
  return node
}

// 角色与用户候选，角色源按 code 取值，用户源按 uuid 取值

const roleOptions = ref<Array<{ label: string; value: string }>>([])

async function loadRoles() {
  try {
    const roles = await listRoles()
    roleOptions.value = roles.map((r) => ({ label: r.name || r.code, value: r.code }))
  } catch {
    /* 角色列表拉取失败不阻塞编辑 */
  }
}

const userOptions = ref<Array<{ label: string; value: string }>>([])
const userLabels = ref<Record<string, string>>({})
const userLoading = ref(false)

/** 远程搜索公司用户，命中项并入标签缓存供回填显示 */
async function searchUsers(keyword = '') {
  userLoading.value = true
  try {
    const res = await pageUsers({
      page: 1,
      size: 50,
      companyId: appStore.companyId ?? undefined,
      keyword: keyword.trim() || undefined,
    })
    userOptions.value = res.items.map((u) => ({
      label: u.displayName ? `${u.displayName}（${u.loginName}）` : u.loginName,
      value: u.id,
    }))
    for (const u of res.items) userLabels.value[u.id] = u.displayName || u.loginName
  } catch {
    /* 用户列表拉取失败不阻塞编辑 */
  } finally {
    userLoading.value = false
  }
}

/** 下拉选项，选中值不在当前搜索结果时补回缓存项 */
function userSelectOptions(currentId: string) {
  const opts = userOptions.value
  if (currentId && !opts.some((o) => o.value === currentId)) {
    return [{ label: userLabels.value[currentId] ?? currentId, value: currentId }, ...opts]
  }
  return opts
}

/** 补齐回填用户显示名，未缓存的 id 逐个补查 */
async function ensureUserLabels(ids: string[]) {
  const missing = ids.filter((id) => id && !userLabels.value[id])
  await Promise.all(
    missing.map(async (id) => {
      try {
        const u = await getUser(id)
        if (u) userLabels.value[id] = u.displayName || u.loginName
      } catch {
        /* 单个用户查询失败保留 uuid 展示 */
      }
    }),
  )
}

function collectUserIds(rows: NodeRow[]): string[] {
  return rows.flatMap((r) => r.handlers.map((h) => h.userId)).filter((id) => !!id)
}

/** 处理人来源文本，指定用户取显示名，角色取角色名 */
function handlerLabel(h: HandlerRow): string {
  if (h.source === 'applicant') return '申请人'
  if (h.source === 'user') return userLabels.value[h.userId] ?? h.userId
  return roleOptions.value.find((o) => o.value === h.code)?.label ?? h.code
}

/** 节点处理人概要，多来源顿号连接 */
function handlerSummary(r: NodeRow): string {
  if (r.type === 'START') return '申请人发起'
  if (r.type === 'END') return '流程结束'
  if (!r.handlers.length) return '未配置处理人'
  return r.handlers.map(handlerLabel).join('、')
}

/** 各审批节点策略概要，无审批节点记 — */
function strategySummary(row: WfDefinitionView): string {
  const nodes = parseNodes(row.nodeJson).filter((n) => n.type === 'APPROVE')
  if (!nodes.length) return '—'
  return nodes.map((n) => STRATEGY_LABEL[n.strategy]).join('、')
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
  { key: 'strategy', title: '审批策略', width: 110, hideBelow: 'md', render: (r) => strategySummary(r) },
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

// 编辑弹窗
const showEditor = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const formCode = ref('')
const formName = ref('')
const formType = ref('')
const nodeRows = ref<NodeRow[]>([])

function openEditor(row?: WfDefinitionView) {
  editingId.value = row ? row.id : null
  formCode.value = row ? row.code : ''
  formName.value = row ? row.name : ''
  formType.value = row?.formType ?? ''
  nodeRows.value = row ? parseNodes(row.nodeJson) : []
  if (!row || !nodeRows.value.length) {
    nodeRows.value = [newNodeRow()]
  }
  void searchUsers('')
  void ensureUserLabels(collectUserIds(nodeRows.value))
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
    if (r.type === 'APPROVE') {
      approve++
      if (!r.handlers.length) return `审批节点 ${c} 未配置处理人`
      for (const x of r.handlers) {
        if (x.source === 'role' && !x.code.trim()) return `审批节点 ${c} 的角色处理人未选择角色`
        if (x.source === 'user' && !x.userId) return `审批节点 ${c} 的指定用户未选择用户`
      }
    }
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
    nodeJson: JSON.stringify(nodeRows.value.map(toNodeDef)),
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

// 只读预览，任意状态均可查看节点编排
const showPreview = ref(false)
const previewRow = ref<WfDefinitionView | null>(null)
const previewNodes = ref<NodeRow[]>([])

/** 预览节点转流程图数据，处理人来源文本化 */
const previewSteps = computed<WfGraphStep[]>(() =>
  previewNodes.value.map((r) => ({
    node_code: r.node_code,
    type: r.type,
    strategy: r.type === 'APPROVE' ? r.strategy : undefined,
    handlerLabel: r.type === 'APPROVE' ? handlerSummary(r) : undefined,
  })),
)

function openPreview(row: WfDefinitionView) {
  previewRow.value = row
  previewNodes.value = parseNodes(row.nodeJson)
  void searchUsers('')
  void ensureUserLabels(collectUserIds(previewNodes.value))
  showPreview.value = true
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
      :scroll-x="1120"
    >
      <template #empty>
        <span>暂无流程定义，点右上角新增流程开始配置</span>
      </template>
    </DataTable>

    <!-- 定义编辑弹窗，节点行编辑器 -->
    <n-modal v-model:show="showEditor" preset="card" :title="editingId ? '编辑流程' : '新增流程'" style="max-width: 760px">
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
            <div class="node-row-head">
              <n-input v-model:value="r.node_code" size="small" placeholder="节点编码" style="width: 140px" />
              <n-select v-model:value="r.type" size="small" :options="TYPE_OPTIONS" style="width: 100px" />
              <n-select
                v-if="r.type === 'APPROVE'"
                v-model:value="r.strategy"
                size="small"
                :options="STRATEGY_OPTIONS"
                style="width: 190px"
              />
              <div class="node-row-actions">
                <n-button text type="primary" size="small" :disabled="i === 0" @click="moveNode(i, -1)">上移</n-button>
                <n-button text type="primary" size="small" :disabled="i === nodeRows.length - 1" @click="moveNode(i, 1)">下移</n-button>
                <n-button text type="error" size="small" @click="removeNode(i)">删除</n-button>
              </div>
            </div>

            <div v-if="r.type === 'APPROVE'" class="handler-list">
              <div v-for="(x, j) in r.handlers" :key="j" class="handler-row">
                <n-select
                  v-model:value="x.source"
                  size="small"
                  :options="SOURCE_OPTIONS"
                  style="width: 110px"
                  @update:value="onSourceChange(x)"
                />
                <n-select
                  v-if="x.source === 'role'"
                  v-model:value="x.code"
                  size="small"
                  :options="roleOptions"
                  filterable
                  placeholder="选择角色"
                  style="flex: 1"
                />
                <n-select
                  v-else-if="x.source === 'user'"
                  v-model:value="x.userId"
                  size="small"
                  :options="userSelectOptions(x.userId)"
                  filterable
                  remote
                  clearable
                  :loading="userLoading"
                  placeholder="搜索用户"
                  style="flex: 1"
                  @search="searchUsers"
                />
                <span v-else class="handler-note">发起该流程的用户</span>
                <n-button
                  text
                  type="error"
                  size="small"
                  :disabled="r.handlers.length <= 1"
                  @click="removeHandler(r, j)"
                >
                  删除
                </n-button>
              </div>
              <n-button size="tiny" dashed style="align-self: flex-start" @click="addHandler(r)">+ 添加处理人</n-button>
            </div>
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
        <WfGraph :steps="previewSteps" />
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
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid var(--fms-border, #e5e5e5);
  border-radius: 6px;
  margin-bottom: 8px;
}
.node-row-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.node-row-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.handler-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-left: 12px;
}
.handler-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.handler-note {
  flex: 1;
  font-size: 12px;
  color: var(--fms-text-muted, #666);
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
</style>
