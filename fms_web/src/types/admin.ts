/**
 * 管理后台模块类型，AdminController 三视图（ParamView/AppLogView/TaskRunView）
 * + OrgController 用户/角色视图复用 types/org.ts（UserView/RoleView/ModuleNode/PermissionView）。
 */
import type { PageQuery } from './api'
import type { ModuleNode, PermissionView, RoleView, UserView } from './org'

export type { ModuleNode, PermissionView, RoleView, UserView }

export type UserStatus = 'ACTIVE' | 'LOCKED' | 'DISABLED'
export type TaskRunStatus = 'OK' | 'FAIL'

/** 系统参数视图，value 为 jsonb 文本，标量参数为 json 标量文本，对象参数 {"v":...} 原样透传 */
export interface ParamView {
  id: string
  code: string
  value: string
  updatedBy: string | null
  createdAt: string
  updatedAt: string
}

/** 参数控件类型，param_meta.control_type，前端按类型渲染对应控件 */
export type ParamControlType = 'boolean' | 'number' | 'select' | 'text' | 'json'

/** 对象参数子字段元数据视图，um.param_meta_field，{"v":{...}} 形态参数按字段渲染子表单 */
export interface ParamFieldView {
  code: string
  field: string
  label: string
  controlType: ParamControlType
  unit: string | null
  minVal: number | null
  maxVal: number | null
  step: number | null
  options: string | null
  hint: string | null
  sortNo: number
}

/** 系统参数元数据视图，um.param_meta，经 um.fn_page_param_meta 读口 */
export interface ParamMetaView {
  code: string
  grp: string
  label: string
  controlType: ParamControlType
  unit: string | null
  minVal: number | null
  maxVal: number | null
  step: number | null
  /** select 控件选项，json 数组文本 [{"label":..,"value":..}] */
  options: string | null
  hint: string | null
  sortNo: number
  sensitive: boolean
  /** 对象参数子字段元数据，非空时渲染子表单，空则整段 json 编辑兜底 */
  fields: ParamFieldView[]
}

/** 应用日志行视图，context jsonb 转 String，log.app_logs 仅追加只读 */
export interface AppLogView {
  id: string
  level: string
  logger: string
  message: string
  context: string | null
  occurredAt: string
}

/** 登录日志行视图，um.login_log，成功/失败/锁定触发/失败原因，ip 为 inet 文本 */
export interface LoginLogView {
  id: string
  userId: string | null
  loginName: string
  ip: string | null
  userAgent: string | null
  success: boolean
  failReason: string | null
  lockedUntil: string | null
  occurredAt: string
}

/** 定时任务执行记录视图，detail jsonb 转 String，log.task_runs */
export interface TaskRunView {
  id: string
  jobName: string
  status: TaskRunStatus
  detail: string | null
  startedAt: string
  finishedAt: string | null
}

/** 用户创建请求，初始密码服务端 bcrypt，mustChangePwd 缺省 true */
export interface UserCreateRequest {
  loginName: string
  password: string
  displayName?: string
  email?: string
  companyId: string
  mustChangePwd?: boolean
}

/** 用户更新请求，auth.update_user 白名单键补丁，email/status/mustChangePwd */
export interface UserUpdateRequest {
  email?: string
  status?: UserStatus
  mustChangePwd?: boolean
}

/** 角色创建/更新请求，按 code 幂等 upsert，唯一且小写约束由后端校验 */
export interface RoleRequest {
  code: string
  name: string
}

/** 用户分页查询，companyId/keyword/status 过滤提示 */
export interface UserQuery extends PageQuery {
  companyId?: string
  keyword?: string
  status?: UserStatus | ''
}

/** 参数分页查询，keyword 按 code 模糊 */
export interface ParamQuery extends PageQuery {
  keyword?: string
}

/** 应用日志分页查询，level 精确 + logger 模糊 + 时间范围，from/to 为 ISO 日期时间 */
export interface AppLogQuery extends PageQuery {
  level?: string
  logger?: string
  from?: string
  to?: string
}

/** 登录日志分页查询，loginName 模糊 + 时间范围 + success 成功失败 + locked 锁定触发，from/to 为 ISO 日期时间 */
export interface LoginLogQuery extends PageQuery {
  loginName?: string
  from?: string
  to?: string
  success?: boolean
  locked?: boolean
}

/** 任务记录分页查询，jobName 模糊 + status 可选 */
export interface TaskRunQuery extends PageQuery {
  jobName?: string
  status?: TaskRunStatus | ''
}

/** api Key 作用域（-2，read 映射 fms_viewer、read_write 映射 fms_operator） */
export type ApiKeyScope = 'read' | 'read_write'
export type ApiKeyStatus = 'ACTIVE' | 'DISABLED' | 'REVOKED'

/** api Key 视图，um.api_keys，key_hash 盲索引恒不返回，key_prefix 仅展示 */
export interface ApiKeyView {
  id: string
  name: string
  keyPrefix: string
  scope: ApiKeyScope
  status: ApiKeyStatus
  expiresAt: string | null
  revokedAt: string | null
  lastUsedAt: string | null
  createdAt: string
}

/** api Key 签发请求，name ≤64，expiresAt 空 = 永不过期 */
export interface ApiKeyIssueRequest {
  name: string
  scope: ApiKeyScope
  expiresAt?: string | null
}

/** api Key 签发结果，明文仅此一次返回，前端须立即展示/复制 */
export interface ApiKeyIssueResult {
  apiKey: string
  keyPrefix: string
}

/** 系统信息，/api/admin/system-info，运行时模块状态 + 集群综合信息 + 关键配置摘要，密钥不外泄 */
export interface SystemInfoView {
  javaVersion: string
  osName: string
  osArch: string
  startedAt: string
  uptimeSecs: number
  springBootVersion: string
  db: { version: string; host: string; reachable: boolean }
  redis: { mode: 'in-memory' | 'redis'; reachable: boolean; version: string; keys: number | null; usedMemoryBytes: number | null; usedMemoryPct: number }
  /** 集群后端实例列表，经 fms.cluster.peers 探测聚合，任意数量 */
  backends: BackendInfo[]
  /** Redis 集群节点列表，哨兵模式聚合主/从/哨兵，单节点模式仅当前节点 */
  redisNodes: RedisNodeInfo[]
  /** PostgreSQL 主从节点列表，主库 + pg_stat_replication 发现的从库 */
  dbNodes: DbNodeInfo[]
  configSummary: Record<string, string>
}

/** 集群后端实例信息 */
export interface BackendInfo {
  instance: string
  host: string
  reachable: boolean
  javaVersion: string
  springBootVersion: string
  uptimeSecs: number
  redisMode: string
}

/** Redis 集群节点信息 */
export interface RedisNodeInfo {
  role: 'master' | 'replica' | 'sentinel'
  name: string
  host: string
  port: number
  status: string
  version: string
  keys: number | null
  usedMemoryBytes: number | null
}

/** PostgreSQL 主从节点信息，replayLagBytes 为 WAL 复制延迟，-1 表示未知 */
export interface DbNodeInfo {
  role: 'master' | 'replica'
  name: string
  host: string
  state: string
  syncState: string
  replayLagBytes: number
}
