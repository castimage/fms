<script setup lang="ts">
/**
 * 独立布局，BlankLayout 语义，全屏居中 + 渐变背景 + 版权，登录页与 404 共用，手机/桌面同构。
 * 作为嵌套路由父组件必须渲染 router-view，slot 占位会导致子路由永不挂载。
 *
 * 挂载时 loadLogin 拉取登录页背景，启用则渲染 BackgroundLayer，按断点档解析单档配置，
 * 渐变底保留为 fallback，基色改透明，由 body 底色承接。
 */
import { computed, onMounted } from 'vue'
import BackgroundLayer from '@/components/common/BackgroundLayer.vue'
import { useBackgroundStore } from '@/stores/background'
import { useBreakpoint } from '@/utils/breakpoint'
import type { BreakpointKey } from '@/types/background'

const background = useBackgroundStore()
const { isMobile, isTablet } = useBreakpoint()

onMounted(() => {
  void background.loadLogin()
})

/** 当前断点档名，与 useBreakpoint 对齐，isMobile 对应 mobile / isTablet 对应 tablet / 否则 desktop */
const bgBreakpoint = computed<BreakpointKey>(() =>
  isMobile.value ? 'mobile' : isTablet.value ? 'tablet' : 'desktop',
)

/** 合并后的登录页单档配置 */
const loginResolvedCfg = computed(() => background.resolveFor(background.loginCfg, bgBreakpoint.value))
</script>

<template>
  <div class="login-layout">
    <!-- 登录页背景层，fixed 底层，渐变底之上留作半透明质感，fallback 语义见样式注释 -->
    <BackgroundLayer v-if="background.loginCfg.enabled && background.loginCfg.imageUrl" :cfg="loginResolvedCfg" />
    <div class="login-layout__bg" aria-hidden="true" />
    <main class="login-layout__body">
      <router-view />
    </main>
    <footer class="login-layout__footer">FMS 财务管理系统 © castimage all rights reserved.</footer>
  </div>
</template>

<style scoped>
.login-layout {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px 16px;
  overflow: hidden;
}

/* 品牌色渐变 + 暗角，深色模式同步，基色由 body 承接，自身半透明，
   启用背景图时透过渐变可见，未启用时渐变即 fallback */
.login-layout__bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  background:
    radial-gradient(1200px 600px at 20% -10%, color-mix(in srgb, var(--fms-primary) 22%, transparent), transparent 60%),
    radial-gradient(900px 500px at 90% 110%, color-mix(in srgb, var(--fms-primary) 14%, transparent), transparent 55%);
}

.login-layout__body {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1080px;
}

.login-layout__footer {
  position: absolute;
  bottom: 16px;
  z-index: 1;
  color: var(--fms-text-muted);
  font-size: 12px;
}
</style>
