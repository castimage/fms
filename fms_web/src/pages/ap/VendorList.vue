<script setup lang="ts">
/**
 * 供应商列表（/ap/vendors），关键词/状态筛选 + 服务端分页，
 * 新建/编辑弹窗表单，n-form，手机单列，删除软删（popconfirm）。
 */
import { h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  useMessage,
} from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import { apApi } from '@/api/ap'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import MoneyText from '@/components/common/MoneyText.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { SupplierStatus, SupplierView } from '@/types/ap'
import { formatDate } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const appStore = useAppStore()
// 写操作仅 admin/operator 可见，viewer/auditor/report 只读
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '启用', value: 'ACTIVE' },
  { label: '停用', value: 'INACTIVE' },
  { label: '冻结', value: 'BLOCKED' },
]

// 筛选状态
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<SupplierView[]>([])
const total = ref(0)
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await apApi.pageSuppliers({
      page: page.value,
      size: size.value,
      companyId: appStore.companyId ?? undefined,
      keyword: keyword.value || undefined,
    })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

/** 关键词防抖（500ms），重置到第一页并重查，page 未变时直接 load，防 watch 漏触发 */
const onKeywordSearch = useDebounceFn(() => {
  if (page.value === 1) void load()
  else page.value = 1
}, 500)
watch(keyword, () => onKeywordSearch())
watch([page, size, () => appStore.companyId], () => void load())

// 新建/编辑表单
const showForm = ref(false)
const formRef = ref<FormInst | null>(null)
const formLoading = ref(false)
const editing = ref(false)
const form = ref<{ id: string; code: string; name: string; taxNo: string; creditLimit: number | null; status: SupplierStatus }>({
  id: '',
  code: '',
  name: '',
  taxNo: '',
  creditLimit: null,
  status: 'ACTIVE',
})

const rules: FormRules = {
  code: [{ required: true, message: '供应商编码必填', trigger: ['blur'] }],
  name: [{ required: true, message: '供应商名称必填', trigger: ['blur'] }],
}

function openCreate() {
  editing.value = false
  form.value = { id: '', code: '', name: '', taxNo: '', creditLimit: null, status: 'ACTIVE' }
  showForm.value = true
}
function openEdit(row: SupplierView) {
  editing.value = true
  form.value = {
    id: row.id,
    code: row.code,
    name: row.name,
    taxNo: row.taxNo ?? '',
    creditLimit: row.creditLimit,
    status: row.status,
  }
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
      companyId: appStore.companyId ?? '',
      code: form.value.code,
      name: form.value.name,
      taxNo: form.value.taxNo || undefined,
      creditLimit: form.value.creditLimit ?? undefined,
      status: form.value.status,
    }
    if (editing.value) await apApi.updateSupplier(form.value.id, req)
    else await apApi.createSupplier(req)
    message.success(editing.value ? '已保存' : '已创建')
    showForm.value = false
    await load()
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    formLoading.value = false
  }
}

async function onDelete(id: string) {
  await apApi.deleteSupplier(id)
  await load()
}

const columns: FmsColumn<SupplierView>[] = [
  { key: 'code', title: '供应商编码', width: 110 },
  { key: 'name', title: '供应商名称', width: 170 },
  { key: 'taxNo', title: '税号', width: 150, hideBelow: 'md' },
  {
    key: 'creditLimit',
    title: '信用额度',
    width: 120,
    align: 'right',
    hideBelow: 'md',
    render: (r) => h(MoneyText, { value: r.creditLimit }),
  },
  { key: 'status', title: '状态', width: 90, render: (r) => h(StatusTag, { status: r.status }) },
  { key: 'createdAt', title: '创建时间', width: 130, hideBelow: 'sm', render: (r) => formatDate(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 120,
    align: 'center',
    render: (r) => {
      const nodes: ReturnType<typeof h>[] = []
      // 写按钮仅 admin/operator 可见
      if (canWrite.value) {
        nodes.push(h(NButton, { text: true, size: 'small', onClick: () => openEdit(r) }, { default: () => '编辑' }))
        nodes.push(
          h(NPopconfirm, { onPositiveClick: () => onDelete(r.id) }, {
            trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
            default: () => '确认删除该供应商？',
          }),
        )
      }
      return h('span', { style: 'display:inline-flex;gap:8px' }, nodes)
    },
  },
]

onMounted(() => void load())
</script>

<template>
  <div class="vendor-list">
    <FormGrid class="filter-grid">
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
      <n-form-item label="关键词">
        <n-input v-model:value="keyword" placeholder="编码 / 名称" clearable />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">供应商列表</span>
      <n-button v-if="canWrite" type="primary" size="small" @click="openCreate">新建供应商</n-button>
    </div>

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
        <span>暂无供应商数据</span>
      </template>
    </DataTable>

    <n-modal
      v-model:show="showForm"
      preset="card"
      :title="editing ? '编辑供应商' : '新建供应商'"
      style="width: min(480px, calc(100vw - 32px))"
      :bordered="false"
    >
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" size="small">
        <n-form-item label="供应商编码" path="code">
          <n-input v-model:value="form.code" maxlength="32" :disabled="editing" placeholder="≤32 字符，公司内唯一" />
        </n-form-item>
        <n-form-item label="供应商名称" path="name">
          <n-input v-model:value="form.name" maxlength="128" />
        </n-form-item>
        <n-form-item label="税号">
          <n-input v-model:value="form.taxNo" maxlength="32" />
        </n-form-item>
        <n-form-item label="信用额度">
          <n-input-number v-model:value="form.creditLimit" :min="0" :precision="2" style="width: 100%" placeholder="0 = 无额度" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="form.status" :options="STATUS_OPTIONS.slice(1)" style="width: 100%" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button size="small" @click="showForm = false">取消</n-button>
        <n-button v-if="canWrite" size="small" type="primary" :loading="formLoading" @click="submitForm">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.vendor-list {
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
