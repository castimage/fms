<script setup lang="ts">
/**
 * 资产卡片列表（/fa/assets），状态/关键词筛选 + 服务端分页，新建/编辑弹窗，表单校验，
 * 在用/计提中行可处置/报废/删除，需日期+原因，原值开始计提后冻结禁改，
 * 工具栏计提折旧弹窗，选期间幂等计提，同期间已确认则跳过。
 */
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import {
  NButton,
  NDatePicker,
  NForm,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  useMessage,
} from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import dayjs from 'dayjs'
import { faApi } from '@/api/fa'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { AssetStatus, AssetView, DepreciationMethod } from '@/types/fa'
import { formatDate } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'
import type { VNode } from 'vue'

const message = useMessage()
const appStore = useAppStore()
// 写角色（admin/operator）才可见写按钮，其余角色只读
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '在用', value: 'ACTIVE' },
  { label: '计提中', value: 'DEPRECIATING' },
  { label: '已处置', value: 'DISPOSED' },
  { label: '已报废', value: 'SCRAPPED' },
]
const METHOD_OPTIONS = [
  { label: '直线法', value: 'STRAIGHT_LINE' },
  { label: '双倍余额递减', value: 'DOUBLE_DECLINING' },
]

const status = ref<AssetStatus | ''>('')
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<AssetView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await faApi.pageAssets({
      page: page.value,
      size: size.value,
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

/** 筛选变化重置到第一页并重查，page 未变时直接 load，避免 watch 漏触发 */
function onFilterChange() {
  if (page.value === 1) void load()
  else page.value = 1
}
const onKeywordSearch = useDebounceFn(onFilterChange, 500)
watch(keyword, () => onKeywordSearch())
watch(status, onFilterChange)
watch([page, size], () => void load())
onMounted(() => void load())

const showForm = ref(false)
const formRef = ref<FormInst | null>(null)
const formLoading = ref(false)
const editingId = ref('')

const form = reactive({
  assetCode: '',
  category: '',
  originalValue: null as number | null,
  residualValue: 0,
  lifeMonths: null as number | null,
  depreciationMethod: 'STRAIGHT_LINE' as DepreciationMethod,
  startDate: dayjs().format('YYYY-MM-DD'),
})
const startTs = computed(() => (form.startDate ? dayjs(form.startDate).valueOf() : null))

const rules: FormRules = {
  assetCode: [{ required: true, message: '资产编码必填', trigger: 'blur' }],
  category: [{ required: true, message: '资产类别必填', trigger: 'blur' }],
  originalValue: [{ required: true, message: '原值必填', trigger: ['blur', 'change'] }],
  lifeMonths: [{ required: true, message: '使用月数必填', trigger: ['blur', 'change'] }],
}

function resetForm() {
  Object.assign(form, {
    assetCode: '',
    category: '',
    originalValue: null,
    residualValue: 0,
    lifeMonths: null,
    depreciationMethod: 'STRAIGHT_LINE',
    startDate: dayjs().format('YYYY-MM-DD'),
  })
}

function openCreate() {
  editingId.value = ''
  resetForm()
  showForm.value = true
}

function openEdit(row: AssetView) {
  editingId.value = row.id
  Object.assign(form, {
    assetCode: row.assetCode,
    category: row.category,
    originalValue: row.originalValue,
    residualValue: row.residualValue,
    lifeMonths: row.lifeMonths,
    depreciationMethod: row.depreciationMethod,
    startDate: row.startDate ? dayjs(row.startDate).format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD'),
  })
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
    const req = {
      assetCode: form.assetCode.trim(),
      category: form.category.trim(),
      originalValue: form.originalValue ?? 0,
      residualValue: form.residualValue,
      lifeMonths: form.lifeMonths ?? 1,
      depreciationMethod: form.depreciationMethod,
      startDate: form.startDate,
    }
    if (editingId.value) await faApi.updateAsset(editingId.value, req)
    else await faApi.createAsset(req)
    message.success(editingId.value ? '资产已更新' : '资产卡片已创建')
    showForm.value = false
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    formLoading.value = false
  }
}

async function onDelete(id: string) {
  await faApi.deleteAsset(id)
  message.success('已删除')
  await load()
}

const disposeVisible = ref(false)
const disposeKind = ref<'dispose' | 'scrap'>('dispose')
const disposeId = ref('')
const disposeLoading = ref(false)
const disposeForm = reactive({ disposeDate: dayjs().format('YYYY-MM-DD'), reason: '' })
const disposeTs = computed(() => (disposeForm.disposeDate ? dayjs(disposeForm.disposeDate).valueOf() : null))

function openDispose(row: AssetView, kind: 'dispose' | 'scrap') {
  disposeKind.value = kind
  disposeId.value = row.id
  disposeForm.disposeDate = dayjs().format('YYYY-MM-DD')
  disposeForm.reason = ''
  disposeVisible.value = true
}

async function submitDispose() {
  disposeLoading.value = true
  try {
    // reason 仅留日志不落库，后端不强制
    const req = { disposeDate: disposeForm.disposeDate, reason: disposeForm.reason || undefined }
    if (disposeKind.value === 'dispose') await faApi.disposeAsset(disposeId.value, req)
    else await faApi.scrapAsset(disposeId.value, req)
    message.success(disposeKind.value === 'dispose' ? '已处置' : '已报废')
    disposeVisible.value = false
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    disposeLoading.value = false
  }
}

const runVisible = ref(false)
const runLoading = ref(false)
const runPeriod = ref(appStore.period)
const runPeriodTs = computed(() => (runPeriod.value ? dayjs(runPeriod.value + '-01').valueOf() : null))
function onRunPeriodChange(ts: number | null) {
  runPeriod.value = ts ? dayjs(ts).format('YYYY-MM') : dayjs().format('YYYY-MM')
}

async function runDepr() {
  if (!appStore.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  runLoading.value = true
  try {
    const count = await faApi.runDepreciation(appStore.companyId, runPeriod.value)
    message.success(`计提完成，共 ${count} 项资产（同期间已计提则跳过）`)
    runVisible.value = false
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    runLoading.value = false
  }
}

/** 可变更态，处置/报废/删除仅 ACTIVE/DEPRECIATING，与后端触发器口径一致 */
function actionable(r: AssetView): boolean {
  return r.status === 'ACTIVE' || r.status === 'DEPRECIATING'
}

const columns: FmsColumn<AssetView>[] = [
  { key: 'assetCode', title: '资产编码', width: 110 },
  { key: 'category', title: '类别', width: 100, hideBelow: 'sm' },
  {
    key: 'originalValue',
    title: '原值',
    width: 120,
    align: 'right',
    render: (r) => h(MoneyText, { value: r.originalValue, symbol: '¥' }),
  },
  {
    key: 'accumulatedDepreciation',
    title: '累计折旧',
    width: 120,
    align: 'right',
    hideBelow: 'md',
    render: (r) => h(MoneyText, { value: r.accumulatedDepreciation, symbol: '¥' }),
  },
  {
    key: 'netValue',
    title: '净值',
    width: 120,
    align: 'right',
    hideBelow: 'md',
    render: (r) => h(MoneyText, { value: r.netValue, symbol: '¥' }),
  },
  { key: 'lifeMonths', title: '使用月数', width: 90, hideBelow: 'sm' },
  { key: 'startDate', title: '开始计提', width: 110, hideBelow: 'sm', render: (r) => formatDate(r.startDate) },
  { key: 'status', title: '状态', width: 90, render: (r) => h(StatusTag, { status: r.status }) },
  {
    key: 'actions',
    title: '操作',
    width: 190,
    align: 'center',
    render: (r) => {
      // 行内写按钮仅 admin/operator 可见，只读角色操作列留空
      const nodes: VNode[] = []
      if (canWrite.value) {
        nodes.push(h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openEdit(r) }, { default: () => '编辑' }))
        if (actionable(r)) {
          nodes.push(
            h(NButton, { text: true, size: 'small', onClick: () => openDispose(r, 'dispose') }, { default: () => '处置' }),
            h(NButton, { text: true, size: 'small', onClick: () => openDispose(r, 'scrap') }, { default: () => '报废' }),
            h(NPopconfirm, { onPositiveClick: () => onDelete(r.id) }, {
              trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
              default: () => '确认删除该资产？',
            }),
          )
        }
      }
      return h('span', { style: 'display:inline-flex;gap:8px;flex-wrap:wrap' }, nodes)
    },
  },
]
</script>

