package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 密码重置请求（匿名端点 POST /api/auth/recovery/reset）。
 */
public record RecoveryResetRequest(
        /** 一次性重置票据 */
        @NotBlank(message = "ticket 不能为空")
        String ticket,
        /** 新密码（BCrypt 哈希后经 auth.set_password 写入） */
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度须在 8-64 位之间")
        String newPassword) { // BCrypt 后写库，
}
