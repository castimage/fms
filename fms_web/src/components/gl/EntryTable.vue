<script setup lang="ts">
/**
 * EntryTable 分录录入表格，凭证录入/编辑共用，v-model:lines 编辑态分录数组，
 * 科目必选且末级，借/贷互斥，输入一方自动清另一方，借贷差额按"分"整数累加（utils/money），
 * 变化时 emit balance-change，移动端横向滚动 + sticky 首两列。
 */
import { computed, watch } from 'vue'
import { NButton, NInput, NSelect } from 'naive-ui'
import AccountSelect from './AccountSelect.vue'
import { toFenOrZero, fromFen } from '@/utils/money'
import { useBreakpoint } from '@/utils/breakpoint'
import { useId } from 'vue'

/** 编辑态分录行，accountCode/accountName 供科目选择器回显 */
export interface EditLine {
  key: string
  accountId: string | null
  accountCode: string
  accountName: string
  summary: string
  debit: string
  credit: string
  currency: string
}

const props = defineProps<{
  companyId?: string | null
  /** 只读模式，禁用全部录入与删除，编辑回填时保留原值 */
  readonly?: boolean
}>()
const { companyId, readonly } = props

const lines = defineModel<EditLine[]>('lines', { default: () => [] })

const emit = defineEmits<{ balanceChange: [fen: number] }>()

const { isMobile } = useBreakpoint()
const uid = useId()

let seq = 0
/** 生成空行，本地 key 唯一，行号取展示下标 */
function newLine(): EditLine {
  seq += 1
  return {
    key: `${uid}-${Date.now()}-${seq}`,
    accountId: null,
    accountCode: '',
    accountName: '',
    summary: '',
    debit: '',
    credit: '',
    currency: 'CNY',
  }
}

function addLine() {
  lines.value.push(newLine())
}
function removeLine(i: number) {
  lines.value.splice(i, 1)
}

/** 借/贷互斥，输入一侧自动清另一侧，单边分录 */
function onDebitInput(line: EditLine, v: string) {
  line.debit = v
  if (v) line.credit = ''
}
function onCreditInput(line: EditLine, v: string) {
  line.credit = v
  if (v) line.debit = ''
}

/** 科目选中回填，AccountSelect 只允许末级 */
function onSelectAccount(line: EditLine, code: string, name: string) {
  line.accountCode = code
  line.accountName = name
}

/** 借贷合计与差额，分整数运算，杜绝浮点误差 */
const totalDebitFen = computed(() => lines.value.reduce((s, l) => s + toFenOrZero(l.debit), 0))
const totalCreditFen = computed(() => lines.value.reduce((s, l) => s + toFenOrZero(l.credit), 0))
const diffFen = computed(() => totalDebitFen.value - totalCreditFen.value)
const balanced = computed(() => diffFen.value === 0)

watch(diffFen, (v) => emit('balanceChange', v), { immediate: true })

const currencyOptions = [{ label: 'CNY', value: 'CNY' }]
</script>

<template>
  <div class="entry-table">
    <div class="entry-scroll">
      <table class="entry-table-inner">
        <thead>
          <tr>
            <th class="sticky-col col-no">行号</th>
            <th class="col-summary">摘要</th>
            <th class="sticky-col col-account">科目（末级）</th>
            <th class="col-money">借方金额</th>
            <th class="col-money">贷方金额</th>
            <th class="col-currency">币种</th>
            <th class="col-op">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(line, i) in lines" :key="line.key">
            <td class="sticky-col col-no">{{ i + 1 }}</td>
            <td class="col-summary">
              <n-input
                v-model:value="line.summary"
                :maxlength="500"
                :disabled="readonly"
                placeholder="分录摘要"
                size="small"
              />
            </td>
            <td class="sticky-col col-account">
              <AccountSelect
                v-model:value="line.accountId"
                :company-id="companyId"
                :display="`${line.accountCode} ${line.accountName}`.trim()"
                :disabled="readonly"
                @select="(code, name) => onSelectAccount(line, code, name)"
              />
            </td>
            <td class="col-money">
              <n-input
                :value="line.debit"
                :disabled="readonly"
                inputmode="decimal"
                placeholder="0.00"
                size="small"
                @update:value="(v) => onDebitInput(line, (v ?? '').replace(/[^\d.]/g, ''))"
              />
            </td>
            <td class="col-money">
              <n-input
                :value="line.credit"
                :disabled="readonly"
                inputmode="decimal"
                placeholder="0.00"
                size="small"
                @update:value="(v) => onCreditInput(line, (v ?? '').replace(/[^\d.]/g, ''))"
              />
            </td>
            <td class="col-currency">
              <n-select v-model:value="line.currency" :options="currencyOptions" size="small" :disabled="readonly" />
            </td>
            <td class="col-op">
              <n-button text type="error" :disabled="readonly" @click="removeLine(i)">删除</n-button>
            </td>
          </tr>
          <tr v-if="!lines.length">
            <td colspan="7" class="entry-empty">暂无分录，点击下方"添加分录"开始录入</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="entry-footer">
      <n-button size="small" type="primary" secondary :disabled="readonly" @click="addLine">
        + 添加分录
      </n-button>
      <span class="entry-footer-sums" v-if="!isMobile">
        借方合计 {{ fromFen(totalDebitFen) }} ｜ 贷方合计 {{ fromFen(totalCreditFen) }} ｜
        差额 {{ fromFen(Math.abs(diffFen)) }}
      </span>
      <span :class="['entry-balance', balanced ? 'is-balanced' : 'is-unbalanced']">
        {{ balanced ? '借贷平衡' : '借贷不平衡' }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.entry-table {
  border: 1px solid var(--n-border-color, #e5e7eb);
  border-radius: 6px;
  overflow: hidden;
}
.entry-scroll {
  overflow: auto;
  -webkit-overflow-scrolling: touch;
}
.entry-table-inner {
  width: 100%;
  min-width: 760px;
  border-collapse: collapse;
  font-size: 13px;
}
.entry-table-inner th,
.entry-table-inner td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--n-border-color, #f0f0f0);
  text-align: left;
  white-space: nowrap;
}
.entry-table-inner th {
  background: var(--n-color, #fafafa);
  font-weight: 600;
}
.col-no {
  width: 48px;
  text-align: center;
}
.col-summary {
  min-width: 180px;
}
.col-account {
  min-width: 220px;
}
.col-money {
  width: 130px;
}
.col-currency {
  width: 90px;
}
.col-op {
  width: 64px;
  text-align: center;
}
/* 移动端 sticky 首两列 */
.sticky-col {
  position: sticky;
  background: var(--n-color, #fff);
  z-index: 1;
}
.sticky-col.col-no {
  left: 0;
}
.sticky-col.col-account {
  left: 48px;
}
html.dark .sticky-col {
  background: var(--n-color, #1e1e1e);
}
.entry-empty {
  text-align: center;
  color: #999;
  padding: 24px 0 !important;
}
.entry-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: var(--n-color, #fff);
  border-top: 1px solid var(--n-border-color, #f0f0f0);
}
.entry-footer-sums {
  color: #888;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.entry-balance {
  margin-left: auto;
  font-size: 12px;
  font-weight: 600;
}
.is-balanced {
  color: var(--n-success-color, #18a058);
}
.is-unbalanced {
  color: var(--n-error-color, #d03050);
}
</style>
