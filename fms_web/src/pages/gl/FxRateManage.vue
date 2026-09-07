<script setup lang="ts">
/**
 * 汇率维护，按公司+期间管理期末汇率表 CRUD，PUT/POST 均 upsert，同公司×币种×期间已存在则更新，
 * 软删行复活，重复提交 = 更新而非冲突。
 *
 * 汇率是期末调汇的数据依据，无汇率记录的外币科目在
 * 调汇时跳过，"执行期末调汇"调 POST /api/gl/fx-adjustment 手动触发，cron 每月 1 日自动调，
 * 返回生成的调汇凭证数，幂等防重复。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NInput, NInputNumber, NPopconfirm, NSelect, useMessage } from 'naive-ui'
import dayjs from 'dayjs'
import { useDebounceFn } from '@vueuse/core'
import { glApi } from '@/api/gl'
import { pageCompanies } from '@/api/org'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { FxRateView } from '@/types/gl'
import { formatDateTime } from '@/utils/format'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const appStore = useAppStore()
const canWrite = useCanWrite()

const companyOptions = ref<{ label: string; value: string }[]>([])
const companyId = ref<string | null>(appStore.companyId)
/** 期间（YYYY-MM），缺省取全局期间 */
const periodTs = computed(() => (appStore.period ? dayjs(appStore.period + '-01').valueOf() : dayjs().valueOf()))
const period = ref(appStore.period || dayjs().format('YYYY-MM'))
const currencyFilter = ref('')

const rows = ref<FxRateView[]>([])
const loading = ref(false)

/** 请求序号，旧结果作废，竞态防护 */
let reqSeq = 0

