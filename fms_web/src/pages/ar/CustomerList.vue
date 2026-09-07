<script setup lang="ts">
/**
 * 客户列表，关键词筛选，trgm 模糊检索 + 服务端分页，
 * 新建/编辑走 n-modal + FormGrid，编辑回填 getCustomer，删除 NPopconfirm 软删。
 */
import { h, onMounted, ref, watch } from 'vue'
import { NButton, NInput, NInputNumber, NModal, NPopconfirm, NSelect, useMessage } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import { arApi } from '@/api/ar'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { CustomerRequest, CustomerStatus, CustomerView } from '@/types/ar'
import { formatAmount, formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const appStore = useAppStore()

/** 写操作仅 admin/operator 可见，其余角色只读，统一语义见 utils/perm */
const canWrite = useCanWrite()

const STATUS_OPTIONS = [
  { label: '启用', value: 'ACTIVE' },
  { label: '停用', value: 'INACTIVE' },
  { label: '冻结', value: 'BLOCKED' },
]

// 列表状态
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<CustomerView[]>([])
const total = ref(0)
const loading = ref(false)

let reqSeq = 0

/** 服务端分页查询，竞态序号作废旧结果 */
async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await arApi.pageCustomers({
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
onMounted(() => void load())

// 新建/编辑弹窗
const showModal = ref(false)
const editing = ref<CustomerView | null>(null)
const saving = ref(false)
const form = ref<CustomerRequest>({ companyId: '', code: '', name: '', taxNo: '', creditLimit: 0, status: 'ACTIVE' })

/** 打开弹窗，create 空表单 / edit 回填，creditLimit 空则按 0 */
function openModal(row?: CustomerView) {
  editing.value = row ?? null
  form.value = {
    companyId: appStore.companyId ?? '',
    code: row?.code ?? '',
    name: row?.name ?? '',
    taxNo: row?.taxNo ?? '',
    creditLimit: row?.creditLimit ?? 0,
    status: (row?.status as CustomerStatus) ?? 'ACTIVE',
  }
  showModal.value = true
}

async function onSave() {
  if (!form.value.companyId) {
    message.warning('请先在顶栏选择公司')
    return
  }
  if (!form.value.code || !form.value.name) {
    message.warning('客户编码与名称必填')
    return
  }
  saving.value = true
  try {
    if (editing.value) await arApi.updateCustomer(editing.value.id, form.value)
    else await arApi.createCustomer(form.value)
    message.success(editing.value ? '客户已更新' : '客户已创建')
    showModal.value = false
    await load()
  } catch (e) {
    // 全局已提示（http.ts），本地仅兜底
    if (e instanceof Error) message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function onDelete(row: CustomerView) {
  await arApi.deleteCustomer(row.id)
  message.success('客户已删除')
  await load()
}

function renderAmount(v: number) {
  return h('span', { class: 'num' }, formatAmount(v))
}

const columns: FmsColumn<CustomerView>[] = [
  { key: 'code', title: '编码', width: 110 },
  { key: 'name', title: '客户名称', width: 160 },
  { key: 'taxNo', title: '税号', width: 150, hideBelow: 'md', render: (r) => r.taxNo || '—' },
  { key: 'creditLimit', title: '信用额度', width: 120, align: 'right', hideBelow: 'sm', render: (r) => renderAmount(r.creditLimit ?? 0) },
  { key: 'status', title: '状态', width: 90, render: (r) => h(StatusTag, { status: r.status }) },
  { key: 'createdAt', title: '创建时间', width: 160, hideBelow: 'sm', render: (r) => formatDateTime(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 120,
    align: 'center',
    render: (r) =>
      canWrite.value
        ? h('span', { style: 'display:inline-flex;gap:8px' }, [
            h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openModal(r) }, { default: () => '编辑' }),
            h(NPopconfirm, { onPositiveClick: () => onDelete(r) }, {
              trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
              default: () => '确认删除该客户？',
            }),
          ])
        : '',
  },
]
</script>

<template>
  <div class="customer-list">
    <!-- 筛选区，手机 1 列 / 平板 2 列 / 桌面 3 列 -->
    <FormGrid class="filter-grid">
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
      <n-form-item label="关键词">
        <n-input v-model:value="keyword" placeholder="编码 / 名称 / 税号" clearable @update:value="onKeywordSearch" />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">客户列表</span>
      <n-button v-if="canWrite" type="primary" size="small" @click="openModal()">新建客户</n-button>
    </div>

    <DataTable v-model:page="page" v-model:size="size" :columns="columns" :data="rows" :loading="loading" :total="total" :scroll-x="900">
      <template #empty>
        <span>暂无客户数据</span>
      </template>
    </DataTable>

    <!-- 新建/编辑弹窗，FormGrid 手机单列 -->
    <n-modal v-model:show="showModal" preset="card" :title="editing ? '编辑客户' : '新建客户'" style="width: 560px; max-width: 92vw">
      <FormGrid>
        <n-form-item label="客户编码">
          <n-input v-model:value="form.code" maxlength="32" placeholder="公司内唯一" />
        </n-form-item>
        <n-form-item label="客户名称">
          <n-input v-model:value="form.name" maxlength="128" />
        </n-form-item>
        <n-form-item label="税号">
          <n-input v-model:value="form.taxNo" maxlength="32" placeholder="可选" />
        </n-form-item>
        <n-form-item label="信用额度">
          <n-input-number v-model:value="form.creditLimit" :min="0" :precision="2" :step="1000" style="width: 100%" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="form.status" :options="STATUS_OPTIONS" style="width: 100%" />
        </n-form-item>
      </FormGrid>
      <template #footer>
        <div class="modal-actions">
          <n-button @click="showModal = false">取消</n-button>
          <n-button v-if="canWrite" type="primary" :loading="saving" @click="onSave">保存</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.customer-list {
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
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
