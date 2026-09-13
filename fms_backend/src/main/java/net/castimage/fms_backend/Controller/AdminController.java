package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.ApiKeyIssueRequest;
import net.castimage.fms_backend.Model.dto.ParamUpdateRequest;
import net.castimage.fms_backend.Model.vo.ApiKeyIssueResult;
import net.castimage.fms_backend.Model.vo.ApiKeyView;
import net.castimage.fms_backend.Model.vo.AppLogView;
import net.castimage.fms_backend.Model.vo.LoginLogView;
import net.castimage.fms_backend.Model.vo.ParamView;
import net.castimage.fms_backend.Model.vo.RoleView;
import net.castimage.fms_backend.Model.vo.SystemInfoView;
import net.castimage.fms_backend.Model.vo.TaskRunView;
import net.castimage.fms_backend.Model.vo.UserView;
import net.castimage.fms_backend.Service.AdminService;
import net.castimage.fms_backend.Service.ApiKeyService;
import net.castimage.fms_backend.Service.OrgService;
import net.castimage.fms_backend.Service.SystemInfoService;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 管理侧 REST 接口（/api/admin）：系统参数读写（um.params 业务参数，敏感键白名单禁写）、
 * 应用日志查询（log.app_logs）、任务监控（log.task_runs）、API 密钥管理（um.api_keys，
 * 列表/签发/吊销，签发明文仅一次返回）；日志两表仅追加只读（FORCE RLS）。
 * 用户/角色列表为 /api/org 的兼容别名（admin.user/admin.role 模块同源数据，RLS 收敛一致）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final ApiKeyService apiKeyService;
    private final OrgService orgService;
    private final SystemInfoService systemInfoService;

    public AdminController(AdminService adminService, ApiKeyService apiKeyService, OrgService orgService,
                           SystemInfoService systemInfoService) {
        this.adminService = adminService;
        this.apiKeyService = apiKeyService;
        this.orgService = orgService;
        this.systemInfoService = systemInfoService;
    }

    /**
     * 系统信息（管理端「系统管理」页）：运行时模块状态（JVM/OS/Spring Boot/数据库/Redis）
     * + 关键运行配置摘要；密钥/口令不出现。只读展示，实时采集。
     */
    @GetMapping("/system-info")
    public ApiResponse<SystemInfoView> systemInfo() {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(systemInfoService.current());
    }

    /**
     * 系统参数分页（keyword 按 code 模糊）。
     */
    @GetMapping("/params")
    public ApiResponse<PageResult<ParamView>> pageParams(@RequestParam(defaultValue = "1") long page,
                                                         @RequestParam(defaultValue = "20") long size,
                                                         @RequestParam(required = false) String keyword) {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(adminService.pageParams(page, size, keyword));
    }

    /**
     * 参数元数据全量（管理页分组表单渲染权威源；字面路径优先于 /params/{code}）。
     */
    @GetMapping("/params/meta")
    public ApiResponse<List<net.castimage.fms_backend.Model.vo.ParamMetaView>> listParamMeta() {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(adminService.listParamMeta());
    }

    /**
     * 单参数详情（按 code；不存在 40400）。
     */
    @GetMapping("/params/{code}")
    public ApiResponse<ParamView> getParam(@PathVariable String code) {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(adminService.getParam(code));
    }

    /**
     * 参数写入（upsert）：敏感键（jwt. 前缀 / pgcrypto. 前缀 / 含 secret|password|token）40300，
     * value 须为合法 JSON。
     */
    @PutMapping("/params/{code}")
    public ApiResponse<Void> setParam(@PathVariable String code,
                                      @RequestBody @Valid ParamUpdateRequest req) {
        RequestContext.requireAdminRole();
        adminService.setParam(code, req.value());
        return ApiResponse.ok(null);
    }

    /**
     * 应用日志分页（level 精确 + logger 模糊 + 时间范围可选）。
     */
    @GetMapping("/app-logs")
    public ApiResponse<PageResult<AppLogView>> pageAppLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String logger,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(adminService.pageAppLogs(page, size, level, logger, from, to));
    }

    /**
     * 登录日志分页（loginName 模糊 + 时间范围 + success 成功失败 +
     * locked 锁定触发可选；um.login_log 按月分区只读）：admin 经 RLS 权限点 audit:read:all
     * 放行全量（PG 层 fms_operator 下 is_self 仅本人可见）。
     */
    @GetMapping("/login-logs")
    public ApiResponse<PageResult<LoginLogView>> pageLoginLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String loginName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) Boolean locked) {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(adminService.pageLoginLogs(page, size, loginName, from, to, success, locked));
    }

    /**
     * 任务执行记录分页（jobName 模糊 + status 可选）。
     */
    @GetMapping("/task-runs")
    public ApiResponse<PageResult<TaskRunView>> pageTaskRuns(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String jobName,
            @RequestParam(required = false) String status) {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(adminService.pageTaskRuns(page, size, jobName, status));
    }

    /**
     * 用户列表（admin.user 模块兼容路径，委托 /api/org 用户分页：RLS 同源收敛）。
     */
    @GetMapping("/users")
    public ApiResponse<PageResult<UserView>> pageUsers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(orgService.pageUsers(page, size, companyId, keyword, status));
    }

    /**
     * 角色列表（admin.role 模块兼容路径，委托 /api/org 角色列表）。
     */
    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> listRoles() {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(orgService.listRoles());
    }

    /**
     * API 密钥列表（admin.api_key 模块）：仅本人密钥（RLS is_self +
     * user_id 双保险），key_hash 盲索引恒不返回，key_prefix 仅展示。
     */
    @GetMapping("/api-keys")
    public ApiResponse<List<ApiKeyView>> listApiKeys() {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(apiKeyService.listMyKeys());
    }

    /**
     * API 密钥签发（admin.api_key）：经 auth.issue_api_key SECURITY DEFINER 生成
     * 'fms_'+32B hex 随机密钥，库内只存 HMAC 盲索引——明文仅本响应返回一次，
     * 前端须立即展示/复制，之后无法再取回。
     */
    @PostMapping("/api-keys")
    public ApiResponse<ApiKeyIssueResult> issueApiKey(@RequestBody @Valid ApiKeyIssueRequest req) {
        RequestContext.requireAdminRole();
        return ApiResponse.ok(apiKeyService.issue(req.name(), req.scope(), req.expiresAt()));
    }

    /**
     * API 密钥吊销（admin.api_key）：status=REVOKED + revoked_at 软删除语义（行保留与
     * 盲索引唯一约束共存）；非本人密钥 40400，重复吊销幂等。
     */
    @PostMapping("/api-keys/{id}/revoke")
    public ApiResponse<Void> revokeApiKey(@PathVariable UUID id) {
        RequestContext.requireAdminRole();
        apiKeyService.revoke(id);
        return ApiResponse.ok(null);
    }
}
