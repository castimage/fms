<script setup lang="ts">
/**
 * 报销申请单（/expense/reimbursements），/new 新建 | /:id 详情，?edit=1 或点编辑进入草稿修改。
 * 新建/编辑，头部字段 + 明细行表格，行合计=头金额，与后端 Service 强制一致，保存草稿/保存并提交。
 *
 * 详情，只读头部 + 明细，按状态给操作，本人草稿/被驳回可编辑提交删除，SUBMITTED 可审批/驳回，
 * APPROVED 可付款，PAID 可生成过账凭证（幂等，后端同事务回填 voucher_id 并推进 POSTED），
 * 已生成凭证时可跳转凭证详情；权限由后端 rls 兜底，越权 40900 全局提示。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  NButton,
  NDatePicker,
  NEmpty,
  NInput,
  NInputNumber,
  NPopconfirm,
  NSelect,
  NTable,
  useMessage,
} from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { expenseApi } from '@/api/expense'
import { pageDepartments } from '@/api/org'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useCanWrite } from '@/utils/perm'
import { refdataPageSize } from '@/utils/refdata'
import PageCard from '@/components/common/PageCard.vue'
import FormGrid from '@/components/common/FormGrid.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import type { ReimbursementDetailView } from '@/types/expense'
import { formatAmount, formatDateTime } from '@/utils/format'
import { fromFen, toFen, toFenOrZero } from '@/utils/money'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const appStore = useAppStore()
const auth = useAuthStore()
/** 当前会话是否业务写角色，viewer/auditor/report 只读，写按钮统一 v-if="canWrite" */
const canWrite = useCanWrite()

/** 新建，/new 无 id| 详情，/:id 有 id */
const isCreate = computed(() => typeof route.params.id !== 'string')
const reimbursementId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))

/** 部门下拉，org.departments，rls 收敛可见范围 */
const deptOptions = ref<{ label: string; value: string }[]>([])
async function loadDepts() {
  if (!appStore.companyId) return
  const res = await pageDepartments({ page: 1, size: await refdataPageSize(), companyId: appStore.companyId })
  deptOptions.value = res.items.map((d) => ({ label: `${d.code} ${d.name}`, value: d.id }))
}

const editing = ref(isCreate.value)
const submitting = ref(false)
const detail = ref<ReimbursementDetailView | null>(null)
const notFound = ref(false)
const loading = ref(false)

/** 明细行草稿，amount 为元数字，分整数路径校验 */
interface LineDraft {
  key: string
  type: string
  amount: number | null
  trxDate: string
  summary: string
}

const form = reactive({
  title: '',
  amount: null as number | null,
  currency: 'CNY',
  deptId: '',
})
const lines = ref<LineDraft[]>([])

/** 明细合计，分整数，防浮点误差 */
const linesTotalFen = computed(() => lines.value.reduce((s, l) => s + toFenOrZero(l.amount), 0))
const amountValid = computed(() => toFen(form.amount) === linesTotalFen.value)