<template>
  <div class="asset-list">
    <!-- 筛选区，FormGrid 手机塌缩单列 -->
    <FormGrid class="filter-grid">
      <n-form-item label="状态">
        <n-select v-model:value="status" :options="STATUS_OPTIONS" style="width: 100%" />
      </n-form-item>
      <n-form-item label="关键词">
        <n-input v-model:value="keyword" placeholder="资产编码 / 类别" clearable />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">资产卡片</span>
      <div class="toolbar-actions">
        <n-button v-if="canWrite" size="small" @click="runVisible = true">计提折旧</n-button>
        <n-button v-if="canWrite" type="primary" size="small" @click="openCreate">新建资产</n-button>
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
        <span>暂无资产数据</span>
      </template>
    </DataTable>

    <!-- 新建/编辑资产弹窗 -->
    <n-modal
      v-model:show="showForm"
      preset="card"
      :title="editingId ? '编辑资产' : '新建资产'"
      style="width: min(520px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" size="small">
        <n-form-item label="资产编码" path="assetCode">
          <n-input v-model:value="form.assetCode" maxlength="64" placeholder="公司内唯一" />
        </n-form-item>
        <n-form-item label="资产类别" path="category">
          <n-input v-model:value="form.category" maxlength="64" placeholder="如 办公设备 / 运输工具" />
        </n-form-item>
        <n-form-item label="原值" path="originalValue">
          <n-input-number v-model:value="form.originalValue" :min="0" :precision="2" style="width: 100%" placeholder="≥ 0" />
        </n-form-item>
        <n-form-item label="残值">
          <n-input-number v-model:value="form.residualValue" :min="0" :precision="2" style="width: 100%" placeholder="缺省 0" />
        </n-form-item>
        <n-form-item label="使用月数" path="lifeMonths">
          <n-input-number v-model:value="form.lifeMonths" :min="1" :max="600" style="width: 100%" placeholder="1–600 月（50 年上限）" />
        </n-form-item>
        <n-form-item label="折旧方法">
          <n-select v-model:value="form.depreciationMethod" :options="METHOD_OPTIONS" style="width: 100%" />
        </n-form-item>
        <n-form-item label="开始计提日期">
          <n-date-picker v-model:value="startTs" type="date" :clearable="false" style="width: 100%" @update:value="(v: number | null) => (form.startDate = v ? dayjs(v).format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD'))" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showForm = false">取消</n-button>
        <n-button v-if="canWrite" size="small" type="primary" :loading="formLoading" @click="submitForm">
          {{ editingId ? '保存修改' : '创建卡片' }}
        </n-button>
      </template>
    </n-modal>

    <!-- 处置/报废弹窗 -->
    <n-modal
      v-model:show="disposeVisible"
      preset="card"
      :title="disposeKind === 'dispose' ? '资产处置' : '资产报废'"
      style="width: min(420px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-form label-placement="top" size="small">
        <n-form-item label="处置/报废日期">
          <n-date-picker v-model:value="disposeTs" type="date" :clearable="false" style="width: 100%" @update:value="(v: number | null) => (disposeForm.disposeDate = v ? dayjs(v).format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD'))" />
        </n-form-item>
        <n-form-item label="原因（仅留日志）">
          <n-input v-model:value="disposeForm.reason" type="textarea" maxlength="200" placeholder="可选" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="disposeVisible = false">取消</n-button>
        <n-button v-if="canWrite" size="small" type="primary" :loading="disposeLoading" @click="submitDispose">
          {{ disposeKind === 'dispose' ? '确认处置' : '确认报废' }}
        </n-button>
      </template>
    </n-modal>

    <!-- 计提折旧弹窗，同公司同期间已确认批次则幂等跳过 -->
    <n-modal
      v-model:show="runVisible"
      preset="card"
      title="计提折旧"
      style="width: min(420px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-form label-placement="top" size="small">
        <n-form-item label="公司">
          <n-input :value="appStore.companyName || '默认公司'" readonly />
        </n-form-item>
        <n-form-item label="计提期间">
          <n-date-picker v-model:value="runPeriodTs" type="month" :clearable="false" style="width: 100%" @update:value="onRunPeriodChange" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="runVisible = false">取消</n-button>
        <n-button v-if="canWrite" size="small" type="primary" :loading="runLoading" @click="runDepr">开始计提</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.asset-list {
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
  gap: 8px;
  flex-wrap: wrap;
}
.toolbar-title {
  font-weight: 600;
}
.toolbar-actions {
  display: inline-flex;
  gap: 8px;
}
</style>
