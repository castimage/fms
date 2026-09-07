<script setup lang="ts">
/**
 * 组织管理，仅 ADMIN，公司 CRUD（code/name/currency/fiscalYearStart/status）+
 * 部门列表，按公司过滤，简单增删。后端 org.fn_upsert_company 按 code 幂等 upsert，
 * 删除为软删（auth.fn_soft_delete）。
 */
import { h, onMounted, ref, watch } from 'vue'
import { NButton, NInput, NPopconfirm, NSelect, useMessage } from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import {
  createCompany,
  createDepartment,
  deleteCompany,
  deleteDepartment,
  pageCompanies,
  pageDepartments,
  updateCompany,
} from '@/api/org'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { CompanyView, DepartmentView } from '@/types/org'
import { formatDate } from '@/utils/format'

const message = useMessage()

// 公司列表，服务端分页 + 关键词
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<CompanyView[]>([])
const total = ref(0)
const loading = ref(false)

let reqSeq = 0

async function loadCompanies() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await pageCompanies({ page: page.value, size: size.value, keyword: keyword.value || undefined })
    if (seq !== reqSeq) return
    rows.value = res.items
    total.value = res.total
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

function onFilterChange() {
  if (page.value === 1) void loadCompanies()
  else page.value = 1
}
const onKeywordSearch = useDebounceFn(onFilterChange, 500)
watch(keyword, () => onKeywordSearch())
watch([page, size], () => void loadCompanies())

// 公司新增/编辑弹窗
const showEdit = ref(false)
const editId = ref<string | null>(null)
const editForm = ref({ code: '', name: '', currency: 'CNY', fiscalYearStart: '', status: 'ACTIVE' })
const editLoading = ref(false)
const STATUS_OPTIONS = [
  { label: '启用', value: 'ACTIVE' },
  { label: '停用', value: 'INACTIVE' },
]

function openCreate() {
  editId.value = null
  editForm.value = { code: '', name: '', currency: 'CNY', fiscalYearStart: '', status: 'ACTIVE' }
  showEdit.value = true
}

function openEdit(r: CompanyView) {
  editId.value = r.id
  editForm.value = {
    code: r.code,
    name: r.name,
    currency: r.currency,
    fiscalYearStart: r.fiscalYearStart ?? '',
    status: r.status,
  }
  showEdit.value = true
}

async function submitCompany() {
  if (!editForm.value.code.trim() || !editForm.value.name.trim()) {
    message.warning('公司编码与名称必填')
    return
  }
  editLoading.value = true
  try {
    if (editId.value) {
      await updateCompany(editId.value, editForm.value)
    } else {
      await createCompany(editForm.value)
    }
    message.success('保存成功')
    showEdit.value = false
    await loadCompanies()
  } catch {
    // 编码冲突/校验错误已由拦截器提示
  } finally {
    editLoading.value = false
  }
}

async function removeCompany(id: string) {
  await deleteCompany(id)
  message.success('已删除')
  await loadCompanies()
}

const companyColumns: FmsColumn<CompanyView>[] = [
  { key: 'code', title: '编码', width: 120 },
  { key: 'name', title: '名称', width: 200 },
  { key: 'currency', title: '本位币', width: 90 },
  { key: 'fiscalYearStart', title: '会计年度起始', width: 140, render: (r) => formatDate(r.fiscalYearStart) },
  { key: 'status', title: '状态', width: 90 },
  { key: 'createdAt', title: '创建时间', width: 170, hideBelow: 'md', render: (r) => formatDate(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 140,
    align: 'center',
    render: (r) =>
      h('span', { style: 'display:inline-flex;gap:8px' }, [
        h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openEdit(r) }, { default: () => '编辑' }),
        h(NPopconfirm, { onPositiveClick: () => removeCompany(r.id) }, {
          trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
          default: () => '确认删除该公司？（软删）',
        }),
      ]),
  },
]

// 部门列表，按公司过滤，简单列表 + 增删
const companyOptions = ref<{ label: string; value: string }[]>([])
const deptCompanyId = ref<string | null>(null)
const deptRows = ref<DepartmentView[]>([])
const deptTotal = ref(0)
const deptPage = ref(1)
const deptSize = ref(20)
const deptLoading = ref(false)

async function loadDepartments() {
  if (!deptCompanyId.value) {
    deptRows.value = []
    deptTotal.value = 0
    return
  }
  deptLoading.value = true
  try {
    const res = await pageDepartments({
      page: deptPage.value,
      size: deptSize.value,
      companyId: deptCompanyId.value,
    })
    deptRows.value = res.items
    deptTotal.value = res.total
  } finally {
    deptLoading.value = false
  }
}

watch(deptCompanyId, () => {
  deptPage.value = 1
  void loadDepartments()
})
watch([deptPage, deptSize], () => void loadDepartments())

// 部门新增弹窗（code + name）
const showDeptModal = ref(false)
const deptForm = ref({ code: '', name: '' })
const deptSaving = ref(false)

