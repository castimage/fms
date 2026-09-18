<script setup lang="ts">
/**
 * 凭证录入/编辑，/gl/vouchers/new 与 /gl/vouchers/:id/edit 复用，mode 由路由决定，
 * 分录校验，科目必选且末级、借/贷互斥、金额 ≤2 位小数、禁止双零行，差额 ≠ 0 或分录为空
 * 时提交 disabled，保存草稿允许不平衡，编辑模式 getVoucher 回填，status≠DRAFT 提示并跳详情，
 * 期间不可变更、科目/币种禁改，附件支持上传/下载/删除。
 */
import { computed, onMounted, ref } from 'vue'
import { NButton, NDatePicker, NInput, NUpload, useMessage, type UploadFileInfo } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { deleteVoucherFile, downloadVoucherFile, glApi, listVoucherFiles, uploadVoucherFile, type VoucherDoc } from '@/api/gl'
import { useAppStore } from '@/stores/app'
import PageCard from '@/components/common/PageCard.vue'
import FormGrid from '@/components/common/FormGrid.vue'
import EntryTable from '@/components/gl/EntryTable.vue'
import type { EditLine } from '@/components/gl/EntryTable.vue'
import { toFen, toFenOrZero, fromFen } from '@/utils/money'
import { toPlainAmount } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const appStore = useAppStore()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

/** 路由模式，create，/new 无 id 或 edit，/:id/edit 有 id */
const isCreate = computed(() => route.path === '/gl/vouchers/new')
const voucherId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))

// 头部字段
const period = ref(appStore.period)
const voucherDate = ref(dayjs().format('YYYY-MM-DD'))
const note = ref('')
const isPrivate = ref(false)

/** 期间月选择器时间戳，create 可改，edit 只读 */
const periodTs = computed(() => (period.value ? dayjs(period.value + '-01').valueOf() : null))
function onPeriodChange(ts: number | null) {
  if (isCreate.value) {
    period.value = ts ? dayjs(ts).format('YYYY-MM') : dayjs().format('YYYY-MM')
    appStore.setPeriod(period.value)
  }
}
const dateTs = computed(() => (voucherDate.value ? dayjs(voucherDate.value).valueOf() : null))
function onDateChange(ts: number | null) {
  if (!ts) return
  const d = dayjs(ts).format('YYYY-MM-DD')
  // 凭证日期须落在期间内
  if (!d.startsWith(period.value)) {
    message.warning(`凭证日期须落在会计期间 ${period.value} 内`)
    return
  }
  voucherDate.value = d
}

// 分录
const lines = ref<EditLine[]>([])
const loading = ref(false)
const submitting = ref(false)

/** 借贷合计/差额，分整数计算 */
const totalDebitFen = computed(() => lines.value.reduce((s, l) => s + toFenOrZero(l.debit), 0))
const totalCreditFen = computed(() => lines.value.reduce((s, l) => s + toFenOrZero(l.credit), 0))
const diffFen = computed(() => totalDebitFen.value - totalCreditFen.value)
const balanced = computed(() => diffFen.value === 0)

/** 有效分录，科目已选且非双零行 */
const effectiveLines = computed(() =>
  lines.value.filter((l) => l.accountId && (toFenOrZero(l.debit) || toFenOrZero(l.credit))),
)
const canSubmit = computed(() => balanced.value && effectiveLines.value.length > 0 && !submitting.value)

/** 金额合法性，0-2 位小数，非负 */
function isAmountInvalid(s: string): boolean {
  return s !== '' && toFen(s) === null
}

/** 行校验，返回错误信息列表，空数组即通过 */
function validateLines(): string[] {
  const errors: string[] = []
  lines.value.forEach((l, i) => {
    if (!l.accountId) errors.push(`第 ${i + 1} 行：请选择科目`)
    if (isAmountInvalid(l.debit)) errors.push(`第 ${i + 1} 行：借方金额格式不正确（最多 2 位小数）`)
    if (isAmountInvalid(l.credit)) errors.push(`第 ${i + 1} 行：贷方金额格式不正确（最多 2 位小数）`)
    if (toFenOrZero(l.debit) === 0 && toFenOrZero(l.credit) === 0 && (l.debit || l.credit))
      errors.push(`第 ${i + 1} 行：借/贷不可双零`)
  })
  return errors
}

