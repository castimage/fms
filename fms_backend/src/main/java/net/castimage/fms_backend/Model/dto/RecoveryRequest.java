package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 密码找回请求（匿名端点 POST /api/auth/recovery）。
 */
public record RecoveryRequest(
        /** 登录名 */
        @NotBlank(message = "登录名不能为空")
        String loginName,
        /** 邮箱（加密存储，一致性比对由 SQL 侧 SECURITY DEFINER 闸口承担，本批仅存在性收敛） */
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式错误")
        String email) { // 一致性比对在 SQL 闸口，
}
