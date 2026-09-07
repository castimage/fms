/**
 * 认证 store，token/user 会话 + 两步登录 + 静默刷新 + 登出，
 * 本地持久化 fms_access_token / fms_refresh_token / fms_user，store 初始化即恢复。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import type { UserSessionView } from '@/types/auth'

export const AUTH_KEYS = {
  accessToken: 'fms_access_token',
  refreshToken: 'fms_refresh_token',
  user: 'fms_user',
} as const

function loadUser(): UserSessionView | null {
  try {
    const raw = localStorage.getItem(AUTH_KEYS.user)
    return raw ? (JSON.parse(raw) as UserSessionView) : null
  } catch {
    // 本地数据损坏则视为未登录
    return null
  }
}

export type LoginStep = 'password' | 'totp'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(localStorage.getItem(AUTH_KEYS.accessToken))
  const refreshToken = ref<string | null>(localStorage.getItem(AUTH_KEYS.refreshToken))
  const user = ref<UserSessionView | null>(loadUser())
  /** 两步登录当前步骤，仅登录流程内存态 */
  const loginStep = ref<LoginStep>('password')
  /** 2fa 票据，仅登录流程内存态，5min 有效 */
  const ticket = ref<string | null>(null)

  const isAuthenticated = computed(() => !!accessToken.value)
  const isAdmin = computed(() => user.value?.role === 'admin')
  const displayName = computed(() => user.value?.displayName ?? user.value?.loginName ?? '未登录')

  /** 落双 token + user，登录成功 / 2fa 完成 / 静默刷新 */
  function setSession(token: string, rToken: string, u: UserSessionView) {
    accessToken.value = token
    refreshToken.value = rToken
    user.value = u
    localStorage.setItem(AUTH_KEYS.accessToken, token)
    localStorage.setItem(AUTH_KEYS.refreshToken, rToken)
    localStorage.setItem(AUTH_KEYS.user, JSON.stringify(u))
  }

  /** 清内存 + 本地存储，401 兜底 / 登出共用 */
  function clearSession() {
    accessToken.value = null
    refreshToken.value = null
    user.value = null
    ticket.value = null
    loginStep.value = 'password'
    localStorage.removeItem(AUTH_KEYS.accessToken)
    localStorage.removeItem(AUTH_KEYS.refreshToken)
    localStorage.removeItem(AUTH_KEYS.user)
  }

  /** 第一步登录，需 2fa 时存票据返回 'totp'，否则落双 token 返回 'ok'，需改密时 'ok-change' */
  async function login(loginName: string, password: string): Promise<'ok' | 'totp' | 'ok-change'> {
    const res = await authApi.login(loginName, password)
    if (res.mfaRequired) {
      ticket.value = res.ticket
      user.value = res.user
      loginStep.value = 'totp'
      return 'totp'
    }
    setSession(res.accessToken ?? '', res.refreshToken ?? '', res.user)
    return res.requirePasswordChange ? 'ok-change' : 'ok'
  }

  /** 第二步，totp 验证通过后落完整会话，需改密时返回 'ok-change' */
  async function verifyTotp(code: string): Promise<'ok' | 'ok-change'> {
    if (!ticket.value) throw new Error('缺少 2FA 票据')
    const res = await authApi.totpVerify(ticket.value, code)
    setSession(res.accessToken ?? '', res.refreshToken ?? '', res.user)
    loginStep.value = 'password'
    ticket.value = null
    return res.requirePasswordChange ? 'ok-change' : 'ok'
  }

  /** 回退到第一步，清票据，预览 user 由下一次 login 覆盖 */
  function backToPassword() {
    loginStep.value = 'password'
    ticket.value = null
  }

  /** 静默刷新，http.ts 40101 重放调用，失败抛错由拦截器统一清态跳登录 */
  async function refreshTokenSilently(): Promise<void> {
    if (!refreshToken.value) throw new Error('无 refresh token')
    const res = await authApi.refresh(refreshToken.value)
    if (!res.accessToken || !res.refreshToken) throw new Error('刷新失败')
    setSession(res.accessToken, res.refreshToken, res.user)
  }

  /** 启动/刷新页面时恢复 user，40300/40100 静默失败清态 */
  async function fetchSession(): Promise<void> {
    try {
      const u = await authApi.getSession()
      user.value = u
      localStorage.setItem(AUTH_KEYS.user, JSON.stringify(u))
    } catch {
      clearSession()
    }
  }

  /** 登出，调后端，失败忽略则清态并跳登录 */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 后端登出失败不阻塞本地清态
    }
    clearSession()
  }

  /** 自助改密，已认证，成功后令牌被服务端入黑名单，当前会话立即失效，本地清态，由页面引导重新登录 */
  async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
    await authApi.changePassword(oldPassword, newPassword)
    clearSession()
  }

  return {
    accessToken,
    refreshToken,
    user,
    loginStep,
    ticket,
    isAuthenticated,
    isAdmin,
    displayName,
    login,
    verifyTotp,
    backToPassword,
    refreshTokenSilently,
    fetchSession,
    clearSession,
    logout,
    changePassword,
  }
})
