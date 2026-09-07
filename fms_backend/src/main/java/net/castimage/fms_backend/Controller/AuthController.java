package net.castimage.fms_backend.Controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.dto.BackupCodesResponse;
import net.castimage.fms_backend.Model.dto.ChangePasswordRequest;
import net.castimage.fms_backend.Model.dto.LoginRequest;
import net.castimage.fms_backend.Model.dto.LoginResponse;
import net.castimage.fms_backend.Model.dto.LogoutRequest;
import net.castimage.fms_backend.Model.dto.RecoveryRequest;
import net.castimage.fms_backend.Model.dto.RecoveryResetRequest;
import net.castimage.fms_backend.Model.dto.RecoveryResponse;
import net.castimage.fms_backend.Model.dto.RefreshRequest;
import net.castimage.fms_backend.Model.dto.TotpEnableRequest;
import net.castimage.fms_backend.Model.dto.TotpPasswordRequest;
import net.castimage.fms_backend.Model.dto.TotpSetupResponse;
import net.castimage.fms_backend.Model.dto.TotpVerifyRequest;
import net.castimage.fms_backend.Model.vo.UserSessionView;
import net.castimage.fms_backend.Service.AuthService;
import net.castimage.fms_backend.Utils.RequestUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

/**
 * 认证模块 REST 接口（/api/auth）：登录（BCrypt + 2FA 两步）/refresh/logout/session、TOTP setup/enable/verify/disable/backup-codes、密码找回 recovery/reset。
 * login/refresh/logout/totp verify/recovery 为匿名端点（网关白名单放行但同样注入网关签名）；totp setup/enable/disable/backup-codes/session 需已认证
 * （身份经 X-FMS-Signature 校验采信，RequestContext 提供）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;


    /**
     * 登录（匿名）：BCrypt 校验，随后 2FA 检测，再签发 JWT 或两步票据。
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest req,
                                            HttpServletRequest request) {
        return ApiResponse.ok(authService.login(req,
                RequestUtil.getClientIp(request), request.getHeader("User-Agent")));
    }

    /**
     * TOTP 二次验证（匿名，登录第 2 步）：票据 + 动态码校验通过则签发 JWT。
     */
    @PostMapping("/totp/verify")
    public ApiResponse<LoginResponse> totpVerify(@RequestBody @Valid TotpVerifyRequest req,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(authService.verifyTotp(req,
                RequestUtil.getClientIp(request), request.getHeader("User-Agent")));
    }

    /**
     * 令牌刷新（匿名）：refresh 验签（auth.jwt_verify + type=refresh）通过后重签。
     */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@RequestBody @Valid RefreshRequest req) {
        return ApiResponse.ok(authService.refresh(req));
    }

    /**
     * 登出（可空请求体）：access/refresh 入内存黑名单。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) @Valid LogoutRequest req) {
        authService.logout(req);
        return ApiResponse.ok(null);
    }

    /**
     * 当前会话信息（已认证）：身份取自 RequestContext（网关签名校验采信）。
     */
    @GetMapping("/session")
    public ApiResponse<UserSessionView> session() {
        return ApiResponse.ok(authService.session());
    }

    /**
     * TOTP setup（已认证）：生成密钥 + otpauth URI（不写库）。
     */
    @PostMapping("/totp/setup")
    public ApiResponse<TotpSetupResponse> totpSetup() {
        return ApiResponse.ok(authService.totpSetup());
    }

    /**
     * 启用 TOTP（已认证）：密码复验 + 动态码校验通过则 auth.set_mfa 持久化。
     */
    @PostMapping("/totp/enable")
    public ApiResponse<Void> totpEnable(@RequestBody @Valid TotpEnableRequest req) {
        authService.totpEnable(req);
        return ApiResponse.ok(null); // 成功即静默返回，
    }

    /**
     * 禁用 TOTP（已认证）：密码复验通过则 auth.set_mfa(secret=null, enabled=false)。
     */
    @PostMapping("/totp/disable")
    public ApiResponse<Void> totpDisable(@RequestBody @Valid TotpPasswordRequest req) {
        authService.totpDisable(req);
        return ApiResponse.ok(null);
    }

    /**
     * 生成 2FA 备用恢复码（已认证；CachePort 会话存储，一次性返回；生成事件经
     * um.login_log 留痕 fail_reason='backup_codes_generated'，ip/ua 随审计落库）。
     */
    @PostMapping("/totp/backup-codes")
    public ApiResponse<BackupCodesResponse> backupCodes(HttpServletRequest request) {
        return ApiResponse.ok(authService.backupCodes(
                RequestUtil.getClientIp(request), request.getHeader("User-Agent")));
    }

    /**
     * 密码找回（匿名）：登录名存在则发一次性重置票据。
     */
    @PostMapping("/recovery")
    public ApiResponse<RecoveryResponse> recovery(@RequestBody @Valid RecoveryRequest req) {
        return ApiResponse.ok(authService.recovery(req)); // 不存在时同样返回票据占位，防用户名探测，
    }

    /**
     * 密码重置（匿名）：票据 + 新密码校验通过则 auth.set_password。
     */
    @PostMapping("/recovery/reset")
    public ApiResponse<Void> recoveryReset(@RequestBody @Valid RecoveryResetRequest req,
                                           HttpServletRequest request) {
        authService.recoveryReset(req, RequestUtil.getClientIp(request),
                request.getHeader("User-Agent"));
        return ApiResponse.ok(null);
    }

    /**
     * 自助改密（已认证；change-password 不在网关匿名白名单，默认要求 JWT）：
     * 旧密码复验，随后弱密码预检，再 auth.set_password 写 BCrypt，再 update_user 清
     * must_change_pwd，随后 record_login 记 um.login_log（fail_reason='password_changed'）。
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid ChangePasswordRequest req,
                                            HttpServletRequest request) {
        authService.changePassword(req, RequestUtil.getClientIp(request),
                request.getHeader("User-Agent"));
        return ApiResponse.ok(null);
    }
}
