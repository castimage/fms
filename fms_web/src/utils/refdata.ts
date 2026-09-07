/**
 * 参考数据下拉拉取上限，um.params web.refdata.page.size 可配，缺省 200，
 * 经 GET /api/config 下发，UiConfigView.refdataPageSize，Web 系统参数页可改，
 * 供 10+ 处下拉，公司/供应商/客户/部门/银行账户/未核销发票/审计日志等统一消费，
 * 消除各处 100/200 硬编码，拉取失败回落本地缺省，不阻断页面。
 */
import { useConfigStore } from '@/stores/config'

/** 参考数据下拉拉取上限，配置 store 单例加载，失败回落本地缺省 200 */
export async function refdataPageSize(): Promise<number> {
  const configStore = useConfigStore()
  await configStore.load() // 共享 promise 防重复请求，失败保持默认值
  const n = configStore.cfg.refdataPageSize
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 200
}
