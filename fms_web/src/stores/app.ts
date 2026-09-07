/**
 * 应用上下文 store，当前公司 + 当前会计期间，GL 页统一消费，localStorage('fms.app') 持久化。
 */
import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import dayjs from 'dayjs'

const STORAGE_KEY = 'fms.app'

interface PersistedAppState {
  companyId: string | null
  companyName: string
  period: string
}

function loadInit(): PersistedAppState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return { companyId: null, companyName: '', period: dayjs().format('YYYY-MM'), ...JSON.parse(raw) }
  } catch {
    // 数据损坏则忽略
  }
  return { companyId: null, companyName: '', period: dayjs().format('YYYY-MM') }
}

export const useAppStore = defineStore('app', () => {
  const init = loadInit()
  const companyId = ref<string | null>(init.companyId)
  const companyName = ref<string>(init.companyName)
  /** 会计期间 YYYY-MM，缺省当月 */
  const period = ref<string>(init.period)

  function setCompany(id: string | null, name: string) {
    companyId.value = id
    companyName.value = name
  }
  function setPeriod(p: string) {
    period.value = p
  }

  watch(
    [companyId, companyName, period],
    () => {
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({ companyId: companyId.value, companyName: companyName.value, period: period.value }),
      )
    },
    { deep: true },
  )

  return { companyId, companyName, period, setCompany, setPeriod }
})
