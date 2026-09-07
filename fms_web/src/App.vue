<script setup lang="ts">
/**
 * 应用壳，n-config-provider，深色/主题色派生 + n-message-provider + router-view，
 * naive 组件按需自动导入（unplugin-vue-components + NaiveUiResolver）。
 */
import { dateZhCN, darkTheme, zhCN, type GlobalThemeOverrides } from 'naive-ui'
import { computed } from 'vue'
import { useThemeStore } from '@/stores/theme'

const theme = useThemeStore()

/** 主题色衍生 naive 四色，与 theme store 的 --fms-primary 一致 */
const themeOverrides = computed<GlobalThemeOverrides>(() => ({
  common: {
    primaryColor: theme.primaryColor,
    primaryColorHover: `${theme.primaryColor}cc`,
    primaryColorPressed: `${theme.primaryColor}aa`,
    primaryColorSuppl: `${theme.primaryColor}cc`,
    borderRadius: '8px',
  },
}))
</script>

<template>
  <n-config-provider :theme="theme.darkMode ? darkTheme : null" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <n-message-provider>
      <n-dialog-provider>
        <router-view />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
