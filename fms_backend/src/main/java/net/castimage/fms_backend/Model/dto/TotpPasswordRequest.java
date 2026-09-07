package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * TOTP 密码复验请求（禁用 2FA 时复验登录密码）。
 */
public record TotpPasswordRequest(
        /** 登录密码（BCrypt 复验） */
        @NotBlank(message = "密码不能为空")
        String password) { // 禁用 2FA 前复验，
}
