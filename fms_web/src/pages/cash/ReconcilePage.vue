<script setup lang="ts">
/**
 * 对账，现金银行，对账单两阶段导入，预览不落库，确认再事务落库，自动对账由独立按钮触发、
 * 批次列表（autoMatched/manualMatched/diffCount）、详情抽屉内人工配对/撤销
 * 均限 UNMATCHED 原子配对，0 行则 40900。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NDataTable, NDescriptions, NDescriptionsItem, NDrawer, NDrawerContent, NModal, NPopconfirm, NSelect, NUpload, useMessage } from 'naive-ui'
import type { UploadCustomRequestOptions } from 'naive-ui'
import { cashApi } from '@/api/cash'
import { useAppStore } from '@/stores/app'
import { useConfigStore } from '@/stores/config'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { BankAccountView, BankStatementLineView, BankStatementView, ReconcileRunView, StatementImportPreview, StatementLinePreview } from '@/types/cash'
import { formatDate, formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'

const message = useMessage()
const appStore = useAppStore()
const configStore = useConfigStore()
void configStore.load() // 预载配置，导入 accept 白名单经 /api/config 下发，fms.file.allowed-extensions，与后端附件上传同源

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

/** 对账单导入 accept，选择器过滤，由 /api/config 扩展名白名单推导，含 tsv，
 * 勿本地硬编码重复维护，裁决以后端为准，accept 仅作选择器过滤 */
const statementAccept = computed(() => configStore.cfg.allowedExtensions.map((e) => `.${e}`).join(','))

// 账户下拉，导入与对账单筛选共用
const accountOptions = ref<{ label: string; value: string }[]>([])
async function loadAccounts() {
  const res = await cashApi.pageBankAccounts({ page: 1, size: await refdataPageSize() })
  accountOptions.value = res.items.map((r: BankAccountView) => ({
    label: `${r.bankName}（${r.accountNoMasked ?? '—'}）`,
    value: r.id,
  }))
}

const page = ref(1)
const size = ref(20)
const runs = ref<ReconcileRunView[]>([])
const runsTotal = ref(0)
const runsLoading = ref(false)
const autoLoading = ref(false)

let runsSeq = 0

async function loadRuns() {
  const seq = ++runsSeq
  runsLoading.value = true
  try {
    const res = await cashApi.pageReconcileRuns({ page: page.value, size: size.value, companyId: appStore.companyId ?? undefined })
    if (seq !== runsSeq) return
    runs.value = res.items
    runsTotal.value = res.total
  } finally {
    if (seq === runsSeq) runsLoading.value = false
  }
}
watch([page, size], () => void loadRuns())

async function onAutoReconcile() {
  autoLoading.value = true
  try {
    const r = await cashApi.autoReconcile(appStore.companyId ?? undefined)
    message.success(`自动对账完成：配对 ${r.autoMatched} 条，剩余差异 ${r.diffCount} 条`)
    await loadRuns()
    await loadStatements()
  } finally {
    autoLoading.value = false
  }
}

const sAccountId = ref('')
const sPage = ref(1)
const sSize = ref(20)
const statements = ref<BankStatementView[]>([])
const sTotal = ref(0)
const sLoading = ref(false)

let stmtSeq = 0

async function loadStatements() {
  const seq = ++stmtSeq
  sLoading.value = true
  try {
    const res = await cashApi.pageStatements({
      page: sPage.value,
      size: sSize.value,
      bankAccountId: sAccountId.value || undefined,
    })
    if (seq !== stmtSeq) return
    statements.value = res.items
    sTotal.value = res.total
  } finally {
    if (seq === stmtSeq) sLoading.value = false
  }
}
watch([sPage, sSize], () => void loadStatements())
watch(sAccountId, () => {
  sPage.value = 1
  void loadStatements()
})

