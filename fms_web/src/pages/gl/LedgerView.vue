<script setup lang="ts">
/**
 * 明细账，科目 × 期间查询，科目可不选 = 全科目，期间与 appStore 联动自动重查，
 * 金额有符号，正=借向、负=贷向，负值标红，导出 Excel，csv 附件，带 Bearer 下载，
 * 非服务端分页，本地切片翻页，接口返回全量，前端按 page/size 切片，同 AgingPage 约定。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NDatePicker, NEmpty, NInput, useMessage } from 'naive-ui'
import dayjs from 'dayjs'
import { glApi } from '@/api/gl'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import AccountSelect from '@/components/gl/AccountSelect.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import type { LedgerView as LedgerRow } from '@/types/gl'
import { formatAmount } from '@/utils/format'

const message = useMessage()
const appStore = useAppStore()

const rows = ref<LedgerRow[]>([])
const loading = ref(false)
const exporting = ref(false)
const accountId = ref<string | null>(null)
const accountDisplay = ref('')
const page = ref(1)
const size = ref(20)

const periodTs = computed(() => (appStore.period ? dayjs(appStore.period + '-01').valueOf() : null))
function onPeriodChange(ts: number | null) {
  appStore.setPeriod(ts ? dayjs(ts).format('YYYY-MM') : dayjs().format('YYYY-MM'))
  void load() // 期间变更直接重查，watch 联动实测不可靠，双保险
}

function onSelectAccount(code: string, name: string) {
  accountDisplay.value = `${code} ${name}`
  page.value = 1
  void load()
}

/** 金额单元格，有符号净额，负值标红，.num.negative 见 styles/index.css */
function renderAmount(v: number) {
  return h('span', { class: ['num', v < 0 ? 'negative' : ''] }, formatAmount(v))
}

const columns: FmsColumn<LedgerRow>[] = [
  { key: 'accountCode', title: '科目编码', width: 110 },
  { key: 'accountName', title: '科目名称', width: 140, hideBelow: 'sm' },
  {
    key: 'direction',
    title: '方向',
    width: 70,
    hideBelow: 'sm',
    render: (r) => (r.direction === 'DEBIT' ? '借' : '贷'),
  },
  { key: 'openingBalance', title: '期初余额', width: 130, align: 'right', render: (r) => renderAmount(r.openingBalance) },
  { key: 'periodDebit', title: '本期借方', width: 130, align: 'right', render: (r) => renderAmount(r.periodDebit) },
  { key: 'periodCredit', title: '本期贷方', width: 130, align: 'right', render: (r) => renderAmount(r.periodCredit) },
  { key: 'closingBalance', title: '期末余额', width: 130, align: 'right', render: (r) => renderAmount(r.closingBalance) },
]

async function load() {
  if (!appStore.companyId) return
  loading.value = true
  try {
    rows.value = await glApi.ledger(appStore.companyId, appStore.period, accountId.value ?? undefined)
    page.value = 1 // 重新查询回到第一页
  } finally {
    loading.value = false
  }
}

/** 客户端切片，DataTable 只做展示与分页，不切片数据，同 AgingPage 约定 */
const visibleRows = computed(() => {
  const start = (page.value - 1) * size.value
  return rows.value.slice(start, start + size.value)
})

/** 每页条数变化后钳制页码，防越界空页 */
watch([page, size], () => {
  const maxPage = Math.max(1, Math.ceil(rows.value.length / size.value))
  if (page.value > maxPage) page.value = maxPage
})

/** 导出 csv，镜像 rpt 导出，downloadCsv 带 Bearer 落盘 */
async function onExport() {
  if (!appStore.companyId) return
  exporting.value = true
  try {
    await glApi.exportLedger(appStore.companyId, appStore.period, accountId.value ?? undefined)
    message.success('导出成功，请查看下载文件')
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  } finally {
    exporting.value = false
  }
}

/** 期间切换自动重查，appStore.period 联动 */
watch(() => appStore.period, () => void load())
onMounted(() => void load())
</script>

<template>
  <div class="ledger-page">
    <!-- 筛选，公司只读 + 期间 + 科目，可不选 -->
    <FormGrid>
      <n-form-item label="公司">
        <n-input :value="appStore.companyName || '默认公司'" readonly />
      </n-form-item>
      <n-form-item label="期间">
        <n-date-picker
          type="month"
          :value="periodTs"
          :clearable="false"
          style="width: 100%"
          @update:value="onPeriodChange"
        />
      </n-form-item>
      <n-form-item label="科目">
        <AccountSelect
          v-model:value="accountId"
          :display="accountDisplay"
          :company-id="appStore.companyId"
          placeholder="全部科目（可不选）"
          @select="onSelectAccount"
        />
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">明细账（{{ appStore.period }}）</span>
      <n-button size="small" :loading="exporting" @click="onExport">导出 Excel</n-button>
    </div>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :columns="columns"
      :data="visibleRows"
      :total="rows.length"
      :loading="loading"
      :scroll-x="800"
    >
      <template #empty>
        <n-empty description="当前期间无过账凭证" />
      </template>
      <template #rowDetail="{ row }">
        <div class="row-detail">
          <div>科目：{{ row.accountCode }} {{ row.accountName }}</div>
          <div>方向：{{ row.direction === 'DEBIT' ? '借' : '贷' }}</div>
          <div>期初余额：{{ formatAmount(row.openingBalance) }}</div>
          <div>本期借方：{{ formatAmount(row.periodDebit) }}</div>
          <div>本期贷方：{{ formatAmount(row.periodCredit) }}</div>
          <div>期末余额：{{ formatAmount(row.closingBalance) }}</div>
        </div>
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
.ledger-page {
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
.row-detail {
  font-size: 12px;
  line-height: 1.8;
  color: var(--fms-text-secondary);
}
</style>
