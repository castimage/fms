/**
 * http 出口，axios 实例 + 统一解包。
 *
 * 请求，localStorage 取 token 注入 Authorization，anonymous 端点除外，
 * 响应，code=0 返回业务数据，data:null 照常返回，40100/40101 静默 refresh 重放一次，
 * 失败清态跳登录，42900 提示限流，其他业务码抛 ApiError，后端中文消息直接展示。
 *
 * 非 2xx，后端错误恒带 ApiResponse 壳，40400 映射 http 404、40300 映射 403 等，catch 中同一分发，
 * 业务消息不丢失，仅无响应体，网络/超时/网关 502 等才提示网络异常。
 * 匿名端点（login/totp/refresh/recovery）40100/40101，无会话可清、无登录态可过期，
 * 不刷新不跳登录，直接抛后端消息，登录失败「用户名或密码错误」等真实原因不被「登录已过期」掩盖。
 */
import axios from 'axios'
import type { AxiosError, AxiosRequestConfig } from 'axios'
import { createDiscreteApi } from 'naive-ui'
import { ApiError, ErrorCode } from '@/types/api'
import type { ApiResponse } from '@/types/api'

const { message } = createDiscreteApi(['message'])

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 10_000,
})

export interface RequestOptions {
  /** 匿名端点，login/totp/refresh 等不携带 Authorization */
  anonymous?: boolean
}

/** 清空本地会话，401 兜底，与 auth store 的 key 约定一致 */
function clearLocalSession() {
  localStorage.removeItem('fms_access_token')
  localStorage.removeItem('fms_refresh_token')
  localStorage.removeItem('fms_user')
}

/** 跳登录，保留原路径 */
function toLogin() {
  if (window.location.pathname === '/login') return
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.href = `/login?redirect=${redirect}`
}

type RetriedConfig = AxiosRequestConfig & { _retried?: boolean }

/**
 * 按 body.code 统一分发业务码，2xx 与非 2xx 共用一条路径防止分支漂移，
 * 40100/40101 匿名端点直接抛后端消息，其余静默刷新重放一次。
 */
async function dispatchBody<T>(
  body: ApiResponse<T>,
  config: AxiosRequestConfig,
  options?: RequestOptions,
): Promise<T> {
  if (body.code === ErrorCode.OK) return body.data as T

  console.warn('[fms] traceId:', body.traceId)
  if (body.code === ErrorCode.UNAUTHORIZED || body.code === ErrorCode.TOKEN_EXPIRED) {
    // 匿名端点（login/totp/refresh/recovery），无会话可清、无登录态可过期，不刷新不跳登录，
    // 直接抛后端消息，登录失败「用户名或密码错误」等真实原因不被「登录已过期」掩盖
    if (options?.anonymous) {
      // refresh 端点失败由 40101 重放外层统一提示「登录已过期」，此处只抛错不重复 toast
      if (!String(config.url).includes('/auth/refresh')) {
        message.error(body.message || '认证失败')
      }
      throw new ApiError(body.code, body.message || '认证失败', body.traceId)
    }
    // 40101 且有 refreshToken、本请求非 refresh 且未重试过则静默刷新后重放一次，与后端行为一致
    const refreshToken = localStorage.getItem('fms_refresh_token')
    const cfg = config as RetriedConfig
    if (
      body.code === ErrorCode.TOKEN_EXPIRED &&
      refreshToken &&
      !cfg._retried &&
      !String(config.url).includes('/auth/refresh')
    ) {
      cfg._retried = true
      try {
        const { refresh } = await import('@/api/auth')
        const res = await refresh(refreshToken)
        localStorage.setItem('fms_access_token', res.accessToken ?? '')
        localStorage.setItem('fms_refresh_token', res.refreshToken ?? '')
        return await request<T>(config, options) // 递归重放，token 已换新
      } catch {
        // 刷新失败则走下方清态跳登录
      }
    }
    clearLocalSession()
    message.error('登录已过期，请重新登录')
    toLogin()
  } else if (body.code === ErrorCode.RATE_LIMITED) {
    message.error('请求过于频繁，请稍后再试')
  } else {
    // 40001/40300/40400/40900/50000，后端中文业务消息直接展示
    message.error(body.message || '请求失败')
  }
  throw new ApiError(body.code, body.message || '请求失败', body.traceId)
}

/** 从 AxiosError 兜底解析后端错误壳，非 AxiosError 或无 code 字段，html/网关 502 等返回 null */
function parseErrorBody<T>(e: unknown): ApiResponse<T> | null {
  if (!axios.isAxiosError(e)) return null
  const data = (e as AxiosError<ApiResponse<T>>).response?.data
  if (!data || typeof data !== 'object' || typeof data.code !== 'number') return null
  return data
}

export async function request<T>(config: AxiosRequestConfig, options?: RequestOptions): Promise<T> {
  const token = localStorage.getItem('fms_access_token')
  const headers: Record<string, string> = {
    ...(config.headers as Record<string, string> | undefined),
  }
  if (token && !options?.anonymous) headers.Authorization = `Bearer ${token}`
  // 无 body 的 POST/PUT/PATCH，网关对带 body 方法强制 Content-Type 白名单（40001
  // unsupported content-type），axios 仅在 data 存在时自动设 Content-Type，无 data
  // 请求，登出/api-key 吊销等须显式补 application/json，有 data，对象/FormData
  // 由 axios 自动处理，不覆盖 multipart boundary
  const method = (config.method ?? 'get').toLowerCase()
  if (['post', 'put', 'patch'].includes(method) && config.data == null && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json'
  }

  let resp
  try {
    resp = await http.request<ApiResponse<T>>({ ...config, headers })
  } catch (e) {
    // 后端已返回 ApiResponse 壳，http 非 2xx，40400 映射 404、40300 映射 403、40900 映射 409 等
    // 均与 2xx 同走业务码分发，40400「接口不存在」等中文消息不再丢失
    const errBody = parseErrorBody<T>(e)
    if (errBody) return await dispatchBody<T>(errBody, config, options)
    // 无响应体，网络/超时/网关无壳响应，仍按网络异常兜底
    message.error('网络异常，请稍后重试')
    throw new ApiError(-1, '网络异常，请稍后重试')
  }

  return await dispatchBody<T>(resp.data, config, options)
}

export { ApiError, ErrorCode }
