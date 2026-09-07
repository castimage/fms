<script setup lang="ts">
/**
 * 独立分页条封装，DataTable 内置分页之外的场景，v-model:page / v-model:size 双向绑定，
 * 每页条数变化自动回第 1 页防越界，手机端隐藏条数选择器与快速跳转并居中。
 */
import { computed, watch } from 'vue'
import { NPagination } from 'naive-ui'
import { useBreakpoint } from '@/utils/breakpoint'
import { useConfigStore } from '@/stores/config'

const configStore = useConfigStore()
void configStore.load() // 分页选项收敛，经 /api/config 下发（fms.ui.page-sizes），失败保持默认 [10,20,50]

const props = withDefaults(
  defineProps<{
    /** 数据总条数，≤0 时不渲染分页条 */
    total: number
    /** 每页条数选项，缺省经 /api/config 下发，fms.ui.page-sizes，默认 10/20/50，显式传值覆盖 */
    pageSizes?: number[]
    /** 是否显示每页条数选择器，手机端强制隐藏 */
    showSizePicker?: boolean
    /** 是否显示快速跳转，手机端强制隐藏 */
    showQuickJumper?: boolean
    disabled?: boolean
    simple?: boolean
    /** 每页条数变化是否重置回第 1 页，缺省 true */
    resetPageOnSizeChange?: boolean
  }>(),
  {
    total: 0,
    pageSizes: undefined,
    showSizePicker: true,
    showQuickJumper: true,
    disabled: false,
    simple: false,
    resetPageOnSizeChange: true,
  },
)
const { total, showSizePicker, showQuickJumper, disabled, simple, resetPageOnSizeChange } = props
/** 每页条数选项，显式 props 优先，否则后端配置，单一来源 */
const pageSizes = computed(() => props.pageSizes ?? configStore.cfg.pageSizes)

const page = defineModel<number>('page', { default: 1 })
// 初始默认 20，与后端 fms.ui.page-sizes 默认值一致，选项经 /api 下发收敛
const size = defineModel<number>('size', { default: 20 })

const { isMobile } = useBreakpoint()

/** 每页条数变化则回第 1 页，防当前页越界 */
watch(size, () => {
  if (resetPageOnSizeChange) page.value = 1
})
</script>

<template>
  <div v-if="total > 0" class="fms-pager">
    <n-pagination
      v-model:page="page"
      v-model:page-size="size"
      :item-count="props.total"
      :page-sizes="pageSizes"
      :show-size-picker="showSizePicker && !isMobile"
      :show-quick-jumper="showQuickJumper && !isMobile"
      :disabled="disabled"
      :simple="simple"
    />
  </div>
</template>

<style scoped>
.fms-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  overflow-x: auto;
  padding-bottom: 2px;
}
/* 手机端居中，断点与 utils/breakpoint.ts md=768 互指 */
@media (max-width: 767px) {
  .fms-pager {
    justify-content: center;
  }
}
</style>
