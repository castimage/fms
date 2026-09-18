package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.CompanyRequest;
import net.castimage.fms_backend.Model.dto.DepartmentRequest;
import net.castimage.fms_backend.Model.dto.ResetPasswordRequest;
import net.castimage.fms_backend.Model.dto.RolePermRequest;
import net.castimage.fms_backend.Model.dto.RoleRequest;
import net.castimage.fms_backend.Model.dto.SodCheckRequest;
import net.castimage.fms_backend.Model.dto.UserCreateRequest;
import net.castimage.fms_backend.Model.dto.UserRoleAssignRequest;
import net.castimage.fms_backend.Model.dto.UserScopeRequest;
import net.castimage.fms_backend.Model.dto.UserUpdateRequest;
import net.castimage.fms_backend.Model.vo.CompanyView;
import net.castimage.fms_backend.Model.vo.DepartmentView;
import net.castimage.fms_backend.Model.vo.ModuleNode;
import net.castimage.fms_backend.Model.vo.RoleView;
import net.castimage.fms_backend.Model.vo.SodResult;
import net.castimage.fms_backend.Model.vo.UserScopeView;
import net.castimage.fms_backend.Model.vo.UserView;
import net.castimage.fms_backend.Service.OrgService;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;

/**
 * 组织架构模块 REST 接口（/api/org）：公司/部门/用户 CRUD、角色分配与回收、密码重置、权限树（perm-tree）、SoD 冲突检查。
 * 全部端点需已认证（网关 JWT 验签 + 签名校验注入身份）；写操作 RBAC 预检（viewer/auditor/report 只读 40300），
 * 敏感写路径经 SECURITY DEFINER 闸口；列表接口 RLS 直接过滤（越权空集），详情越权 40400。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/org")
public class OrgController {

    private final OrgService orgService;


    // 公司

    /**
     * 公司分页（RLS can_access_org 收敛；keyword 匹配 code/name）。
     */
    @GetMapping("/companies")
    public ApiResponse<PageResult<CompanyView>> pageCompanies(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(orgService.pageCompanies(page, size, keyword));
    }

    /**
     * 公司详情（越权则 40400）。
     */
    @GetMapping("/companies/{id}")
    public ApiResponse<CompanyView> getCompany(@PathVariable UUID id) {
        return ApiResponse.ok(orgService.getCompany(id));
    }

    /**
     * 创建公司（org.fn_upsert_company 幂等 upsert，按 code）。
     */
    @PostMapping("/companies")
    public ApiResponse<CompanyView> createCompany(@RequestBody @Valid CompanyRequest req) {
        return ApiResponse.ok(orgService.upsertCompany(null, req));
    }

    /**
     * 更新公司（同 code upsert）。
     */
    @PutMapping("/companies/{id}")
    public ApiResponse<CompanyView> updateCompany(@PathVariable UUID id,
                                                  @RequestBody @Valid CompanyRequest req) {
        return ApiResponse.ok(orgService.upsertCompany(id, req));
    }

    /**
     * 软删公司（auth.fn_soft_delete）。
     */
    @DeleteMapping("/companies/{id}")
    public ApiResponse<Void> deleteCompany(@PathVariable UUID id) {
        orgService.deleteCompany(id);
        return ApiResponse.ok(null);
    }

    // 部门

    /**
     * 部门分页（companyId/keyword 过滤提示，RLS 收敛）。
     */
    @GetMapping("/departments")
    public ApiResponse<PageResult<DepartmentView>> pageDepartments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(orgService.pageDepartments(page, size, companyId, keyword));
    }

    /**
     * 部门详情（含 ltree path）。
     */
    @GetMapping("/departments/{id}")
    public ApiResponse<DepartmentView> getDepartment(@PathVariable UUID id) {
        return ApiResponse.ok(orgService.getDepartment(id));
    }

    /**
     * 创建部门（path=父.path+'.'+code，level=父+1；ltree 标签仅字母/数字/下划线）。
     */
    @PostMapping("/departments")
    public ApiResponse<DepartmentView> createDepartment(@RequestBody @Valid DepartmentRequest req) {
        return ApiResponse.ok(orgService.createDepartment(req));
    }

    /**
     * 更新部门（结构字段禁改，仅 name/managerId）。
     */
    @PutMapping("/departments/{id}")
    public ApiResponse<DepartmentView> updateDepartment(@PathVariable UUID id,
                                                        @RequestBody @Valid DepartmentRequest req) {
        return ApiResponse.ok(orgService.updateDepartment(id, req));
    }

    /**
     * 软删部门（有子部门 40900）。
     */
    @DeleteMapping("/departments/{id}")
    public ApiResponse<Void> deleteDepartment(@PathVariable UUID id) {
        orgService.deleteDepartment(id);
        return ApiResponse.ok(null);
    }

    // 用户

    /**
     * 用户分页（RLS users_select 收敛；companyId/keyword/status 过滤提示）。
     */
    @GetMapping("/users")
    public ApiResponse<PageResult<UserView>> pageUsers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(orgService.pageUsers(page, size, companyId, keyword, status));
    }

    /**
     * 用户详情（含角色集合）。
     */
    @GetMapping("/users/{id}")
    public ApiResponse<UserView> getUser(@PathVariable UUID id) {
        return ApiResponse.ok(orgService.getUser(id));
    }

    /**
     * 创建用户（auth.create_user；初始密码 BCrypt；默认首次登录须改密）。
     */
    @PostMapping("/users")
    public ApiResponse<UserView> createUser(@RequestBody @Valid UserCreateRequest req) {
        return ApiResponse.ok(orgService.createUser(req));
    }

    /**
     * 更新用户（auth.update_user 白名单键补丁）。
     */
    @PutMapping("/users/{id}")
    public ApiResponse<UserView> updateUser(@PathVariable UUID id,
                                            @RequestBody @Valid UserUpdateRequest req) {
        return ApiResponse.ok(orgService.updateUser(id, req));
    }

    /**
     * 软删用户（auth.fn_soft_delete）。
     */
    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable UUID id) {
        orgService.deleteUser(id);
        return ApiResponse.ok(null);
    }

    /**
     * 分配角色（auth.assign_role，按角色 code）。
     */
    @PostMapping("/users/{id}/roles")
    public ApiResponse<Void> assignRole(@PathVariable UUID id,
                                        @RequestBody @Valid UserRoleAssignRequest req) {
        orgService.assignRole(id, req); // 按 code 幂等分配，
        return ApiResponse.ok(null);
    }

    /**
     * 回收角色（auth.revoke_role）。
     */
    @DeleteMapping("/users/{id}/roles/{roleId}")
    public ApiResponse<Void> revokeRole(@PathVariable UUID id, @PathVariable UUID roleId) {
        orgService.revokeRole(id, roleId);
        return ApiResponse.ok(null);
    }

    /**
     * 读取用户数据范围（auth.list_user_scopes），返回公司与部门范围，管理员专属。
     */
    @GetMapping("/users/{id}/scopes")
    public ApiResponse<UserScopeView> getUserScopes(@PathVariable UUID id) {
        return ApiResponse.ok(orgService.getUserScopes(id));
    }

    /**
     * 配置用户数据范围（auth.set_user_org_scope + auth.set_user_dept_scope），全量替换，空数组表示清空，管理员专属。
     */
    @PutMapping("/users/{id}/scopes")
    public ApiResponse<Void> setUserScopes(@PathVariable UUID id,
                                           @RequestBody @Valid UserScopeRequest req) {
        orgService.setUserScopes(id, req);
        return ApiResponse.ok(null);
    }

    /**
     * 重置密码（auth.set_password + must_change_pwd=true）。
     */
    @PostMapping("/users/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable UUID id,
                                           @RequestBody @Valid ResetPasswordRequest req) {
        orgService.resetPassword(id, req);
        return ApiResponse.ok(null);
    }

    /**
     * 用户权限编码集合（user_role、role_perm、permissions 联查）。
     */
    @GetMapping("/users/{id}/permissions")
    public ApiResponse<List<String>> permissions(@PathVariable UUID id) {
        return ApiResponse.ok(orgService.userPermissions(id));
    }

    // 角色与权限

    /**
     * 角色列表（含权限绑定数）。
     */
    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> listRoles() {
        return ApiResponse.ok(orgService.listRoles());
    }

    /**
     * 创建角色（auth.upsert_role，按 code 幂等）。
     */
    @PostMapping("/roles")
    public ApiResponse<RoleView> createRole(@RequestBody @Valid RoleRequest req) {
        return ApiResponse.ok(orgService.createRole(req));
    }

    /**
     * 更新角色（仅 name；is_system 禁改 40300）。
     */
    @PutMapping("/roles/{id}")
    public ApiResponse<RoleView> updateRole(@PathVariable UUID id,
                                            @RequestBody @Valid RoleRequest req) {
        return ApiResponse.ok(orgService.updateRole(id, req));
    }

    /**
     * 软删角色（is_system 禁删 40300）。
     */
    @DeleteMapping("/roles/{id}")
    public ApiResponse<Void> deleteRole(@PathVariable UUID id) {
        orgService.deleteRole(id);
        return ApiResponse.ok(null);
    }

    /**
     * 角色-权限绑定（auth.grant_role_permission）。
     */
    @PostMapping("/roles/{id}/permissions")
    public ApiResponse<Void> grantRolePermission(@PathVariable UUID id,
                                                 @RequestBody @Valid RolePermRequest req) {
        orgService.grantRolePermission(id, req);
        return ApiResponse.ok(null);
    }

    /**
     * 角色-权限解绑（auth.revoke_role_permission）。
     */
    @DeleteMapping("/roles/{id}/permissions/{permCode}")
    public ApiResponse<Void> revokeRolePermission(@PathVariable UUID id,
                                                  @PathVariable String permCode) {
        orgService.revokeRolePermission(id, permCode);
        return ApiResponse.ok(null);
    }

    /**
     * 角色已绑定的权限编码集合，供授权界面回显与差量解绑，管理员专属。
     */
    @GetMapping("/roles/{id}/permissions")
    public ApiResponse<List<String>> rolePermissions(@PathVariable UUID id) {
        return ApiResponse.ok(orgService.rolePermissions(id));
    }

    /**
     * 权限树（um.modules 树 + 挂载 permissions）。
     */
    @GetMapping("/perm-tree")
    public ApiResponse<List<ModuleNode>> permTree() {
        return ApiResponse.ok(orgService.permTree());
    }

    /**
     * 权限端点别名（任务矩阵路径 /api/org/permissions 期望 200）：与 /api/org/perm-tree
     * 同源返回模块树 + 权限点，兼容既有前端消费口径。
     */
    @GetMapping("/permissions")
    public ApiResponse<List<ModuleNode>> permissionsTree() {
        return ApiResponse.ok(orgService.permTree());
    }

    /**
     * SoD 冲突检查（仅告警不阻断；管理员专属，权限矩阵 admin-only）。
     */
    @PostMapping("/sod/check")
    public ApiResponse<SodResult> sodCheck(@RequestBody @Valid SodCheckRequest req) {
        RequestContext.requireAdminRole(); // 系统管理分析端点，仅 admin 可调用，
        return ApiResponse.ok(orgService.sodCheck(req)); // 告警级检查，
    }
}
