<script setup lang="ts">
/**
 * 期间管理，结账/反结账操作页。期间列表按所选年补齐 12 个月，各行状态以 gl.periods 实际
 * 落库行为准（打开页面与切换公司时经 listPeriods 拉取覆盖），结账/反结账成功后同步刷新。
 *
 * 完整性预检，未完成凭证/断号由后端 fn_close_period 把关，反结账须原因 + fms.force_reopen 开关。
 * 期间行被结账后，trg_period_guard/trg_period_voucher_guard 会联动锁定新凭证写入。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NInput, NSelect, NTag, useMessage } from 'naive-ui'
import { glApi } from '@/api/gl'
import { pageCompanies } from '@/api/org'
import { useAppStore } from '@/stores/app'
import FormGrid from '@/components/common/FormGrid.vue'
import DataTable from '@/components/common/DataTable.vue'
import type { FmsColumn } from '@/components/common/DataTable.vue'
import { useCanWrite } from '@/utils/perm'

const message = useMessage()
const appStore = useAppStore()
const canWrite = useCanWrite()

type PeriodStatus = 'OPEN' | 'CLOSED'

/** 公司选择器，与 Layout 顶栏同源 pageCompanies，rls 收敛可见公司 */
const companyOptions = ref<{ label: string; value: string }[]>([])
const companyId = ref<string | null>(appStore.companyId)

/** 年度，缺省取全局期间所在年，可前后翻 1 年覆盖跨年结账场景 */
const year = ref(new Date(appStore.period + '-01').getFullYear())
const yearOptions = computed(() => [year.value - 1, year.value, year.value + 1].map((y) => ({ label: `${y}年`, value: y })))

/** 期间行，本地补齐 12 个月，状态取自 gl.periods 实际落库行 */
interface PeriodRow {
  period: string
  status: PeriodStatus
}

const statusMap = ref<Record<string, PeriodStatus>>({})

/** 拉取已存在的期间行覆盖状态，未创建的月份保持 OPEN，RLS 按公司范围收敛 */
async function loadPeriods() {
  if (!companyId.value) {
    statusMap.value = {}
    return
  }
  try {
    const rows = await glApi.listPeriods(companyId.value)
    const next: Record<string, PeriodStatus> = {}
    for (const r of rows) next[r.period] = r.status
    statusMap.value = next
  } catch (e) {
    if (e instanceof Error) message.error(e.message)
  }
}

const periods = computed<PeriodRow[]>(() =>
  Array.from({ length: 12 }, (_, i) => {
    const p = `${year.value}-${String(i + 1).padStart(2, '0')}`
    return { period: p, status: statusMap.value[p] ?? 'OPEN' }
  }),
)

/** 公司切换后全局上下文同步，列表页/凭证页 watch appStore 自动刷新，与顶栏选择器同一语义 */
watch(companyId, (v) => {
  const opt = companyOptions.value.find((o) => o.value === v)
  appStore.setCompany(v, opt?.label ?? '')
  void loadPeriods()
})

// 结账 / 反结账，确认弹窗 + 原因输入，结账无需原因，反结账必填审计留痕
const opModal = ref(false)
const opTarget = ref<{ period: string; action: 'close' | 'reopen' } | null>(null)
const reason = ref('')
const opLoading = ref(false)

function askAction(row: PeriodRow, action: 'close' | 'reopen') {
  opTarget.value = { period: row.period, action }
  reason.value = ''
  opModal.value = true
}

async function confirmOp() {
  if (!opTarget.value || !companyId.value) return
  const { period, action } = opTarget.value
  if (action === 'reopen' && !reason.value.trim()) {
    message.warning('反结账原因必填（审计留痕）')
    return
  }
  opLoading.value = true
  try {
    if (action === 'close') await glApi.closePeriod(companyId.value, period)
    else await glApi.reopenPeriod(companyId.value, period, reason.value.trim())
    // 动作成功后回读实际状态，与后端落库行保持一致
    await loadPeriods()
    message.success(`${period} 已${action === 'close' ? '结账' : '反结账'}`)
    opModal.value = false
  } catch {
    // 40900，预检失败/非末位期间/force_reopen 未开等已由 http 拦截器提示
  } finally {
    opLoading.value = false
  }
}

