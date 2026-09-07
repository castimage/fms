/**
 * 统一 api 响应 / 错误类型，与后端 ApiResponse / ApiError 逐字段一致
 */

/** 后端统一响应壳，code=0 且 message=ok 为成功，traceId 恒返回，排障关键 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T | null
  traceId: string
}

/** 后端统一分页结构 */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 通用分页查询参数 */
export interface PageQuery {
  page?: number
  size?: number
}

/** 错误码常量，erasableSyntaxOnly 禁止 enum，用 as const + 联合类型 */
export const ErrorCode = {
  OK: 0,
  BAD_REQUEST: 40001,
  UNAUTHORIZED: 40100,
  TOKEN_EXPIRED: 40101,
  FORBIDDEN: 40300,
  NOT_FOUND: 40400,
  CONFLICT: 40900,
  RATE_LIMITED: 42900,
  INTERNAL: 50000,
} as const

export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode]

/** 业务错误，code/message/traceId 随 http.ts 解包失败向上抛，页面按需兜底 */
export class ApiError extends Error {
  readonly code: number
  readonly traceId?: string

  // erasableSyntaxOnly 禁构造参数属性，显式声明字段
  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }

  /** 40100/40101，登录态失效 */
  isAuthExpired(): boolean {
    return this.code === ErrorCode.UNAUTHORIZED || this.code === ErrorCode.TOKEN_EXPIRED
  }

  /** 42900，限流 */
  isRateLimited(): boolean {
    return this.code === ErrorCode.RATE_LIMITED
  }
}