/** 组装创建/更新请求 */
function buildRequest() {
  return {
    companyId: appStore.companyId ?? '',
    period: period.value,
    voucherDate: voucherDate.value,
    isPrivate: isPrivate.value,
    note: note.value || undefined,
    lines: effectiveLines.value.map((l) => ({
      accountId: l.accountId!,
      summary: l.summary || undefined,
      debit: l.debit || undefined,
      credit: l.credit || undefined,
      currency: l.currency || 'CNY',
    })),
  }
}

/** 草稿 id，create 模式首次保存后记录，后续保存走 update 防重复建单 */
const draftId = ref('')

async function save(asSubmit: boolean) {
  if (!appStore.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  const errors = validateLines()
  if (errors.length) {
    message.error(errors[0])
    return
  }
  if (asSubmit && !balanced.value) {
    message.error('借贷不平衡，无法提交审核')
    return
  }
  if (asSubmit && effectiveLines.value.length === 0) {
    message.error('至少需要一条有效分录')
    return
  }
  submitting.value = true
  try {
    const req = buildRequest()
    let id = voucherId.value
    if (isCreate.value) {
      if (!draftId.value) {
        const detail = await glApi.createVoucher(req)
        draftId.value = detail.id
      } else {
        await glApi.updateVoucher(draftId.value, req)
      }
      id = draftId.value
    } else {
      await glApi.updateVoucher(id, req)
    }
    if (id) void uploadAttach(id)
    if (!asSubmit) {
      // 草稿保存后留在编辑页继续编辑
      message.success('草稿已保存')
      return
    }
    // 提交审核，db 触发器终审平衡/期间 OPEN
    const detail = await glApi.submitVoucher(id)
    message.success('已提交审核')
    void router.replace(`/gl/vouchers/${detail.id}`)
  } catch (e) {
    // 全局已提示（http.ts），本地仅兜底
    if (e instanceof Error) message.error(e.message)
  } finally {
    submitting.value = false
  }
}

// 附件（docs.documents，businessType=VOUCHER）
const attachFileList = ref<UploadFileInfo[]>([])  // 待上传文件，保存凭证后统一上传
const existingDocs = ref<VoucherDoc[]>([])        // 已关联附件，编辑模式回显
const docLoading = ref(false)

/** 保存成功后上传待传附件，凭证 id 确定后关联 */
async function uploadAttach(id: string) {
  const pending = attachFileList.value.filter((f) => f.file)
  if (pending.length === 0) return
  for (const f of pending) {
    try {
      await uploadVoucherFile(f.file!, id)
    } catch (e) {
      if (e instanceof Error) message.error('附件上传失败: ' + e.message)
    }
  }
  attachFileList.value = []
  await loadDocs(id)
}

/** 加载凭证已关联附件 */
async function loadDocs(id: string) {
  docLoading.value = true
  try {
    existingDocs.value = await listVoucherFiles(id)
  } finally {
    docLoading.value = false
  }
}

/** 删除附件，软删 + 移出列表 */
async function onRemoveDoc(docId: string) {
  try {
    await deleteVoucherFile(docId)
    existingDocs.value = existingDocs.value.filter((d) => d.id !== docId)
    message.success('附件已删除')
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  }
}

// 编辑模式回填
onMounted(async () => {
  if (!isCreate.value && voucherId.value) {
    loading.value = true
    try {
      const detail = await glApi.getVoucher(voucherId.value)
      if (!detail) {
        message.error('凭证不存在或无权查看')
        void router.replace('/gl/vouchers')
        return
      }
      if (detail.status !== 'DRAFT') {
        message.warning('仅草稿凭证可编辑')
        void router.replace(`/gl/vouchers/${voucherId.value}`)
        return
      }
      void loadDocs(voucherId.value)
      period.value = detail.period
      voucherDate.value = dayjs(detail.voucherDate).format('YYYY-MM-DD')
      note.value = detail.note ?? ''
      isPrivate.value = detail.isPrivate
      appStore.setPeriod(detail.period)
      lines.value = detail.lines.map((l, i) => ({
        key: `edit-${i}-${l.id}`,
        accountId: l.accountId,
        accountCode: l.accountCode,
        accountName: l.accountName,
        summary: l.summary ?? '',
        debit: toPlainAmount(l.debit),
        credit: toPlainAmount(l.credit),
        currency: l.currency ?? 'CNY',
      }))
    } finally {
      loading.value = false
    }
  } else {
    // create 模式，默认一行空分录
    lines.value.push(createEmptyLine())
  }
})

/** 初始化一行空分录，EntryTable 内部亦有 addLine */
function createEmptyLine(): EditLine {
  return {
    key: `init-${Date.now()}`,
    accountId: null,
    accountCode: '',
    accountName: '',
    summary: '',
    debit: '',
    credit: '',
    currency: 'CNY',
  }
}
</script>

<template>
  <PageCard :title="isCreate ? '凭证录入' : `编辑凭证 ${voucherId}`" :loading="loading">
    <!-- 头部，公司只读 / 期间，create 可选 / 凭证日期 / 摘要 -->
    <FormGrid>
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
      <n-form-item label="会计期间">
        <n-date-picker
          type="month"
          :value="periodTs"
          :clearable="false"
          :disabled="!isCreate"
          style="width: 100%"
          @update:value="onPeriodChange"
        />
      </n-form-item>
      <n-form-item label="凭证日期">
        <n-date-picker type="date" :value="dateTs" :clearable="false" style="width: 100%" @update:value="onDateChange" />
      </n-form-item>
      <n-form-item label="摘要">
        <n-input v-model:value="note" maxlength="500" placeholder="凭证摘要（可选）" />
      </n-form-item>
      <n-form-item label="私密">
        <n-switch v-model:value="isPrivate" :disabled="!isCreate" />
      </n-form-item>
    </FormGrid>

    <!-- 分录表格，草稿可编辑，编辑模式期间不可变，科目编码沿用原值 -->
    <EntryTable v-model:lines="lines" :company-id="appStore.companyId" />

    <!-- 附件，收集文件，保存凭证后统一上传关联，VOUCHER 类型，编辑模式回显已关联附件 -->
    <n-form-item label="附件" class="attach-item">
      <n-upload
        v-model:file-list="attachFileList"
        :default-upload="false"
        multiple
        accept=".pdf,.jpg,.jpeg,.png,.gif,.doc,.docx,.xls,.xlsx,.csv,.txt"
      >
        <n-button size="small">选择附件</n-button>
        <span class="attach-tip">保存凭证后自动上传关联（≤8MB，pdf/jpg/png/doc/xls 等）</span>
      </n-upload>
      <div v-if="existingDocs.length" class="attach-docs">
        <div v-for="d in existingDocs" :key="d.id" class="attach-doc">
          <n-button size="tiny" text type="primary" @click="downloadVoucherFile(d.id)">
            {{ d.fileName }}（{{ (d.fileSize / 1024).toFixed(1) }}KB）
          </n-button>
          <n-button size="tiny" quaternary @click="onRemoveDoc(d.id)">删除</n-button>
        </div>
      </div>
    </n-form-item>
  </PageCard>

  <!-- 底部工具栏，借贷合计 + 差额 + 保存/提交，sticky 常驻可视 -->
  <div class="entry-toolbar">
    <div class="toolbar-sums">
      <span>借方合计：<b class="num">{{ fromFen(totalDebitFen) }}</b></span>
      <span>贷方合计：<b class="num">{{ fromFen(totalCreditFen) }}</b></span>
      <span :class="balanced ? 'sum-balanced' : 'sum-unbalanced'">
        差额：{{ fromFen(Math.abs(diffFen)) }} {{ balanced ? '（借贷平衡）' : '（借贷不平衡）' }}
      </span>
    </div>
    <div class="toolbar-actions">
      <n-button v-if="canWrite" :loading="submitting" @click="save(false)">保存草稿</n-button>
      <n-button v-if="canWrite" type="primary" :disabled="!canSubmit" :loading="submitting" @click="save(true)">
        提交审核
      </n-button>
    </div>
  </div>
</template>

<style scoped>
.attach-item {
  margin-top: 12px;
}
.attach-tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--fms-text-muted);
}
/* sticky 底部工具栏，白底 + 阴影，常驻可视 */
.entry-toolbar {
  position: sticky;
  bottom: 0;
  z-index: 10;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 16px;
  margin-top: 12px;
  background: var(--n-color, #fff);
  border-radius: 8px;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.06);
}
html.dark .entry-toolbar {
  background: var(--n-color, #1e1e1e);
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.4);
}
.toolbar-sums {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 13px;
}
.sum-balanced {
  color: var(--n-success-color, #18a058);
  font-weight: 600;
}
.sum-unbalanced {
  color: var(--n-error-color, #d03050);
  font-weight: 600;
}
</style>