async function load() {
  if (!companyId.value) {
    rows.value = []
    return
  }
  const seq = ++reqSeq
  loading.value = true
  try {
    rows.value = await glApi.listFxRates(companyId.value, period.value, currencyFilter.value || undefined)
    if (seq !== reqSeq) return
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

function onPeriodChange(ts: number | null) {
  period.value = ts ? dayjs(ts).format('YYYY-MM') : dayjs().format('YYYY-MM')
}

watch(companyId, () => void load())
watch(period, () => void load())
/** 币种过滤防抖（500ms）后重查，避免逐键请求 */
watch(currencyFilter, useDebounceFn(() => void load(), 500))

/** 单行保存，upsert，同币种×期间重复提交即更新，无 id 也安全 */
const savingId = ref<string | null>(null)
async function saveRow(r: FxRateView) {
  if (!companyId.value) return
  if (!/^[A-Z]{3}$/.test(r.currency)) {
    message.warning('币种须为 3 位大写 ISO 代码')
    return
  }
  if (!(r.rate > 0)) {
    message.warning('汇率必须大于 0')
    return
  }
  savingId.value = r.id
  try {
    await glApi.saveFxRate({ companyId: companyId.value, currency: r.currency, period: period.value, rate: r.rate })
    message.success(`${r.currency} 汇率已保存`)
    await load()
  } catch {
    // 校验/冲突已由 http 拦截器提示
  } finally {
    savingId.value = null
  }
}

/** 新增行，保存成功后并入列表 */
const newCurrency = ref('')
const newRate = ref<number | null>(null)
const adding = ref(false)
async function addRow() {
  if (!companyId.value) return
  const currency = newCurrency.value.trim().toUpperCase()
  if (!/^[A-Z]{3}$/.test(currency)) {
    message.warning('币种须为 3 位大写 ISO 代码（如 USD）')
    return
  }
  if (!newRate.value || !(newRate.value > 0)) {
    message.warning('汇率必须大于 0')
    return
  }
  adding.value = true
  try {
    await glApi.saveFxRate({ companyId: companyId.value, currency, period: period.value, rate: newRate.value })
    message.success(`${currency} 汇率已保存`)
    newCurrency.value = ''
    newRate.value = null
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    adding.value = false
  }
}

async function removeRow(id: string) {
  await glApi.deleteFxRate(id)
  await load()
}

/** 执行期末调汇，成功后提示生成的凭证数，0 = 无外币科目余额或无汇率记录，属正常 */
const adjusting = ref(false)
async function runAdjustment() {
  if (!companyId.value) return
  adjusting.value = true
  try {
    const count = await glApi.runFxAdjustment(companyId.value, period.value)
    message.success(
      count > 0
        ? `期末调汇完成，已生成 ${count} 张调汇凭证草稿`
        : '期末调汇完成：本次无调汇凭证产生（无外币科目余额或无汇率记录）',
    )
  } catch {
    /* 拦截器已提示，如调汇凭证已存在防重复 40900 */
  } finally {
    adjusting.value = false
  }
}

const columns: FmsColumn<FxRateView>[] = [
  { key: 'currency', title: '币种', width: 120 },
  { key: 'period', title: '期间', width: 120, hideBelow: 'md' },
  {
    key: 'rate',
    title: '期末汇率',
    width: 180,
    render: (r) =>
      h(NInputNumber, {
        value: r.rate,
        min: 0.000001,
        precision: 6,
        step: 0.01,
        disabled: !canWrite.value,
        onUpdateValue: (v) => (r.rate = v ?? 0),
      }),
  },
  { key: 'updatedAt', title: '更新时间', width: 170, hideBelow: 'md', render: (r) => (r.updatedAt ? formatDateTime(r.updatedAt) : '—') },
  {
    key: 'actions',
    title: '操作',
    width: 160,
    align: 'center',
    render: (r) => {
      if (!canWrite.value) return h('span', {}, '只读')
      return h('span', { style: 'display:inline-flex;gap:8px' }, [
        h(NButton, { text: true, type: 'primary', size: 'small', loading: savingId.value === r.id, onClick: () => saveRow(r) }, { default: () => '保存' }),
        h(NPopconfirm, { onPositiveClick: () => removeRow(r.id) }, {
          trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
          default: () => '确认删除该汇率？（软删，调汇自动排除）',
        }),
      ])
    },
  },
]

onMounted(async () => {
  try {
    const res = await pageCompanies({ page: 1, size: 100 })
    companyOptions.value = res.items.map((c) => ({ label: c.name, value: c.id }))
    if (!companyId.value && res.items.length > 0) companyId.value = res.items[0].id
    if (companyId.value) await load()
  } catch {
    // 失败全局已提示，选择器保持空态
  }
})
</script>

<template>
  <div class="fx-rate-manage">
    <FormGrid class="filter-grid">
      <n-form-item label="公司">
        <n-select v-model:value="companyId" :options="companyOptions" placeholder="选择公司" clearable />
      </n-form-item>
      <n-form-item label="期间">
        <n-date-picker type="month" :value="periodTs" :clearable="false" style="width: 100%" @update:value="onPeriodChange" />
      </n-form-item>
      <n-form-item label="币种过滤">
        <n-input v-model:value="currencyFilter" placeholder="如 USD（可选）" clearable />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">期末汇率（{{ period }}）</span>
      <div class="toolbar-actions">
        <template v-if="canWrite">
          <n-input v-model:value="newCurrency" placeholder="币种（3 位代码）" class="new-currency" />
          <n-input-number v-model:value="newRate" :min="0.000001" :precision="6" placeholder="汇率" class="new-rate" />
          <n-button size="small" :loading="adding" @click="addRow">新增</n-button>
        </template>
        <n-button size="small" type="primary" :loading="adjusting" :disabled="!companyId" @click="runAdjustment">
          执行期末调汇
        </n-button>
      </div>
    </div>

    <DataTable :columns="columns" :data="rows" :loading="loading" :scroll-x="760" :bordered="true">
      <template #empty>
        <span>{{ companyId ? '暂无汇率记录，可新增' : '请先选择公司' }}</span>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.fx-rate-manage {
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
  flex-wrap: wrap;
  gap: 8px;
}
.toolbar-title {
  font-weight: 600;
}
.toolbar-actions {
  display: inline-flex;
  gap: 8px;
  align-items: center;
}
.new-currency {
  width: 130px;
}
.new-rate {
  width: 130px;
}
</style>
