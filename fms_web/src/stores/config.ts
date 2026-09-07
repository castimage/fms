/**
 * 前端 ui 配置 store，GET /api/config 一次性拉取，共享 promise 防重复请求，
 * 拉取失败保持默认值不阻断页面渲染，权威裁决恒在后端。
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as configApi from '@/api/config'
import type { UiConfig } from '@/types/config'

/** 本地默认值，与后端 yaml 缺省逐项一致，密码 8-64 / 附件含 tsv / 分页 10-20-50 / 报表六类 / 下拉 200 */
const DEFAULTS: UiConfig = {
  passwordMinLength: 8,
  passwordMaxLength: 64,
  allowedExtensions: [
    'pdf', 'jpg', 'jpeg', 'png', 'gif', 'doc', 'docx', 'xls', 'xlsx', 'csv', 'txt', 'tsv',
  ],
  pageSizes: [10, 20, 50],
  enabledReportTypes: [
    'trial-balance', 'income-statement', 'balance-sheet', 'cash-flow', 'ar-aging', 'dept-expense',
  ],
  refdataPageSize: 200,
}

export const useConfigStore = defineStore('config', () => {
  const cfg = ref<UiConfig>({ ...DEFAULTS })
  let loaded = false
  let loading: Promise<void> | null = null

  /** 拉取配置，并发共享同一请求，成功后固化，失败保持默认值下次再试 */
  async function load(): Promise<void> {
    if (loaded) return
    if (loading) return loading
    loading = configApi
      .getConfig()
      .then((c) => {
        cfg.value = c
        loaded = true
      })
      .catch(() => {
        // 拉取失败保持默认值，页面渲染不阻塞，http.ts 已统一提示网络异常
      })
      .finally(() => {
        loading = null
      })
    return loading
  }

  return { cfg, load }
})
