<script setup lang="ts">
/**
 * QR 码渲染，矩阵生成委托 qrcode-generator 成熟库（typeNumber 0 自动选版本，纠错 M），
 * 深色模式随主题换色，text/size/主题变化时重建。
 */
import { onMounted, watch } from 'vue'
import { useTemplateRef } from 'vue'
import qrcode from 'qrcode-generator'
import { useThemeStore } from '@/stores/theme'

const { text, size = 200 } = defineProps<{
  /** 编码内容（otpauth://... uri） */
  text: string
  /** 显示边长（px） */
  size?: number
}>()

const theme = useThemeStore()
const canvasRef = useTemplateRef<HTMLCanvasElement>('canvasRef')

/** 绘制，静区 4 模块，模块按 devicePixelRatio 缩放保证高清 */
function draw() {
  const canvas = canvasRef.value
  if (!canvas) return
  const qr = qrcode(0, 'M')
  qr.addData(text)
  qr.make()
  const n = qr.getModuleCount()
  const quiet = 4
  const dpr = window.devicePixelRatio || 1
  const total = n + quiet * 2
  const px = Math.floor((size * dpr) / total)
  canvas.width = px * total
  canvas.height = px * total
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.fillStyle = theme.darkMode ? '#1d1f24' : '#ffffff' // 浅色底，白 / 深色卡底
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = theme.darkMode ? '#e5e6eb' : '#1f2329'
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (qr.isDark(y, x)) ctx.fillRect((x + quiet) * px, (y + quiet) * px, px, px)
    }
  }
}

onMounted(draw)
watch(() => [text, size, theme.darkMode], draw, { deep: false })
</script>

<template>
  <canvas
    ref="canvasRef"
    class="qr-canvas"
    :style="{ width: `${size}px`, height: `${size}px` }"
  />
</template>

<style scoped>
.qr-canvas {
  display: block;
  margin: 0 auto;
  border-radius: var(--fms-radius);
  background: #fff;
}
</style>
