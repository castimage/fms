package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 自助改密请求（已认证端点 POST /api/auth/change-password）：旧密码复验 + 新密码弱密码
 * 预检后经 auth.set_password 写库（pass_hash 列级不可直写）；accessToken/refreshToken
 * 可空携带，改密成功后由服务端入内存黑名单（当前会话立即失效，强制重新登录）。
 */
public record ChangePasswordRequest(
        /** 旧密码（BCrypt 复验，语义与 auth.verify_login 一致） */
        @NotBlank(message = "旧密码不能为空")
        String oldPassword,
        /** 新密码（BCrypt 哈希后经 auth.set_password 写入） */
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度须在 8-64 位之间")
        String newPassword,
        /** 当前 access token（可空：改密成功后入黑名单失效，TTL=fms.jwt.access-ttl） */
        String accessToken,
        /** 当前 refresh token（可空：改密成功后入黑名单失效，TTL=fms.jwt.refresh-ttl） */
        String refreshToken) { // 会话失效策略：可选携带，防登出令牌续期，
}