/** 当前全局期间的状态标签，期间选择器旁显示 OPEN/CLOSED，取值来自后端回显 */
const currentStatus = computed<PeriodStatus | null>(() => statusMap.value[appStore.period] ?? null)

function renderStatusTag(status: PeriodStatus) {
  return h(NTag, { type: status === 'OPEN' ? 'success' : 'error', size: 'small', bordered: false }, { default: () => status })
}

const columns: FmsColumn<PeriodRow>[] = [
  { key: 'period', title: '期间', width: 160 },
  {
    key: 'status',
    title: '状态',
    width: 120,
    render: (r) => renderStatusTag(r.status),
  },
  {
    key: 'actions',
    title: '操作',
    width: 220,
    align: 'center',
    render: (r) => {
      if (!canWrite.value) return h('span', {}, '只读')
      if (r.status === 'OPEN') {
        return h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => askAction(r, 'close') }, { default: () => '结账' })
      }
      // CLOSED 则反结账，确认弹窗 + 原因必填
      return h(NButton, { text: true, type: 'warning', size: 'small', onClick: () => askAction(r, 'reopen') }, { default: () => '反结账' })
    },
  },
]

onMounted(async () => {
  try {
    const res = await pageCompanies({ page: 1, size: 100 })
    companyOptions.value = res.items.map((c) => ({ label: c.name, value: c.id }))
    if (!companyId.value && res.items.length > 0) companyId.value = res.items[0].id
    await loadPeriods()
  } catch {
    // 失败全局已提示，选择器保持空态
  }
})
</script>

<template>
  <div class="period-manage">
    <FormGrid class="filter-grid">
      <n-form-item label="公司">
        <n-select v-model:value="companyId" :options="companyOptions" placeholder="选择公司" clearable />
      </n-form-item>
      <n-form-item label="年度">
        <n-select v-model:value="year" :options="yearOptions" style="max-width: 160px" />
      </n-form-item>
      <n-form-item label="当前期间状态">
        <n-tag v-if="currentStatus" :type="currentStatus === 'OPEN' ? 'success' : 'error'" :bordered="false">
          {{ appStore.period }} {{ currentStatus }}
        </n-tag>
        <span v-else class="status-unknown">OPEN（未见落库期间行）</span>
      </n-form-item>
    </FormGrid>

    <div class="toolbar">
      <span class="toolbar-title">期间列表（{{ year }} 年）</span>
      <span class="toolbar-hint">状态取自 gl.periods 实际落库行；未创建的月份显示为 OPEN</span>
    </div>

    <DataTable :columns="columns" :data="periods" :scroll-x="520" local-pagination :bordered="true">
      <template #empty>
        <span>请先选择公司</span>
      </template>
    </DataTable>

    <!-- 结账/反结账确认弹窗 -->
    <n-modal v-model:show="opModal" preset="card" :title="opTarget?.action === 'close' ? '确认结账' : '确认反结账'" style="max-width: 440px">
      <template v-if="opTarget">
        <p class="op-desc">
          {{ opTarget.action === 'close'
            ? `期间 ${opTarget.period} 结账后进入 CLOSED 锁定，未完成凭证/断号预检由后端把关（违规 40900）`
            : `期间 ${opTarget.period} 反结账需原因（审计留痕）+ fms.force_reopen 开关开启，仅支持末位期间` }}
        </p>
        <n-input
          v-model:value="reason"
          type="textarea"
          :placeholder="opTarget.action === 'reopen' ? '请输入反结账原因（必填）' : '结账原因（可选，仅作扩展占位）'"
          :rows="3"
          :maxlength="500"
          show-count
        />
      </template>
      <template #footer>
        <n-button @click="opModal = false">取消</n-button>
        <n-button type="primary" :loading="opLoading" @click="confirmOp">确认{{ opTarget?.action === 'close' ? '结账' : '反结账' }}</n-button>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.period-manage {
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
  align-items: baseline;
  gap: 12px;
}
.toolbar-title {
  font-weight: 600;
}
.toolbar-hint {
  font-size: 12px;
  color: var(--fms-text-secondary);
}
.op-desc {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--fms-text-secondary);
}
.status-unknown {
  font-size: 13px;
  color: var(--fms-text-secondary);
}
</style>