const importAccountId = ref('')
const preview = ref<StatementImportPreview | null>(null)
/** 预览弹窗开关，由 preview 派生，关闭即清空 */
const showPreview = computed({
  get: () => preview.value !== null,
  set: (v: boolean) => {
    if (!v) preview.value = null
  },
})

function handleUpload(options: UploadCustomRequestOptions) {
  const file = options.file.file
  if (!importAccountId.value) {
    message.warning('请先选择银行账户')
    options.onError()
    return
  }
  if (!file) {
    message.warning('未获取到文件')
    options.onError()
    return
  }
  void (async () => {
    try {
      preview.value = await cashApi.importStatement(file, importAccountId.value)
      options.onFinish()
    } catch {
      options.onError()
    }
  })()
}

/** 两阶段第二步，预览行事务落库 */
async function onConfirm() {
  if (!preview.value) return
  await cashApi.confirmStatement({
    bankAccountId: preview.value.bankAccountId,
    filename: preview.value.filename,
    rows: preview.value.rows,
  })
  message.success('对账单已落库')
  preview.value = null
  await loadStatements()
}

const showDrawer = ref(false)
const detail = ref<{ statement: BankStatementView; lines: BankStatementLineView[] } | null>(null)
const detailLoading = ref(false)

async function openDetail(r: BankStatementView) {
  detailLoading.value = true
  try {
    const res = await cashApi.getStatement(r.id)
    if (!res) {
      message.error('对账单不存在或无权查看')
      return
    }
    detail.value = res
    showDrawer.value = true
  } finally {
    detailLoading.value = false
  }
}

const showManual = ref(false)
const manualLine = ref<BankStatementLineView | null>(null)
const trxId = ref('')
const trxOptions = ref<{ label: string; value: string }[]>([])
const trxLoading = ref(false)
const manualSaving = ref(false)

/** 载入同公司未匹配流水作为配对候选，分页大小取 refdataPageSize */
async function openManual(line: BankStatementLineView) {
  manualLine.value = line
  trxId.value = ''
  trxLoading.value = true
  try {
    const res = await cashApi.pageTransactions({ page: 1, size: await refdataPageSize(), matchStatus: 'UNMATCHED', companyId: appStore.companyId ?? undefined })
    trxOptions.value = res.items.map((t) => ({
      label: `${formatDate(t.trxDate)} ${t.bankName} ${t.amount}${t.summary ? ' ' + t.summary : ''}`,
      value: t.id,
    }))
    showManual.value = true
  } finally {
    trxLoading.value = false
  }
}

async function onManualMatch() {
  const line = manualLine.value
  if (!line || !trxId.value) {
    message.warning('请选择要配对的流水')
    return
  }
  manualSaving.value = true
  try {
    await cashApi.manualMatch({ trxId: trxId.value, lineId: line.id, companyId: appStore.companyId ?? '' })
    message.success('配对成功')
    showManual.value = false
    await refreshDetail()
    await loadRuns()
  } finally {
    manualSaving.value = false
  }
}

async function onUndo(line: BankStatementLineView) {
  if (!line.matchedTrxId) return
  await cashApi.undoMatch({ trxId: line.matchedTrxId, lineId: line.id })
  message.success('已撤销配对')
  await refreshDetail()
  await loadRuns()
}

async function refreshDetail() {
  if (!detail.value) return
  const res = await cashApi.getStatement(detail.value.statement.id)
  if (res) detail.value = res
}

const renderMatch = (s: string) => h(StatusTag, { status: s })

const runColumns: FmsColumn<ReconcileRunView>[] = [
  { key: 'runAt', title: '运行时间', width: 160, render: (r) => formatDateTime(r.runAt) },
  { key: 'period', title: '期间', width: 100, render: (r) => r.period || '—' },
  { key: 'autoMatched', title: '自动配对', width: 90, align: 'right' },
  { key: 'manualMatched', title: '人工配对', width: 90, align: 'right', hideBelow: 'sm' },
  { key: 'diffCount', title: '差异行数', width: 90, align: 'right' },
]

