/**
 * 管理后台 api，/api/admin 端点，AdminController，参数/应用日志/任务记录
 * + /api/org 用户/角色端点，OrgController，管理后台消费，与 api/org.ts 并存。
 */
import { request } from './http'
import type { PageResult } from '@/types/api'
import type {
  ApiKeyIssueRequest,
  ApiKeyIssueResult,
  ApiKeyView,
  AppLogQuery,
  AppLogView,
  LoginLogQuery,
  LoginLogView,
  ModuleNode,
  ParamQuery,
  SystemInfoView,
  ParamMetaView,
  ParamView,
  RoleRequest,
  RoleView,
  TaskRunQuery,
  TaskRunView,
  UserCreateRequest,
  UserQuery,
  UserUpdateRequest,
  UserView,
} from '@/types/admin'
import type { UserScopeRequest, UserScopeView } from '@/types/org'

export const adminApi = {
  // 系统参数（/api/admin/params）
  pageParams(q: ParamQuery): Promise<PageResult<ParamView>> {
    return request<PageResult<ParamView>>({ url: '/admin/params', method: 'get', params: q })
  },
  getParam(code: string): Promise<ParamView | null> {
    return request<ParamView | null>({ url: `/admin/params/${code}`, method: 'get' })
  },
  /** 参数元数据全量，管理页分组表单渲染权威源 */
  listParamMeta(): Promise<ParamMetaView[]> {
    return request<ParamMetaView[]>({ url: '/admin/params/meta', method: 'get' })
  },
  /** 参数写入，upsert，敏感键禁写与 json 合法性由后端 40300/400 兜底 */
  setParam(code: string, value: string): Promise<void> {
    return request<void>({ url: `/admin/params/${code}`, method: 'put', data: { value } })
  },

  // 应用日志 / 登录日志 / 任务记录
  pageAppLogs(q: AppLogQuery): Promise<PageResult<AppLogView>> {
    return request<PageResult<AppLogView>>({ url: '/admin/app-logs', method: 'get', params: q })
  },
  /** 登录日志分页，登录审计，loginName 模糊 + 时间范围 + success/locked 过滤 */
  pageLoginLogs(q: LoginLogQuery): Promise<PageResult<LoginLogView>> {
    return request<PageResult<LoginLogView>>({ url: '/admin/login-logs', method: 'get', params: q })
  },
  pageTaskRuns(q: TaskRunQuery): Promise<PageResult<TaskRunView>> {
    return request<PageResult<TaskRunView>>({ url: '/admin/task-runs', method: 'get', params: q })
  },

  // 用户（/api/org/users）
  pageUsers(q: UserQuery): Promise<PageResult<UserView>> {
    return request<PageResult<UserView>>({ url: '/org/users', method: 'get', params: q })
  },
  createUser(req: UserCreateRequest): Promise<UserView> {
    return request<UserView>({ url: '/org/users', method: 'post', data: req })
  },
  updateUser(id: string, req: UserUpdateRequest): Promise<UserView> {
    return request<UserView>({ url: `/org/users/${id}`, method: 'put', data: req })
  },
  deleteUser(id: string): Promise<void> {
    return request<void>({ url: `/org/users/${id}`, method: 'delete' })
  },
  /** 分配角色，按 roleId，服务端取 code 后经闸口幂等分配 */
  assignRole(id: string, roleId: string): Promise<void> {
    return request<void>({ url: `/org/users/${id}/roles`, method: 'post', data: { roleId } })
  },
  revokeRole(id: string, roleId: string): Promise<void> {
    return request<void>({ url: `/org/users/${id}/roles/${roleId}`, method: 'delete' })
  },
  /** 重置密码（auth.set_password + must_change_pwd=true） */
  resetPassword(id: string, newPassword: string): Promise<void> {
    return request<void>({ url: `/org/users/${id}/password`, method: 'post', data: { newPassword } })
  },

  // 角色与权限（/api/org）
  listRoles(): Promise<RoleView[]> {
    return request<RoleView[]>({ url: '/org/roles', method: 'get' })
  },
  /** 读取用户数据范围（公司 + 部门 + 含子树开关），管理员 */
  getUserScopes(id: string): Promise<UserScopeView> {
    return request<UserScopeView>({ url: `/org/users/${id}/scopes`, method: 'get' })
  },
  /** 配置用户数据范围，全量替换，空数组表示清空该维度，管理员 */
  setUserScopes(id: string, req: UserScopeRequest): Promise<void> {
    return request<void>({ url: `/org/users/${id}/scopes`, method: 'put', data: req })
  },
  createRole(req: RoleRequest): Promise<RoleView> {
    return request<RoleView>({ url: '/org/roles', method: 'post', data: req })
  },
  updateRole(id: string, req: RoleRequest): Promise<RoleView> {
    return request<RoleView>({ url: `/org/roles/${id}`, method: 'put', data: req })
  },
  deleteRole(id: string): Promise<void> {
    return request<void>({ url: `/org/roles/${id}`, method: 'delete' })
  },
  grantRolePermission(id: string, permCode: string): Promise<void> {
    return request<void>({ url: `/org/roles/${id}/permissions`, method: 'post', data: { permCode } })
  },
  revokeRolePermission(id: string, permCode: string): Promise<void> {
    return request<void>({ url: `/org/roles/${id}/permissions/${permCode}`, method: 'delete' })
  },
  /** 权限模块树，um.modules 树 + 挂载 permissions */
  permTree(): Promise<ModuleNode[]> {
    return request<ModuleNode[]>({ url: '/org/perm-tree', method: 'get' })
  },

  // api Key，/api/admin/api-keys，系统间集成，签发明文仅一次返回
  listApiKeys(): Promise<ApiKeyView[]> {
    return request<ApiKeyView[]>({ url: '/admin/api-keys', method: 'get' })
  },
  createApiKey(req: ApiKeyIssueRequest): Promise<ApiKeyIssueResult> {
    return request<ApiKeyIssueResult>({ url: '/admin/api-keys', method: 'post', data: req })
  },
  revokeApiKey(id: string): Promise<void> {
    return request<void>({ url: `/admin/api-keys/${id}/revoke`, method: 'post' })
  },

  // 系统信息，/api/admin/system-info，模块状态 + 配置摘要，admin 专属
  systemInfo(): Promise<SystemInfoView> {
    return request<SystemInfoView>({ url: '/admin/system-info', method: 'get' })
  },
}
