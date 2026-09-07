/**
 * 组织模块 api，22 个端点，供管理后台/公司选择器消费，
 * 分页参数 page/size 为后端统一风格。
 */
import { request } from '@/api/http'
import type { PageQuery, PageResult } from '@/types/api'
import type {
  CompanyView,
  DepartmentView,
  RoleView,
  SodResult,
  UserView,
} from '@/types/org'

// 公司

export function pageCompanies(q: PageQuery & { keyword?: string }): Promise<PageResult<CompanyView>> {
  return request<PageResult<CompanyView>>({ url: '/org/companies', method: 'get', params: q })
}

export function getCompany(id: string): Promise<CompanyView | null> {
  return request<CompanyView | null>({ url: `/org/companies/${id}`, method: 'get' })
}

export function createCompany(req: Partial<CompanyView>): Promise<CompanyView> {
  return request<CompanyView>({ url: '/org/companies', method: 'post', data: req })
}

export function updateCompany(id: string, req: Partial<CompanyView>): Promise<CompanyView> {
  return request<CompanyView>({ url: `/org/companies/${id}`, method: 'put', data: req })
}

export function deleteCompany(id: string): Promise<void> {
  return request<void>({ url: `/org/companies/${id}`, method: 'delete' })
}

// 部门

export function pageDepartments(
  q: PageQuery & { companyId?: string; keyword?: string },
): Promise<PageResult<DepartmentView>> {
  return request<PageResult<DepartmentView>>({ url: '/org/departments', method: 'get', params: q })
}

export function getDepartment(id: string): Promise<DepartmentView | null> {
  return request<DepartmentView | null>({ url: `/org/departments/${id}`, method: 'get' })
}

export function createDepartment(req: Partial<DepartmentView>): Promise<DepartmentView> {
  return request<DepartmentView>({ url: '/org/departments', method: 'post', data: req })
}

export function updateDepartment(id: string, req: Partial<DepartmentView>): Promise<DepartmentView> {
  return request<DepartmentView>({ url: `/org/departments/${id}`, method: 'put', data: req })
}

export function deleteDepartment(id: string): Promise<void> {
  return request<void>({ url: `/org/departments/${id}`, method: 'delete' })
}

// 用户

export function pageUsers(
  q: PageQuery & { companyId?: string; keyword?: string; status?: string },
): Promise<PageResult<UserView>> {
  return request<PageResult<UserView>>({ url: '/org/users', method: 'get', params: q })
}

export function getUser(id: string): Promise<UserView | null> {
  return request<UserView | null>({ url: `/org/users/${id}`, method: 'get' })
}

export function createUser(req: Partial<UserView> & { password: string }): Promise<UserView> {
  return request<UserView>({ url: '/org/users', method: 'post', data: req })
}

export function updateUser(id: string, req: Partial<UserView>): Promise<UserView> {
  return request<UserView>({ url: `/org/users/${id}`, method: 'put', data: req })
}

export function deleteUser(id: string): Promise<void> {
  return request<void>({ url: `/org/users/${id}`, method: 'delete' })
}

/** 回收角色 */
export function revokeRole(id: string, roleId: string): Promise<void> {
  return request<void>({ url: `/org/users/${id}/roles/${roleId}`, method: 'delete' })
}

/** 重置密码，管理员 */
export function resetPassword(id: string, newPassword: string): Promise<void> {
  return request<void>({ url: `/org/users/${id}/password`, method: 'post', data: { newPassword } })
}

/** 用户权限码列表 */
export function userPermissions(id: string): Promise<string[]> {
  return request<string[]>({ url: `/org/users/${id}/permissions`, method: 'get' })
}

// 角色 / 权限

export function listRoles(): Promise<RoleView[]> {
  return request<RoleView[]>({ url: '/org/roles', method: 'get' })
}

export function createRole(req: { code: string; name: string }): Promise<RoleView> {
  return request<RoleView>({ url: '/org/roles', method: 'post', data: req })
}

export function updateRole(id: string, req: { name: string }): Promise<RoleView> {
  return request<RoleView>({ url: `/org/roles/${id}`, method: 'put', data: req })
}

export function deleteRole(id: string): Promise<void> {
  return request<void>({ url: `/org/roles/${id}`, method: 'delete' })
}

/** 角色授予权限 */
export function grantRolePermission(id: string, permCode: string): Promise<void> {
  return request<void>({ url: `/org/roles/${id}/permissions`, method: 'post', data: { permCode } })
}

/** 角色回收权限 */
export function revokeRolePermission(id: string, permCode: string): Promise<void> {
  return request<void>({ url: `/org/roles/${id}/permissions/${permCode}`, method: 'delete' })
}

/** 职责分离（SoD）冲突检测 */
export function sodCheck(req: { roleId: string; permCode: string }): Promise<SodResult> {
  return request<SodResult>({ url: '/org/sod/check', method: 'post', data: req })
}