const statementColumns: FmsColumn<BankStatementView>[] = [
  { key: 'filename', title: '文件名', width: 200 },
  { key: 'bankName', title: '银行账户', width: 160, hideBelow: 'md' },
  { key: 'importDate', title: '账单日期', width: 110, render: (r) => formatDate(r.importDate) },
  { key: 'totalLines', title: '行数', width: 70, align: 'right', render: (r) => r.totalLines ?? '—' },
  { key: 'status', title: '状态', width: 90, render: (r) => renderMatch(r.status) },
  {
    key: 'actions',
    title: '操作',
    width: 90,
    align: 'center',
    render: (r) => h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openDetail(r) }, { default: () => '详情' }),
  },
]

const previewColumns: FmsColumn<StatementLinePreview>[] = [
  { key: 'trxDate', title: '交易日期', width: 110, render: (r) => formatDate(r.trxDate) },
  { key: 'direction', title: '方向', width: 70, render: (r) => (r.direction === 'IN' ? '收入' : '支出') },
  { key: 'amount', title: '金额', width: 130, align: 'right', render: (r) => h(MoneyText, { value: r.amount }) },
  { key: 'summary', title: '摘要', render: (r) => r.summary || '—' },
]

const lineColumns: FmsColumn<BankStatementLineView>[] = [
  { key: 'trxDate', title: '交易日期', width: 110, render: (r) => formatDate(r.trxDate) },
  { key: 'direction', title: '方向', width: 70, render: (r) => (r.direction === 'IN' ? '收入' : '支出') },
  { key: 'amount', title: '金额', width: 120, align: 'right', render: (r) => h(MoneyText, { value: r.amount }) },
  { key: 'summary', title: '摘要', width: 160, render: (r) => r.summary || '—' },
  { key: 'matchStatus', title: '匹配状态', width: 90, render: (r) => renderMatch(r.matchStatus) },
  { key: 'similarityScore', title: '相似度', width: 90, render: (r) => (r.similarityScore !== null ? r.similarityScore.toFixed(2) : '—') },
  {
    key: 'actions',
    title: '操作',
    width: 110,
    align: 'center',
    render: (r) => {
      if (!canWrite.value) return h('span', { class: 'muted' }, '—')
      return r.matchStatus === 'UNMATCHED'
        ? h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openManual(r) }, { default: () => '人工配对' })
        : h(NPopconfirm, { onPositiveClick: () => onUndo(r) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '撤销' }),
            default: () => '确认撤销该配对？',
          })
    },
  },
]

onMounted(() => {
  void loadAccounts()
  void loadRuns()
  void loadStatements()
})
</script>

