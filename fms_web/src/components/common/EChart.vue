<script setup lang="ts">
/**
 * EChart 封装，按需引入，模板 ref 绑定容器初始化，option 深度 watch 后 setOption(notMerge) 重绘，
 * 容器尺寸自适应，深色模式切换时销毁重建，卸载时释放实例。
 *
 * 体积控制，echarts/core 按需注册，仅本项目用到的图表（line/pie/bar）与组件
 * （Grid/Tooltip/Legend）+ CanvasRenderer，产物由全量 1.1MB 降至 ~250KB（gzip ~90KB），
 * 新增图表类型时须在下方 use 注册并扩展 EChartOption 组合。
 */
import { onBeforeUnmount, onMounted, watch } from 'vue'
import { useTemplateRef } from 'vue'
import { use, init, type ComposeOption } from 'echarts/core'
import { LineChart, PieChart, BarChart, type LineSeriesOption, type PieSeriesOption, type BarSeriesOption } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  type GridComponentOption,
  type TooltipComponentOption,
  type LegendComponentOption,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useResizeObserver } from '@vueuse/core'
import { useThemeStore } from '@/stores/theme'

use([LineChart, PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

/** 按需组合类型，调用方 computed 标注用，替代全量 EChartsOption */
export type EChartOption = ComposeOption<
  | LineSeriesOption
  | PieSeriesOption
  | BarSeriesOption
  | GridComponentOption
  | TooltipComponentOption
  | LegendComponentOption
>

const props = withDefaults(
  defineProps<{
    option: EChartOption
    height?: number | string
    autoresize?: boolean
  }>(),
  { height: 320, autoresize: true },
)
const { option, height, autoresize } = props

const chartRef = useTemplateRef<HTMLElement>('chartRef')
const themeStore = useThemeStore()

let chart: ReturnType<typeof init> | null = null

function initChart() {
  if (!chartRef.value) return
  chart = init(chartRef.value)
  chart.setOption(option, { notMerge: true })
}
function disposeChart() {
  chart?.dispose()
  chart = null
}

onMounted(() => {
  initChart()
  if (autoresize && chartRef.value) {
    // 容器尺寸变化自动 resize，无需监听 window
    useResizeObserver(chartRef.value, () => chart?.resize())
  }
})

watch(
  () => option,
  (o) => chart?.setOption(o, { notMerge: true }),
  { deep: true },
)

// 深色模式切换则重建，文字/背景色跟随主题
watch(
  () => themeStore.darkMode,
  () => {
    disposeChart()
    initChart()
  },
)

onBeforeUnmount(disposeChart)
</script>

<template>
  <div
    ref="chartRef"
    class="fms-echart"
    :style="{ height: typeof height === 'number' ? `${height}px` : height }"
  />
</template>
