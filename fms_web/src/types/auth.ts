/**
 * 认证模块类型，与后端 LoginResponse / UserSessionView / TotpSetupResponse 等 record 逐字段一致
 */

/** 用户会话视图，登录响应 / GET /auth/session，字段白名单与后端一致 */
export interface UserSessionView {
  id: string
  loginName: string
  displayName: string
  /** jwt role 短名，首批权限判断依据 */
  role: string
  mfaEnabled: boolean
  mustChangePwd: boolean
  /** ISO LocalDateTime，可为 null */
  lastLoginAt: string | null
  companyId: string | null
}

/** 登录响应，jwt 令牌对 或 2fa 两步票据，mfaRequired=true 时无令牌 */
export interface LoginResponse {
  /** access token，2h，2fa 未完成时为 null */
  accessToken: string | null
  /** refresh token，7d，2fa 未完成时为 null */
  refreshToken: string | null
  /** true 时进入第二步 totp 验证，无 token */
  mfaRequired: boolean
  /** 两步票据，5min 有效，mfaRequired=true 时非 null */
  ticket: string | null
  user: UserSessionView
  /** 弱密码标记，默认 false */
  requirePasswordChange: boolean
}

/** 登录请求，匿名端点 */
export interface LoginRequest {
  loginName: string
  password: string
}

/** totp 二次验证请求，匿名端点，登录第 2 步 */
export interface TotpVerifyRequest {
  ticket: string
  /** totp 动态码，6 位 */
  code: string
}

/** totp 启用请求，已认证，密码复验 + 动态码 */
export interface TotpEnableRequest {
  password: string
  code: string
}

/** totp setup 响应，密钥仅本次返回，enable 才落库 */
export interface TotpSetupResponse {
  /** Base32 密钥，扫码 / 手动录入用 */
  secretBase32: string
  /** otpauth uri（otpauth://totp/FMS:loginName?...），前端直接渲染 QR */
  otpauthUrl: string
  /** 密钥在服务端内存的有效期 */
  expiresAt: string
}

/** 备用恢复码响应，一次性返回 */
export interface BackupCodesResponse {
  codes: string[]
  expiresAt: string
}

/** 令牌刷新请求，匿名端点 */
export interface RefreshRequest {
  refreshToken: string
}

/**
 * 密码找回响应，不存在时同样返回票据占位，防用户名探测，邮件链路已闭环，
 * 命中且邮件启用时服务端已向请求邮箱发送重置邮件，前端统一提示"若账号存在，邮件已发送"
 */
export interface RecoveryResponse {
  /** 一次性重置票据 */
  ticket: string
  expiresAt: string
}

/** 自助改密请求，已认证端点，旧密码复验 + 新密码弱密码预检，令牌随请求携带，改密成功后服务端入黑名单 */
export interface ChangePasswordRequest {
  /** 旧密码，bcrypt 复验 */
  oldPassword: string
  /** 新密码，长度 8-64，弱密码拒绝 */
  newPassword: string
  /** 当前 access token，改密成功后失效，强制重新登录 */
  accessToken: string | null
  /** 当前 refresh token，改密成功后失效，强制重新登录 */
  refreshToken: string | null
}
