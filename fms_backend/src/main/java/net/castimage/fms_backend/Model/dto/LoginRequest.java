package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（匿名端点 POST /api/auth/login）。
 */
public record LoginRequest(
        /** 登录名 */
        @NotBlank(message = "登录名不能为空")
        String loginName, // 全局唯一，
        /** 密码（明文，仅传输用；服务端 BCrypt 校验） */
        @NotBlank(message = "密码不能为空")
        String password) {
}
