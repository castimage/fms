<script setup lang="ts">
/**
 * FormGrid 响应式栅格表单容器，手机 1 列、平板 2 列、桌面 3 列，子项自动占一格。
 * 断点媒体查询与 utils/breakpoint.ts 常量互指。
 */
import { computed } from 'vue'

const props = withDefaults(defineProps<{ cols?: { xs?: number; md?: number; lg?: number } }>(), {
  cols: () => ({ xs: 1, md: 2, lg: 3 }),
})
const { cols } = props

const gridStyle = computed(() => ({
  '--fg-xs': cols.xs ?? 1,
  '--fg-md': cols.md ?? 2,
  '--fg-lg': cols.lg ?? 3,
}))
</script>

<template>
  <div class="fms-form-grid" :style="gridStyle">
    <slot />
  </div>
</template>

<style scoped>
.fms-form-grid {
  display: grid;
  grid-template-columns: repeat(var(--fg-xs), minmax(0, 1fr));
  gap: 12px 16px;
}
/* 断点与 utils/breakpoint.ts 的 md=768 / lg=992 互指 */
@media (min-width: 768px) {
  .fms-form-grid {
    grid-template-columns: repeat(var(--fg-md), minmax(0, 1fr));
  }
}
@media (min-width: 992px) {
  .fms-form-grid {
    grid-template-columns: repeat(var(--fg-lg), minmax(0, 1fr));
  }
}
</style>