async function submitDepartment() {
  if (!deptCompanyId.value) return
  if (!deptForm.value.code.trim() || !deptForm.value.name.trim()) {
    message.warning('部门编码与名称必填')
    return
  }
  deptSaving.value = true
  try {
    await createDepartment({ companyId: deptCompanyId.value, ...deptForm.value })
    message.success('部门已创建')
    showDeptModal.value = false
    deptForm.value = { code: '', name: '' }
    await loadDepartments()
  } catch {
    /* 拦截器已提示 */
  } finally {
    deptSaving.value = false
  }
}

async function removeDepartment(id: string) {
  await deleteDepartment(id)
  await loadDepartments()
}

const deptColumns: FmsColumn<DepartmentView>[] = [
  { key: 'code', title: '编码', width: 120 },
  { key: 'name', title: '名称', width: 200 },
  { key: 'status', title: '状态', width: 90 },
  { key: 'createdAt', title: '创建时间', width: 170, hideBelow: 'md', render: (r) => formatDate(r.createdAt) },
  {
    key: 'actions',
    title: '操作',
    width: 100,
    align: 'center',
    render: (r) =>
      h(NPopconfirm, { onPositiveClick: () => removeDepartment(r.id) }, {
        trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
        default: () => '确认删除该部门？（有子部门将 40900）',
      }),
  },
]

onMounted(async () => {
  await loadCompanies()
  try {
    const res = await pageCompanies({ page: 1, size: 100 })
    companyOptions.value = res.items.map((c) => ({ label: c.name, value: c.id }))
    if (res.items.length > 0) deptCompanyId.value = res.items[0].id
  } catch {
    // 失败全局已提示，部门选择器保持空态
  }
})
</script>

<template>
  <div class="org-manage">
    <n-tabs type="line">
      <!-- 公司管理 -->
      <n-tab-pane name="companies" tab="公司管理">
        <div class="pane">
          <FormGrid class="filter-grid">
            <n-form-item label="关键词">
              <n-input v-model:value="keyword" placeholder="编码 / 名称" clearable />
            </n-form-item>
            <n-form-item label=" ">
              <n-button type="primary" size="small" @click="openCreate">新建公司</n-button>
            </n-form-item>
          </FormGrid>
          <DataTable
            v-model:page="page"
            v-model:size="size"
            :columns="companyColumns"
            :data="rows"
            :loading="loading"
            :total="total"
            :scroll-x="960"
            :bordered="true"
          />
        </div>
      </n-tab-pane>

      <!-- 部门管理 -->
      <n-tab-pane name="departments" tab="部门管理">
        <div class="pane">
          <FormGrid class="filter-grid">
            <n-form-item label="公司">
              <n-select v-model:value="deptCompanyId" :options="companyOptions" placeholder="选择公司" clearable />
            </n-form-item>
            <n-form-item label=" ">
              <n-button type="primary" size="small" :disabled="!deptCompanyId" @click="showDeptModal = true">新建部门</n-button>
            </n-form-item>
          </FormGrid>
          <DataTable
            v-model:page="deptPage"
            v-model:size="deptSize"
            :columns="deptColumns"
            :data="deptRows"
            :loading="deptLoading"
            :total="deptTotal"
            :scroll-x="640"
            :bordered="true"
          />
        </div>
      </n-tab-pane>
    </n-tabs>

    <!-- 公司编辑弹窗 -->
    <n-modal v-model:show="showEdit" preset="card" :title="editId ? '编辑公司' : '新建公司'" style="max-width: 480px">
      <n-form label-placement="top">
        <n-form-item label="公司编码（唯一，字母/数字/连字符）">
          <n-input v-model:value="editForm.code" :disabled="!!editId" placeholder="如 FMS01" :maxlength="32" />
        </n-form-item>
        <n-form-item label="公司名称">
          <n-input v-model:value="editForm.name" placeholder="请输入公司名称" :maxlength="128" />
        </n-form-item>
        <n-form-item label="本位币（3 位字母）">
          <n-input v-model:value="editForm.currency" placeholder="CNY" :maxlength="3" />
        </n-form-item>
        <n-form-item label="会计年度起始日">
          <n-date-picker
            :formatted-value="editForm.fiscalYearStart || null"
            type="date"
            value-format="yyyy-MM-dd"
            clearable
            style="width: 100%"
            @update:formatted-value="(v) => (editForm.fiscalYearStart = v ?? '')"
          />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="editForm.status" :options="STATUS_OPTIONS" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="showEdit = false">取消</n-button>
        <n-button type="primary" :loading="editLoading" @click="submitCompany">保存</n-button>
      </template>
    </n-modal>

    <!-- 部门新建弹窗 -->
    <n-modal v-model:show="showDeptModal" preset="card" title="新建部门" style="max-width: 420px">
      <n-form label-placement="top">
        <n-form-item label="部门编码（ltree 标签仅字母/数字/下划线）">
          <n-input v-model:value="deptForm.code" placeholder="如 IT" :maxlength="64" />
        </n-form-item>
        <n-form-item label="部门名称">
          <n-input v-model:value="deptForm.name" placeholder="如 信息技术部" :maxlength="128" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="showDeptModal = false">取消</n-button>
        <n-button type="primary" :loading="deptSaving" @click="submitDepartment">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.org-manage {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pane {
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
</style>
