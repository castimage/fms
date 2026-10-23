/**
 * 背景 api，用户背景，/api/user/background，任意已认证角色 + 登录页背景，/api/public/login-background，
 * 匿名端点 + 背景图上传（/api/files/upload multipart，businessType=USER_BACKGROUND/LOGIN_BACKGROUND）。
 */
import { request } from './http'
import { adminApi } from './admin'
import { DEFAULT_BG, type BackgroundCfg } from '@/types/background'

/** 登录页背景系统参数 code，与 init/seed_system.sql / PublicController 键一致 */
export const LOGIN_BG_PARAM_CODE = 'ui.login.background'

/** 背景图业务类型，与后端 FileServiceImpl.BUSINESS_TYPES 对齐 */
export type BackgroundBusinessType = 'USER_BACKGROUND' | 'LOGIN_BACKGROUND'

/** 上传响应，DocumentView 投影，取 id 拼下载 url */
export interface BackgroundUploadView {
  id: string
}

/** 后端响应双形态，data 直接为配置对象，或包一层 {background:{...}}，按实际响应兜底解包 */
type BackgroundPayload = BackgroundCfg | { background: BackgroundCfg }

function unwrapBackground(payload: BackgroundPayload | null): BackgroundCfg {
  if (payload && typeof payload === 'object' && 'background' in payload && payload.background) {
    return payload.background
  }
  return (payload as BackgroundCfg | null) ?? { ...DEFAULT_BG }
}

/** 取当前用户背景配置，失败由 store 回落默认，不阻断 */
export async function getUserBackground(): Promise<BackgroundCfg> {
  const res = await request<BackgroundPayload | null>({ url: '/user/background', method: 'get' })
  return unwrapBackground(res)
}

/** 保存当前用户背景，约定 body {background:{...}} 包装，与后端双形态兼容 */
export async function putUserBackground(cfg: BackgroundCfg): Promise<void> {
  await request<void>({ url: '/user/background', method: 'put', data: { background: cfg } })
}

/** 登录页背景配置，匿名端点，不携带 Authorization，登录页未登录态可读 */
export async function getLoginBackground(): Promise<BackgroundCfg> {
  const res = await request<BackgroundPayload | null>(
    { url: '/public/login-background', method: 'get' },
    { anonymous: true },
  )
  return unwrapBackground(res)
}

/**
 * 管理员保存登录页背景，写 ui.login.background 系统参数，PUT /api/admin/params/{code}，
 * value 为 jsonb 文本，种子/读口均为 {"v":{...}} 包装结构。
 */
export async function saveLoginBackground(cfg: BackgroundCfg): Promise<void> {
  await adminApi.setParam(LOGIN_BG_PARAM_CODE, JSON.stringify({ v: cfg }))
}

/**
 * 背景图上传（multipart，file + businessType + businessId），返回 data.id，调用方按所属形态拼 url。
 * 后端强制 businessId=当前用户 id，客户端不可指定他人。
 */
export async function uploadBackgroundFile(
  file: File,
  businessType: BackgroundBusinessType,
  businessId: string,
): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  form.append('businessType', businessType)
  form.append('businessId', businessId)
  const res = await request<BackgroundUploadView>({ url: '/files/upload', method: 'post', data: form })
  return res.id
}