<template>
  <div class="reconcile-page">
    <!-- 工具栏，导入银行账户 + 对账单文件 + 发起自动对账 -->
    <div class="toolbar">
      <span class="toolbar-title">对账中心</span>
      <div class="toolbar-actions">
        <n-select
          v-model:value="importAccountId"
          :options="accountOptions"
          placeholder="选择银行账户"
          clearable
          size="small"
          style="width: 200px"
        />
        <n-upload
          v-if="canWrite"
          :accept="statementAccept"
          :show-file-list="false"
          :custom-request="handleUpload"
        >
          <n-button size="small">导入对账单</n-button>
        </n-upload>
        <n-button v-if="canWrite" type="primary" size="small" :loading="autoLoading" @click="onAutoReconcile">
          发起自动对账
        </n-button>
      </div>
    </div>

    <div class="section-title">对账批次</div>
    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="runColumns"
      :data="runs"
      :loading="runsLoading"
      :total="runsTotal"
      :scroll-x="700"
    >
      <template #empty>
        <span>暂无对账批次，点击「发起自动对账」开始</span>
      </template>
    </DataTable>

    <FormGrid class="stmt-filter">
      <n-form-item label="银行账户">
        <n-select v-model:value="sAccountId" :options="accountOptions" placeholder="全部" clearable />
      </n-form-item>
    </FormGrid>
    <div class="section-title">对账单</div>
    <DataTable
      v-model:page="sPage"
      v-model:size="sSize"
      :columns="statementColumns"
      :data="statements"
      :loading="sLoading"
      :total="sTotal"
      :scroll-x="800"
    >
      <template #empty>
        <span>暂无对账单，点击「导入对账单」上传</span>
      </template>
    </DataTable>

    <!-- 导入预览，两阶段第一步，不落库，核对后确认 -->
    <n-modal v-model:show="showPreview" preset="card" title="对账单导入预览" style="width: 640px; max-width: 94vw">
      <template v-if="preview">
        <p class="preview-tip">
          {{ preview.filename }}：共 {{ preview.totalLines }} 行，核对无误后确认落库
        </p>
        <n-data-table :columns="previewColumns" :data="preview.rows" size="small" :scroll-x="640" max-height="320" />
      </template>
      <template #footer>
        <n-button @click="preview = null">取消</n-button>
        <n-button v-if="canWrite" type="primary" @click="onConfirm">确认落库</n-button>
      </template>
    </n-modal>

    <!-- 对账单详情抽屉，行明细 + 人工配对/撤销 -->
    <n-drawer v-model:show="showDrawer" :width="'min(720px, 100vw)'">
      <n-drawer-content :title="detail ? `对账单：${detail.statement.filename}` : '对账单详情'" closable>
        <n-spin :show="detailLoading">
          <template v-if="detail">
            <n-descriptions :column="2" label-placement="left" bordered size="small" class="stmt-head">
              <n-descriptions-item label="银行账户">{{ detail.statement.bankName }}</n-descriptions-item>
              <n-descriptions-item label="账单日期">{{ formatDate(detail.statement.importDate) }}</n-descriptions-item>
              <n-descriptions-item label="行数">{{ detail.statement.totalLines ?? '—' }}</n-descriptions-item>
              <n-descriptions-item label="导入时间">{{ formatDateTime(detail.statement.createdAt) }}</n-descriptions-item>
            </n-descriptions>
            <n-data-table :columns="lineColumns" :data="detail.lines" size="small" :scroll-x="820" class="line-table" />
          </template>
        </n-spin>
      </n-drawer-content>
    </n-drawer>

    <!-- 人工配对弹窗，选择同公司未匹配流水 -->
    <n-modal v-model:show="showManual" preset="card" title="人工配对" style="width: 520px; max-width: 94vw">
      <template v-if="manualLine">
        <p class="preview-tip">
          对账单行：{{ formatDate(manualLine.trxDate) }} ¥{{ manualLine.amount }} {{ manualLine.summary || '' }}
        </p>
        <n-form-item label="选择未匹配流水">
          <n-select
            v-model:value="trxId"
            :options="trxOptions"
            :loading="trxLoading"
            placeholder="无未匹配流水时先登记收付款单"
            filterable
          />
        </n-form-item>
      </template>
      <template #footer>
        <n-button @click="showManual = false">取消</n-button>
        <n-button v-if="canWrite" type="primary" :loading="manualSaving" @click="onManualMatch">确认配对</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.reconcile-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar-title,
.section-title {
  font-weight: 600;
}
.section-title {
  margin-top: 4px;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.stmt-filter {
  background: var(--n-color, #fff);
  border-radius: 8px;
  padding: 16px 16px 0;
  margin-top: 4px;
}
html.dark .stmt-filter {
  background: var(--n-color, #1e1e1e);
}
.preview-tip {
  margin: 0 0 12px;
  color: var(--n-text-color-3, var(--fms-text-muted));
  font-size: 13px;
}
.muted {
  color: var(--n-text-color-3, var(--fms-text-muted));
}
.stmt-head {
  margin-bottom: 12px;
}
/* 手机，工具栏操作下沉换行 */
@media (max-width: 767px) {
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
  .toolbar-actions {
    justify-content: flex-start;
  }
}
</style>
