package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 启用 TOTP 请求：密码复验 + setup 后验证动态码。
 */
public record TotpEnableRequest(
        /** 登录密码（BCrypt 复验） */
        @NotBlank(message = "密码不能为空")
        String password, // BCrypt 复验，
        /** TOTP 动态码（验证与用户口令一致性后落库） */
        @NotBlank(message = "验证码不能为空")
        String code) {
}
