package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.CompanyRequest;
import net.castimage.fms_backend.Model.dto.DepartmentRequest;
import net.castimage.fms_backend.Model.dto.ResetPasswordRequest;
import net.castimage.fms_backend.Model.dto.RolePermRequest;
import net.castimage.fms_backend.Model.dto.RoleRequest;
import net.castimage.fms_backend.Model.dto.SodCheckRequest;
import net.castimage.fms_backend.Model.dto.UserCreateRequest;
import net.castimage.fms_backend.Model.dto.UserRoleAssignRequest;
import net.castimage.fms_backend.Model.dto.UserUpdateRequest;
import net.castimage.fms_backend.Model.vo.CompanyView;
import net.castimage.fms_backend.Model.vo.DepartmentView;
import net.castimage.fms_backend.Model.vo.ModuleNode;
import net.castimage.fms_backend.Model.vo.RoleView;
import net.castimage.fms_backend.Model.vo.SodResult;
import net.castimage.fms_backend.Model.vo.UserView;

import java.util.List;
import java.util.UUID;

/** 组织架构模块业务接口（公司/部门/用户/角色/权限树/SoD）：写路径两条——部门直接写（RLS 放行）、用户/角色/新公司经 SECURITY DEFINER 闸口，软删统一 auth.fn_soft_delete；列表越权空集、详情 40400 不泄露存在性；写操作 RBAC 预检 40300 */
public interface OrgService {

    // 公司（org.fn_upsert_company 幂等 upsert），

    /**
     * 公司分页（RLS can_access_org 收敛可见集合；keyword 匹配 code/name）。
     */
    PageResult<CompanyView> pageCompanies(long page, long size, String keyword);

    /**
     * 公司详情（越权则 RLS 空集，40400）。
     */
    CompanyView getCompany(UUID id);

    /**
     * 创建/更新公司（org.fn_upsert_company，按 code 幂等 upsert；更新须同 code）。
     */
    CompanyView upsertCompany(UUID id, CompanyRequest req);

    /**
     * 软删公司（auth.fn_soft_delete）。
     */
    void deleteCompany(UUID id);

    // 部门（ltree 树，path=父.path+'.'+code），

    /**
     * 部门分页（RLS can_access_org(company_id) 收敛；companyId/keyword 过滤提示）。
     */
    PageResult<DepartmentView> pageDepartments(long page, long size, UUID companyId, String keyword);

    /**
     * 部门详情（含 ltree path）。
     */
    DepartmentView getDepartment(UUID id);

    /**
     * 创建部门：path=父.path+'.'+code、level=父.level+1（根：path=code, level=1）。
     */
    DepartmentView createDepartment(DepartmentRequest req);

    /**
     * 更新部门（结构字段禁改，仅 name/managerId）。
     */
    DepartmentView updateDepartment(UUID id, DepartmentRequest req);

    /**
     * 软删部门（有子部门 40900）。
     */
    void deleteDepartment(UUID id);

    // 用户

    /**
     * 用户分页（RLS users_select 收敛：operator 仅本人可见，admin 全量见 SQL 侧配套；
     * companyId/keyword/status 过滤提示）。
     */
    PageResult<UserView> pageUsers(long page, long size, UUID companyId, String keyword, String status);

    /**
     * 用户详情（含角色编码集合）。
     */
    UserView getUser(UUID id);

    /**
     * 创建用户（auth.create_user；初始密码 BCrypt；建议 must_change_pwd=true）。
     */
    UserView createUser(UserCreateRequest req);

    /**
     * 更新用户（auth.update_user 白名单键补丁：email/status/must_change_pwd）。
     */
    UserView updateUser(UUID id, UserUpdateRequest req);

    /**
     * 软删用户（auth.fn_soft_delete）。
     */
    void deleteUser(UUID id);

    /**
     * 分配角色（auth.assign_role，按角色 code）。
     */
    void assignRole(UUID id, UserRoleAssignRequest req);

    /**
     * 回收角色（auth.revoke_role）。
     */
    void revokeRole(UUID id, UUID roleId);

    /**
     * 重置密码（auth.set_password + must_change_pwd=true）。
     */
    void resetPassword(UUID id, ResetPasswordRequest req);

    /**
     * 用户权限编码集合（user_role、role_perm、permissions 联查；RLS 收敛）。
     */
    List<String> userPermissions(UUID id);

    // 角色与权限

    /**
     * 角色列表（含权限绑定数）。
     */
    List<RoleView> listRoles();

    /**
     * 创建角色（auth.upsert_role，按 code 幂等）。
     */
    RoleView createRole(RoleRequest req);

    /**
     * 更新角色（仅 name；is_system 禁改 40300）。
     */
    RoleView updateRole(UUID id, RoleRequest req);

    /**
     * 软删角色（is_system 禁删 40300）。
     */
    void deleteRole(UUID id);

    /**
     * 角色-权限绑定（auth.grant_role_permission）。
     */
    void grantRolePermission(UUID roleId, RolePermRequest req);

    /**
     * 角色-权限解绑（auth.revoke_role_permission）。
     */
    void revokeRolePermission(UUID roleId, String permCode);

    /**
     * 权限树（um.modules 树 + 挂载 permissions）。
     */
    List<ModuleNode> permTree();

    /**
     * SoD 冲突检查（内置互斥对：制单↔审核、过账↔审核、过账↔冲销；仅告警不阻断）。
     */
    SodResult sodCheck(SodCheckRequest req);
}
