package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 密码重置请求（经 auth.set_password，重置后建议 must_change_pwd=true）。
 */
public record ResetPasswordRequest(
        /** 新密码（BCrypt 后写入） */
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度须在 8-64 位之间")
        String newPassword) {
}
