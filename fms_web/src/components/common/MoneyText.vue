<script setup lang="ts">
/**
 * MoneyText 金额文本组件，千分位 + 正负色，value 按元，formatAmount 千分位 2 位小数，
 * fen 按分整数，fromFen 杜绝浮点误差，优先于 value，负数标红（.num.negative）、空值显示 —，
 * symbol 为币种前缀，align 控制对齐，默认右对齐，金额列惯例。
 */
import { computed } from 'vue'
import { formatAmount } from '@/utils/format'
import { fromFen } from '@/utils/money'

const props = withDefaults(
  defineProps<{
    /** 元金额，string/number 均可 */
    value?: string | number | null
    /** 分整数金额，优先于 value */
    fen?: number | null
    /** 币种前缀，如 ¥ / $ */
    symbol?: string
    /** 对齐方式，默认右对齐，金额列惯例 */
    align?: 'left' | 'right' | 'center'
  }>(),
  { value: null, fen: null, symbol: '', align: 'right' },
)
const { value, fen, symbol, align } = props

/** 展示文案，fen 优先，千分位 2 位小数，空值统一显示 — */
const display = computed(() => {
  if (fen !== null && fen !== undefined) return formatAmount(fromFen(fen))
  return formatAmount(value)
})

/** 负数判定，分整数模式直接判 fen，元模式转数字判负 */
const isNegative = computed(() => {
  if (fen !== null && fen !== undefined) return fen < 0
  const n = Number(value)
  return Number.isFinite(n) && n < 0
})
</script>

<template>
  <span class="fms-money num" :class="{ negative: isNegative }" :style="{ textAlign: align }">
    <template v-if="symbol">{{ symbol }}</template>{{ display }}
  </span>
</template>

<style scoped>
/* 数字等宽 + 右对齐由全局 .num 提供（styles/index.css），此处补内联块与最小宽度 */
.fms-money {
  display: inline-block;
  min-width: 1.5em;
}
</style>
