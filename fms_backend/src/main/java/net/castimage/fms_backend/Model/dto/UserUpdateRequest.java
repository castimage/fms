package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户更新请求（经 auth.update_user 补丁，白名单键 status/must_change_pwd/email/display_name）。
 */
public record UserUpdateRequest(
        /** 邮箱（加密存储） */
        String email,
        /** 状态：ACTIVE/LOCKED/DISABLED */
        @Pattern(regexp = "ACTIVE|LOCKED|DISABLED", message = "状态仅支持 ACTIVE/LOCKED/DISABLED")
        String status,
        /** 下次登录须改密 */
        Boolean mustChangePwd,
        /** 显示名（白名单键，管理员/本人可更新） */
        @Size(max = 64, message = "显示名长度不能超过 64")
        String displayName) { // 白名单键之一，
}
