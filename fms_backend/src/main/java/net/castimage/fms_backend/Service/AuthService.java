package net.castimage.fms_backend.Service;

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

/** 认证模块业务接口（登录/刷新/登出/2FA/找回/会话）：登录先 BCrypt 校验，再做锁定/禁用检查，随后 auth.jwt_sign 签发或两步票据；refresh 验签断言 type=refresh 后重签；登出令牌入内存黑名单 */
public interface AuthService {

    /**
     * 登录（匿名端点，网关白名单放行）。
     *
     * @param req       登录请求
     * @param ip        客户端 IP（XFF 首段或 remoteAddr）
     * @param userAgent 客户端 UA
     * @return 令牌对；2FA 启用时仅票据（mfaRequired=true）
     */
    LoginResponse login(LoginRequest req, String ip, String userAgent);

    // 2FA 启用时登录只出两步票据，不出令牌，

    /**
     * TOTP 二次验证（登录第 2 步，匿名端点）：票据 + 动态码校验通过则签发 JWT。
     *
     * @param req       验证请求（ticket + code）
     * @param ip        客户端 IP
     * @param userAgent 客户端 UA
     * @return 令牌对
     */
    LoginResponse verifyTotp(TotpVerifyRequest req, String ip, String userAgent);

    /**
     * 令牌刷新（匿名端点）：refresh 验签（auth.jwt_verify + type=refresh）通过后重签令牌对。
     *
     * @param req 刷新请求
     * @return 新令牌对
     */
    LoginResponse refresh(RefreshRequest req);

    /**
     * 登出：access/refresh 入内存黑名单（TTL 内失效）。
     *
     * @param req 登出请求（可空）
     */
    void logout(LogoutRequest req);

    // 会话身份取自签名校验采信的 RequestContext，

    /**
     * 当前会话信息（GET /api/auth/session）：身份取自 RequestContext（签名校验采信）。
     *
     * @return 会话视图
     */
    UserSessionView session();

    /**
     * TOTP setup（已认证）：生成密钥并返回 otpauth URI；不写库（enable 才落库）。
     *
     * @return setup 响应
     */
    TotpSetupResponse totpSetup();

    /**
     * 启用 TOTP（已认证）：密码复验 + 动态码校验通过则 auth.set_mfa 持久化。
     *
     * @param req 启用请求
     */
    void totpEnable(TotpEnableRequest req);

    /**
     * 禁用 TOTP（已认证）：密码复验通过则 auth.set_mfa(secret=null, enabled=false)。
     *
     * @param req 密码复验请求
     */
    void totpDisable(TotpPasswordRequest req);

    /**
     * 生成 2FA 备用恢复码（已认证；CachePort 会话存储，一次性返回，生成事件经
     * auth.record_login 记 um.login_log：success=true + fail_reason='backup_codes_generated'）。
     *
     * @param ip        客户端 IP（审计用）
     * @param userAgent 客户端 UA（审计用）
     * @return 备用码响应
     */
    BackupCodesResponse backupCodes(String ip, String userAgent);

    /**
     * 密码找回（匿名）：登录名 + 邮箱经 recovery_lookup 闸口校验（加密盲索引）通过后
     * 一次性重置票据（30min，CachePort）；命中且邮件启用时向请求体邮箱发重置邮件，
     * 未命中不发但响应一致（防用户名探测与邮箱轰炸）。
     *
     * @param req 找回请求
     * @return 票据响应
     */
    RecoveryResponse recovery(RecoveryRequest req);

    /**
     * 密码重置（匿名）：票据有效则 auth.set_password 写入 BCrypt 新哈希。
     *
     * @param req       重置请求
     * @param ip        客户端 IP
     * @param userAgent 客户端 UA
     */
    void recoveryReset(RecoveryResetRequest req, String ip, String userAgent);

    /**
     * 自助改密（已认证）：旧密码复验 + 弱密码预检通过则 auth.set_password 写 BCrypt，再
     * auth.update_user 清 must_change_pwd，随后 auth.record_login 记 um.login_log
     * （success=true + fail_reason='password_changed'）；请求携带的令牌原值入
     * 内存黑名单（当前会话立即失效，他端会话 TTL 内自然失效）。
     *
     * @param req       改密请求（oldPassword/newPassword + 可选令牌）
     * @param ip        客户端 IP
     * @param userAgent 客户端 UA
     */
    void changePassword(ChangePasswordRequest req, String ip, String userAgent);
}