function emptyLine(): LineDraft {
  return { key: `line-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, type: '', amount: null, trxDate: dayjs().format('YYYY-MM-DD'), summary: '' }
}
function addLine() {
  lines.value.push(emptyLine())
}
function removeLine(i: number) {
  lines.value.splice(i, 1)
}

/** 进入草稿编辑，详情回填 */
function startEdit() {
  if (!detail.value) return
  form.title = detail.value.title
  form.amount = detail.value.amount
  form.currency = detail.value.currency || 'CNY'
  form.deptId = detail.value.deptId
  lines.value = detail.value.lines.map((l, i) => ({
    key: `edit-${i}-${l.id}`,
    type: l.type,
    amount: l.amount,
    trxDate: dayjs(l.trxDate).format('YYYY-MM-DD'),
    summary: l.summary,
  }))
  editing.value = true
}

/** 行级校验 + 头行金额一致性，返回错误文案数组，空 = 通过 */
function validate(): string[] {
  const errs: string[] = []
  if (!form.title.trim()) errs.push('标题必填')
  if (!form.deptId) errs.push('请选择报销部门')
  if (!lines.value.length) errs.push('至少需要一条明细')
  lines.value.forEach((l, i) => {
    if (!l.type.trim()) errs.push(`第 ${i + 1} 行：明细类型必填`)
    if (l.amount === null || l.amount <= 0) errs.push(`第 ${i + 1} 行：金额必须大于 0`)
    if (!l.trxDate) errs.push(`第 ${i + 1} 行：发生日期必填`)
    if (!l.summary.trim()) errs.push(`第 ${i + 1} 行：摘要必填`)
  })
  if (!amountValid.value) errs.push('报销总额须等于明细行合计')
  return errs
}

function buildRequest() {
  return {
    companyId: appStore.companyId ?? '',
    title: form.title.trim(),
    amount: form.amount ?? 0,
    currency: form.currency || 'CNY',
    deptId: form.deptId,
    lines: lines.value.map((l) => ({
      type: l.type.trim(),
      amount: l.amount ?? 0,
      trxDate: l.trxDate,
      summary: l.summary.trim(),
    })),
  }
}

/** 草稿 id，create 模式首次保存后记录，后续保存走 update 避免重复建单 */
const draftId = ref('')

async function save(asSubmit: boolean) {
  if (!appStore.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  const errs = validate()
  if (errs.length) {
    message.error(errs[0])
    return
  }
  submitting.value = true
  try {
    const req = buildRequest()
    let id = reimbursementId.value
    if (isCreate.value) {
      if (!draftId.value) {
        const d = await expenseApi.createReimbursement(req)
        draftId.value = d.id
      } else {
        await expenseApi.updateReimbursement(draftId.value, req)
      }
      id = draftId.value
    } else {
      await expenseApi.updateReimbursement(id, req)
    }
    if (!asSubmit) {
      message.success('草稿已保存')
      return
    }
    // 提交，REJECTED 经更新路径预检转 DRAFT 后提交，db 触发器终审
    const d = await expenseApi.submitReimbursement(id)
    message.success('已提交审批')
    if (isCreate.value) {
      void router.replace(`/expense/reimbursements/${d.id}`)
    } else {
      editing.value = false
      await loadDetail()
    }
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    submitting.value = false
  }
}

async function loadDetail() {
  if (!reimbursementId.value) return
  loading.value = true
  try {
    const d = await expenseApi.getReimbursement(reimbursementId.value)
    if (!d) {
      // 他人草稿/越权，不泄露存在性
      notFound.value = true
      return
    }
    detail.value = d
    // 列表页 ?edit=1 直达草稿编辑，仅本人草稿/被驳回可见该入口
    if (route.query.edit === '1') startEdit()
  } finally {
    loading.value = false
  }
}

/** 申请人与当前用户一致，编辑/删除/提交仅本人可见 */
const isSelf = computed(() => !!detail.value && !!auth.user?.id && detail.value.applicantId === auth.user.id)

async function runAction(fn: () => Promise<unknown>, okText: string) {
  actionLoading.value = true
  try {
    await fn()
    message.success(okText)
    await loadDetail()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    actionLoading.value = false
  }
}
const actionLoading = ref(false)

function onActionSubmit() {
  return runAction(() => expenseApi.submitReimbursement(reimbursementId.value), '已提交审批')
}
function onActionApprove() {
  return runAction(() => expenseApi.approveReimbursement(reimbursementId.value), '已审批')
}
function onActionReject() {
  return runAction(() => expenseApi.rejectReimbursement(reimbursementId.value), '已驳回')
}
function onActionPay() {
  return runAction(() => expenseApi.payReimbursement(reimbursementId.value), '已付款')
}
/** 生成过账凭证（幂等），成功即已过账，凭证详情入口 goVoucher 在刷新后出现 */
function onActionLinkVoucher() {
  return runAction(() => expenseApi.linkVoucher(reimbursementId.value), '已生成凭证并过账')
}
/** 跳转联动凭证详情，凭证只读态由 gl 模块按状态渲染 */
function goVoucher() {
  const id = detail.value?.voucherId
  if (id) void router.push(`/gl/vouchers/${id}`)
}
async function onActionDelete() {
  await expenseApi.deleteReimbursement(reimbursementId.value)
  message.success('已删除')
  void router.replace('/expense/reimbursements')
}

// 从 /new 提交后 replace 到 /:id，同组件实例复用，靠 id 变化触发详情加载
watch(reimbursementId, (id) => {
  if (!id) return
  editing.value = false
  void loadDetail()
})
watch(() => appStore.companyId, () => void loadDepts())

onMounted(async () => {
  await loadDepts()
  if (isCreate.value) {
    // 新建，默认一行空明细
    lines.value.push(emptyLine())
  } else {
    await loadDetail()
  }
})
</script>

<template>
  <PageCard
    :title="isCreate ? '新建报销单' : `报销单详情 ${detail ? detail.title : ''}`"
    :loading="loading"
  >
    <n-empty v-if="notFound" description="报销单不存在或无权查看" />
    <template v-else>
      <!-- 编辑态，头部 + 明细行表格 -->
      <template v-if="editing">
        <FormGrid>
          <n-form-item label="公司">
            <n-input :value="appStore.companyName || '默认公司'" readonly />
          </n-form-item>
          <n-form-item label="报销标题">
            <n-input v-model:value="form.title" maxlength="100" placeholder="报销标题" />
          </n-form-item>
          <n-form-item label="报销总额">
            <n-input-number v-model:value="form.amount" :min="0.01" :precision="2" style="width: 100%" placeholder="须 = 明细合计" />
          </n-form-item>
          <n-form-item label="币种">
            <n-input v-model:value="form.currency" maxlength="3" placeholder="CNY" />
          </n-form-item>
          <n-form-item label="报销部门">
            <n-select v-model:value="form.deptId" :options="deptOptions" filterable placeholder="选择部门" style="width: 100%" />
          </n-form-item>
        </FormGrid>

        <div class="lines-block">
          <div class="lines-toolbar">
            <span class="block-title">明细行</span>
            <div class="lines-toolbar-right">
              <span :class="amountValid ? 'sum-ok' : 'sum-bad'">明细合计：{{ fromFen(linesTotalFen) }}</span>
              <n-button v-if="canWrite" size="small" @click="addLine">添加明细</n-button>
            </div>
          </div>
          <div class="lines-table-wrap">
            <table class="lines-table">
              <thead>
                <tr>
                  <th class="col-type">明细类型</th>
                  <th class="col-amount">金额</th>
                  <th class="col-date">发生日期</th>
                  <th>摘要</th>
                  <th class="col-del" />
                </tr>
              </thead>
              <tbody>
                <tr v-for="(l, i) in lines" :key="l.key">
                  <td>
                    <n-input v-model:value="l.type" maxlength="32" placeholder="餐费 / 差旅 / 办公" />
                  </td>
                  <td>
                    <n-input-number v-model:value="l.amount" :min="0.01" :precision="2" style="width: 100%" />
                  </td>
                  <td>
                    <n-date-picker
                      :value="l.trxDate ? dayjs(l.trxDate).valueOf() : null"
                      type="date"
                      :clearable="false"
                      style="width: 100%"
                      @update:value="(v: number | null) => (l.trxDate = v ? dayjs(v).format('YYYY-MM-DD') : '')"
                    />
                  </td>
                  <td>
                    <n-input v-model:value="l.summary" maxlength="200" placeholder="费用摘要" />
                  </td>
                  <td class="col-del">
                    <n-button v-if="canWrite" text type="error" size="small" @click="removeLine(i)">删</n-button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 底部操作，保存草稿 / 保存并提交，sticky 常驻 -->
        <div class="form-actions">
          <div class="form-actions-left">
            <n-button v-if="canWrite" :loading="submitting" @click="save(false)">保存草稿</n-button>
            <n-button v-if="canWrite" type="primary" :loading="submitting" @click="save(true)">保存并提交</n-button>
          </div>
          <n-button v-if="!isCreate" size="small" @click="editing = false">取消编辑</n-button>
        </div>
      </template>

      <!-- 详情态，只读头部 + 明细 + 状态动作 -->
      <template v-else>
        <FormGrid>
          <n-form-item label="状态">
            <StatusTag :status="detail?.status ?? ''" />
          </n-form-item>
          <n-form-item label="申请人">
            <n-input :value="detail?.applicantName ?? ''" readonly />
          </n-form-item>
          <n-form-item label="部门">
            <n-input :value="detail?.deptName ?? ''" readonly />
          </n-form-item>
          <n-form-item label="报销金额">
            <MoneyText :value="detail?.amount ?? 0" :symbol="detail?.currency === 'CNY' ? '¥' : ''" style="width: 100%" />
          </n-form-item>
          <n-form-item label="币种">
            <n-input :value="detail?.currency ?? ''" readonly />
          </n-form-item>
          <n-form-item label="创建时间">
            <n-input :value="detail ? formatDateTime(detail.createdAt) : ''" readonly />
          </n-form-item>
        </FormGrid>

        <div class="lines-block">
          <div class="lines-toolbar">
            <span class="block-title">明细行</span>
            <span :class="amountValid ? 'sum-ok' : 'sum-bad'">合计：{{ formatAmount(detail?.amount) }}</span>
          </div>
          <n-table :bordered="false" size="small">
            <thead>
              <tr>
                <th>类型</th>
                <th>金额</th>
                <th>发生日期</th>
                <th>摘要</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="l in detail?.lines ?? []" :key="l.id">
                <td>{{ l.type }}</td>
                <td class="num-col"><MoneyText :value="l.amount" :symbol="detail?.currency === 'CNY' ? '¥' : ''" /></td>
                <td>{{ dayjs(l.trxDate).format('YYYY-MM-DD') }}</td>
                <td>{{ l.summary }}</td>
              </tr>
            </tbody>
          </n-table>
        </div>

        <!-- 状态机操作区，权限后端 rls 兜底，非法流转 40900 全局提示 -->
        <div class="form-actions">
          <div class="form-actions-left">
            <n-button v-if="detail?.status === 'DRAFT' && isSelf && canWrite" size="small" @click="startEdit">编辑</n-button>
            <n-button v-if="detail?.status === 'DRAFT' && isSelf && canWrite" size="small" :loading="actionLoading" @click="onActionSubmit">提交</n-button>
            <n-popconfirm v-if="detail?.status === 'DRAFT' && isSelf && canWrite" @positive-click="onActionDelete">
              <template #trigger>
                <n-button size="small" type="error">删除</n-button>
              </template>
              确认删除该报销单？
            </n-popconfirm>
            <n-button v-if="detail?.status === 'REJECTED' && isSelf && canWrite" size="small" @click="startEdit">修改后重新提交</n-button>
            <n-button v-if="detail?.status === 'SUBMITTED' && canWrite" type="primary" size="small" :loading="actionLoading" @click="onActionApprove">审批通过</n-button>
            <n-button v-if="detail?.status === 'SUBMITTED' && canWrite" size="small" :loading="actionLoading" @click="onActionReject">驳回</n-button>
            <n-button v-if="detail?.status === 'APPROVED' && canWrite" type="primary" size="small" :loading="actionLoading" @click="onActionPay">出纳付款</n-button>
            <n-button v-if="detail?.status === 'PAID' && canWrite" type="primary" size="small" :loading="actionLoading" @click="onActionLinkVoucher">生成凭证</n-button>
            <n-button v-if="detail?.voucherId" size="small" @click="goVoucher">查看凭证</n-button>
          </div>
        </div>
      </template>
    </template>
  </PageCard>
</template>

<style scoped>
.lines-block {
  margin-top: 16px;
}
.lines-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.lines-toolbar-right {
  display: inline-flex;
  align-items: center;
  gap: 12px;
}
.block-title {
  font-weight: 600;
}
.sum-ok {
  color: var(--n-success-color, #18a058);
}
.sum-bad {
  color: var(--n-error-color, #d03050);
}
/* 明细行表格，手机端横向滚动保底，min-width 撑开 */
.lines-table-wrap {
  overflow-x: auto;
}
.lines-table {
  width: 100%;
  min-width: 720px;
  border-collapse: collapse;
  font-size: 13px;
}
.lines-table th,
.lines-table td {
  border: 1px solid rgba(128, 128, 128, 0.2);
  padding: 6px 8px;
}
.lines-table th {
  background: rgba(128, 128, 128, 0.08);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}
.col-type {
  width: 140px;
}
.col-amount {
  width: 140px;
}
.col-date {
  width: 140px;
}
.col-del {
  width: 48px;
  text-align: center;
}
.num-col {
  text-align: right;
}
/* 底部操作，sticky 常驻可视，手机换行不下沉 */
.form-actions {
  position: sticky;
  bottom: 0;
  z-index: 10;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 0;
  margin-top: 16px;
}
.form-actions-left {
  display: inline-flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>