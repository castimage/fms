/**
 * 主题 store，深色模式 + 主题色，localStorage('fms_theme') 持久化，
 * 驱动 html.dark class，ECharts/自定义 css 依赖，与 --fms-primary css 变量。
 */
import { ref, watch } from 'vue'
import { defineStore } from 'pinia'

const STORAGE_KEY = 'fms_theme'

interface PersistedTheme {
  dark: boolean
  primary: string
}

/** 预设主题色，财务蓝默认 / 墨绿 / 绛紫 / 橙 / 朱红 */
export const THEME_PRESETS = [
  { label: '财务蓝', value: '#1677ff' },
  { label: '墨绿', value: '#18a058' },
  { label: '绛紫', value: '#722ed1' },
  { label: '橙', value: '#fa8c16' },
  { label: '朱红', value: '#f5222d' },
] as const

function loadInit(): PersistedTheme {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return { dark: false, primary: '#1677ff', ...JSON.parse(raw) }
  } catch {
    // 数据损坏则忽略
  }
  return { dark: false, primary: '#1677ff' }
}

export const useThemeStore = defineStore('theme', () => {
  const init = loadInit()
  const darkMode = ref<boolean>(init.dark)
  const primaryColor = ref<string>(init.primary)

  /** 同步 html class 与 css 变量，配合 index.html 内联 FOUC 脚本 */
  function apply() {
    document.documentElement.classList.toggle('dark', darkMode.value)
    document.documentElement.style.setProperty('--fms-primary', primaryColor.value)
  }

  function toggleDark() {
    darkMode.value = !darkMode.value
  }
  function setPrimary(color: string) {
    primaryColor.value = color
  }

  watch(
    [darkMode, primaryColor],
    () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ dark: darkMode.value, primary: primaryColor.value }))
      apply()
    },
    { immediate: true },
  )

  return { darkMode, primaryColor, toggleDark, setPrimary }
})
