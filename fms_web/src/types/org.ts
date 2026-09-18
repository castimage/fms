/**
 * 组织模块类型，与后端 CompanyView/DepartmentView/UserView/RoleView/ModuleNode/
 * PermissionView/SodResult 逐字段一致。本批仅定义类型 + api 函数，管理后台消费。
 */

/** 公司视图 */
export interface CompanyView {
  id: string
  code: string
  name: string
  currency: string
  fiscalYearStart: string
  status: string
  createdAt: string
}

/** 部门视图 */
export interface DepartmentView {
  id: string
  companyId: string
  code: string
  name: string
  parentId: string | null
  status: string
  createdAt: string
}

/**
 * 用户视图，roles 为 jwt role 短名列表
 *
 * email 由后端经 auth.get_user_email 闸口解出，管理员得全部，其余人仅得本人，
 * 无权与未设邮箱同为 null，前端一律按未展示处理，不区分两者
 */
export interface UserView {
  id: string
  loginName: string
  displayName: string
  status: string
  mfaEnabled: boolean
  mustChangePwd: boolean
  lastLoginAt: string | null
  companyId: string | null
  email: string | null
  roles: string[]
}

/** 角色视图，非分页列表 */
export interface RoleView {
  id: string
  companyId: string | null
  code: string
  name: string
  isSystem: boolean
  permissionCount: number
}

/** 权限点视图 */
export interface PermissionView {
  id: string
  code: string
  name: string
  description: string | null
}

/** 模块树节点，权限挂树 */
export interface ModuleNode {
  id: string
  identifier: string
  name: string
  description: string | null
  parentId: string | null
  permissions: PermissionView[]
  children: ModuleNode[]
}

/** 职责分离（SoD）冲突检测结果 */
export interface SodResult {
  conflict: boolean
  pairs: { permA: string; permB: string }[]
}

/** 用户数据范围视图，auth.list_user_scopes 返回，部门范围含子树开关对全部部门统一设置 */
export interface UserScopeView {
  orgIds: string[]
  deptIds: string[]
  includeSubtree: boolean
}

/** 用户数据范围配置请求，全量替换语义，空数组表示清空该维度 */
export interface UserScopeRequest {
  orgIds: string[]
  deptIds: string[]
  includeSubtree: boolean
}
