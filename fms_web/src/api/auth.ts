/**
 * 认证模块 api，11 个端点，login/totp/refresh/recovery 为匿名端点，网关要求不携带 Authorization。
 * 错误一律抛 ApiError，全局已提示，页面按需兜底。
 */
import { request } from '@/api/http'
import type {
  BackupCodesResponse,
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  RecoveryResponse,
  TotpEnableRequest,
  TotpSetupResponse,
  TotpVerifyRequest,
  UserSessionView,
} from '@/types/auth'

/** 第一步，账号密码登录，mfaRequired=true 时无 token，返回 ticket 供第二步 */
export function login(loginName: string, password: string): Promise<LoginResponse> {
  return request<LoginResponse>(
    { url: '/auth/login', method: 'post', data: { loginName, password } satisfies LoginRequest },
    { anonymous: true },
  )
}

/** 第二步，totp 动态码验证，票据驱动 */
export function totpVerify(ticket: string, code: string): Promise<LoginResponse> {
  return request<LoginResponse>(
    { url: '/auth/totp/verify', method: 'post', data: { ticket, code } satisfies TotpVerifyRequest },
    { anonymous: true },
  )
}

/** 静默刷新，40101 重放用，匿名端点 */
export function refresh(refreshToken: string): Promise<LoginResponse> {
  return request<LoginResponse>(
    { url: '/auth/refresh', method: 'post', data: { refreshToken } },
    { anonymous: true },
  )
}

/** 登出，可空体，失败忽略，本地照清 */
export function logout(): Promise<void> {
  return request<void>({ url: '/auth/logout', method: 'post' })
}

/** 当前会话，刷新页面/启动时恢复 user */
export function getSession(): Promise<UserSessionView> {
  return request<UserSessionView>({ url: '/auth/session', method: 'get' })
}

/** totp 启用前获取密钥，已认证 */
export function totpSetup(): Promise<TotpSetupResponse> {
  return request<TotpSetupResponse>({ url: '/auth/totp/setup', method: 'post' })
}

/** 启用 totp，密码复验 + 动态码 */
export function totpEnable(password: string, code: string): Promise<void> {
  return request<void>({
    url: '/auth/totp/enable',
    method: 'post',
    data: { password, code } satisfies TotpEnableRequest,
  })
}

/** 关闭 totp，密码复验 */
export function totpDisable(password: string): Promise<void> {
  return request<void>({ url: '/auth/totp/disable', method: 'post', data: { password } })
}

/** 生成备用恢复码 */
export function totpBackupCodes(): Promise<BackupCodesResponse> {
  return request<BackupCodesResponse>({ url: '/auth/totp/backup-codes', method: 'post' })
}

/**
 * 密码找回，防用户名探测，不存在同样返回票据占位，email 为加密盲索引校验字段，
 * 命中且服务端邮件启用时向该邮箱发重置邮件，未命中不发但响应一致
 */
export function recovery(loginName: string, email: string): Promise<RecoveryResponse> {
  return request<RecoveryResponse>(
    { url: '/auth/recovery', method: 'post', data: { loginName, email } },
    { anonymous: true },
  )
}

/** 凭票据重置密码 */
export function recoveryReset(ticket: string, newPassword: string): Promise<void> {
  return request<void>({ url: '/auth/recovery/reset', method: 'post', data: { ticket, newPassword } })
}

/**
 * 自助改密，已认证，旧密码复验 + 弱密码预检，弱密码 40001 拒绝，
 * 改密成功后服务端将当前令牌入黑名单立即失效，前端须清态重新登录。
 */
export function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  return request<void>({
    url: '/auth/change-password',
    method: 'post',
    data: {
      oldPassword,
      newPassword,
      accessToken: localStorage.getItem('fms_access_token'),
      refreshToken: localStorage.getItem('fms_refresh_token'),
    } satisfies ChangePasswordRequest,
  })
}
