/**
 * 系统配置 api（GET /api/config），前端硬编码收敛的单一事实源下发
 * 密码长度/扩展名白名单/分页选项/报表白名单，与后端 yaml fms.* 联动。
 */
import { request } from '@/api/http'
import type { UiConfig } from '@/types/config'

/** 获取 ui 配置，任意已认证角色可读，失败时调用方保持本地默认值 */
export function getConfig(): Promise<UiConfig> {
  return request<UiConfig>({ url: '/config', method: 'get' })
}
