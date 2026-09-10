<script setup lang="ts">
/**
 * DataTable 响应式表格封装，hideBelow 按断点裁剪列，手机只留核心列，展开详情兜底，
 * v-model:page / v-model:size 双绑定服务端分页，手机端且有 rowDetail 插槽时追加 expand 列。
 */
import { computed, useSlots } from 'vue'
import type { VNode } from 'vue'
import { NDataTable, NPagination } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { useBreakpoint } from '@/utils/breakpoint'
import { useConfigStore } from '@/stores/config'

export interface FmsColumn<T> {
  key: string
  title: string
  width?: number
  align?: 'left' | 'right' | 'center'
  /** 小于该断点隐藏列，sm=仅手机隐藏，md=平板与手机均隐藏，桌面全列 */
  hideBelow?: 'sm' | 'md' | 'lg'
  render?: (row: T) => VNode | string | number
}

const props = withDefaults(
  defineProps<{
    columns: FmsColumn<any>[]
    data: any[]
    loading?: boolean
    total?: number
    rowKey?: (r: any) => string | number
    scrollX?: number
    bordered?: boolean
    onRowClick?: (row: any) => void
    /** 本地分页，data 为全量，n-data-table 内置分页自动切片，页面无需手动 slice，同服务端页外观 */
    localPagination?: boolean
  }>(),
  { loading: false, total: 0, scrollX: 900, bordered: false, localPagination: false },
)
const { columns, loading, scrollX, bordered, onRowClick } = props // total 经 props.total 响应式读取，见 itemCount

const configStore = useConfigStore()
void configStore.load() // 分页选项收敛，经 /api/config 下发（fms.ui.page-sizes），失败保持默认 [10,20,50]
/** 每页条数选项，后端配置单一来源 */
const pageSizes = computed(() => configStore.cfg.pageSizes)

const page = defineModel<number>('page', { default: 1 })
// 初始默认 20，与后端 fms.ui.page-sizes 默认值一致，选项经 /api 下发收敛
const size = defineModel<number>('size', { default: 20 })

const slots = useSlots()
const { isMobile, isDesktop } = useBreakpoint()

/**
 * 断点过滤可见列，sm 仅手机隐藏，md/lg 平板与手机均隐藏，桌面全列，
 * 依赖 isMobile/isDesktop 自动重算。
 */
const visibleColumns = computed(() =>
  columns.filter((c) => {
    if (!c.hideBelow) return true
    if (c.hideBelow === 'sm') return !isMobile.value
    return isDesktop.value // 'md' / 'lg'
  }),
)

const rowKeyFn = computed(() => props.rowKey ?? ((r: any) => r.id))

/** n-data-table 列，FmsColumn 映射 + 手机端 expand 列 */
const tableColumns = computed<DataTableColumns>(() => {
  const cols: any[] = visibleColumns.value.map((c) => ({
    key: c.key,
    title: c.title,
    width: c.width,
    align: c.align,
    ellipsis: { tooltip: true },
    render: c.render
      ? (row: any) => c.render!(row)
      : (row: any) => String(row[c.key] ?? ''),
  }))
  if (isMobile.value && slots.rowDetail) {
    cols.unshift({ type: 'expand', renderExpand: (row: any) => slots.rowDetail?.(row) ?? null })
  }
  return cols
})

// props 解构在 3.5 非响应式，仅 defineProps 直接解构生效，total 必须经 props.total 读取，
// 否则恒为初始值 0，itemCount 退化为当页 data.length，分页恒 1 页
const itemCount = computed(() => props.total || props.data.length)

/** 本地分页配置，n-data-table 内置分页，自动对全量 data 切片，v-model:page/size 与外部绑定一致 */
const localPaginationProp = computed(() =>
  props.localPagination
    ? {
        page: page.value,
        pageSize: size.value,
        pageSizes: pageSizes.value,
        showSizePicker: true,
        onUpdatePage: (v: number) => (page.value = v),
        onUpdatePageSize: (v: number) => (size.value = v),
      }
    : undefined,
)

/** 行点击，本地 const 别名，保证回调内窄化生效 */
const handleRowClick = onRowClick
const rowPropsFn = (row: any) => ({
  style: handleRowClick ? { cursor: 'pointer' } : undefined,
  onClick: handleRowClick ? () => handleRowClick(row) : undefined,
})
</script>

<template>
  <div class="fms-datatable">
    <n-data-table
      :columns="tableColumns"
      :data="data"
      :loading="loading"
      :row-key="rowKeyFn"
      :scroll-x="scrollX"
      :bordered="bordered"
      :row-props="rowPropsFn"
      :pagination="localPaginationProp"
      size="small"
    >
      <template #empty>
        <slot name="empty" />
      </template>
    </n-data-table>
    <div v-if="!props.localPagination" class="fms-datatable-footer">
      <n-pagination
        :page="page"
        :page-size="size"
        :item-count="itemCount"
        :page-sizes="pageSizes"
        show-size-picker
        @update:page="(v: number) => (page = v)"
        @update:page-size="(v: number) => (size = v)"
      />
    </div>
  </div>
</template>

<style scoped>
.fms-datatable-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  overflow-x: auto;
  padding-bottom: 2px;
}
/* 手机端分页居中，断点与 utils/breakpoint.ts md=768 互指 */
@media (max-width: 767px) {
  .fms-datatable-footer {
    justify-content: center;
  }
}
</style>
