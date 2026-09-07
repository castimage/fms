<script setup lang="ts">
/**
 * 背景层组件，fixed 底层渲染背景图，支持拉伸/模糊/暗化/透明度。
 * cfg 变化时双图 crossfade，旧层淡出 + 新层淡入，550ms，
 * 同图仅刷新参数无过渡。pointer-events 穿透不拦截交互。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type CSSProperties } from 'vue'
import axios from 'axios'
import type { ResolvedBackground } from '@/types/background'

const props = defineProps<{ cfg: ResolvedBackground }>()

/** 当前显示层与正在淡出的旧层，imageUrl 为原始配置 url */
const current = ref<ResolvedBackground | null>(null)
const outgoing = ref<ResolvedBackground | null>(null)
/** 认证解析后的可渲染 src，/api/ 图走 blob 带 token，外链原样 */
const currentSrc = ref<string | null>(null)
const outgoingSrc = ref<string | null>(null)
/** 淡入态，挂载后下一帧移除类触发 opacity 过渡 */
const entering = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null
/** 竞态序号 + objectURL 持有，卸载 revoke 防泄漏 */
let resolveSeq = 0
const currentHolder = { obj: null as string | null }
const outgoingHolder = { obj: null as string | null }

/**
 * 解析层图片 src，/api/ 同源图经 axios blob 带 token 换取 objectURL
 * 原生 img 加载不带 Authorization 会 401，外链原样返回
 */
async function resolveLayerSrc(
  layerUrl: string | null,
  setter: (v: string | null) => void,
  holder: { obj: string | null },
) {
  const seq = ++resolveSeq
  if (holder.obj) {
    URL.revokeObjectURL(holder.obj)
    holder.obj = null
  }
  if (!layerUrl) {
    setter(null)
    return
  }
  if (!layerUrl.startsWith('/api/')) {
    setter(layerUrl)
    return
  }
  try {
    // http.ts 的 token 注入在自定义 request 中，axios 直连须显式携带
    const token = localStorage.getItem('fms_access_token')
    const resp = await axios.get(layerUrl, {
      responseType: 'blob',
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    })
    if (seq !== resolveSeq) {
      return // 竞态，期间已发起新解析，本次作废
    }
    holder.obj = URL.createObjectURL(resp.data)
    setter(holder.obj)
  } catch {
    if (seq === resolveSeq) {
      setter(null) // 加载失败不显示
    }
  }
}

function clearTimer() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

/** 应用新配置，同图仅刷新参数，换图双图层 crossfade，停用则旧层淡出后移除 */
function applyCfg(cfg: ResolvedBackground) {
  const active: ResolvedBackground | null = cfg.enabled && cfg.imageUrl ? cfg : null
  const prev = current.value
  clearTimer()
  if (!prev) {
    // 首帧直接显示，轻微淡入防闪
    current.value = active
    if (active) {
      entering.value = true
      void nextTick(() => (entering.value = false))
    }
    void resolveLayerSrc(active?.imageUrl ?? null, (v) => (currentSrc.value = v), currentHolder)
    return
  }
  if (active && prev.imageUrl === active.imageUrl) {
    // 同图，仅刷新参数，无过渡
    current.value = active
    outgoing.value = null
    outgoingSrc.value = null
    return
  }
  // 换图/停用，旧层淡出，src 移交 outgoing 保持显示，新层异步解析
  outgoing.value = prev
  outgoingSrc.value = currentSrc.value
  current.value = active
  currentSrc.value = null
  void resolveLayerSrc(active?.imageUrl ?? null, (v) => (currentSrc.value = v), currentHolder)
  if (active) {
    entering.value = true
    void nextTick(() => (entering.value = false))
  }
  timer = setTimeout(() => {
    outgoing.value = null
    outgoingSrc.value = null
    if (outgoingHolder.obj) {
      URL.revokeObjectURL(outgoingHolder.obj)
      outgoingHolder.obj = null
    }
    timer = null
  }, 550)
}

watch(() => props.cfg, (cfg) => applyCfg(cfg), { deep: true })
onMounted(() => applyCfg(props.cfg))
onBeforeUnmount(() => {
  resolveSeq++
  if (currentHolder.obj) URL.revokeObjectURL(currentHolder.obj)
  if (outgoingHolder.obj) URL.revokeObjectURL(outgoingHolder.obj)
})

/** 暗化层透明度，0 不渲染 */
const darken = computed(() => (props.cfg.darken > 0 ? props.cfg.darken : 0))

/** 单层样式，object-fit 按 fit，fill 拉伸铺满，为合法 object-fit 值，blur 时放大 1.1 防边缘露白 */
function imgStyle(cfg: ResolvedBackground): CSSProperties {
  return {
    objectFit: cfg.fit,
    filter: cfg.blur > 0 ? `blur(${cfg.blur}px)` : undefined,
    transform: cfg.blur > 0 ? 'scale(1.1)' : undefined,
  }
}
</script>

<template>
  <div v-if="current || outgoing" class="bg-layer" aria-hidden="true">
    <!-- 旧层，过渡中淡出，wrapper 透明度与 img 的 crossfade 类相乘，过渡平滑 -->
    <div v-if="outgoing" class="bg-layer__img-wrap" :style="{ opacity: outgoing.opacity }">
      <img :src="outgoingSrc ?? ''" class="bg-layer__img bg-layer__img--exit" :style="imgStyle(outgoing)" alt="" />
    </div>
    <!-- 新层，挂载后淡入，src 为认证解析后的可渲染 url -->
    <div v-if="current" class="bg-layer__img-wrap" :style="{ opacity: current.opacity }">
      <img
        :src="currentSrc ?? ''"
        class="bg-layer__img"
        :class="{ 'bg-layer__img--enter': entering }"
        :style="imgStyle(current)"
        alt=""
      />
    </div>
    <!-- 暗化覆盖层，黑色透明叠加 -->
    <div v-if="darken > 0" class="bg-layer__darken" :style="{ background: `rgba(0, 0, 0, ${darken})` }" />
  </div>
</template>

<style scoped>
.bg-layer {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  /* 背景层不拦截交互 */
  pointer-events: none;
}

/* wrapper 透明度与 img 换图 crossfade 相乘，互不干扰 */
.bg-layer__img-wrap {
  position: absolute;
  inset: 0;
  transition: opacity 0.5s ease;
}

.bg-layer__img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 1;
  transition: opacity 0.5s ease;
}

.bg-layer__img--enter,
.bg-layer__img--exit {
  opacity: 0;
}

.bg-layer__darken {
  position: absolute;
  inset: 0;
}
</style>
