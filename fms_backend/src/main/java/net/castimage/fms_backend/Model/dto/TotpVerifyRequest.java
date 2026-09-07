package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * TOTP 二次验证请求（匿名端点 POST /api/auth/totp/verify，登录第 2 步）。
 */
public record TotpVerifyRequest(
        /** 登录第 1 步返回的票据 */
        @NotBlank(message = "ticket 不能为空")
        String ticket,
        /** TOTP 动态码（6 位） */
        @NotBlank(message = "验证码不能为空")
        String code) { // 6 位动态码，
}
