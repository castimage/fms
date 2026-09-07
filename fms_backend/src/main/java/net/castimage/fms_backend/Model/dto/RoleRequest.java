package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 角色创建/更新请求（经 auth.upsert_role，按 code 幂等 upsert）。
 */
public record RoleRequest(
        /** 角色编码（唯一，小写字母/数字/下划线） */
        @NotBlank(message = "角色编码不能为空")
        @Pattern(regexp = "[a-z0-9_]+", message = "角色编码仅允许小写字母/数字/下划线")
        @Size(max = 32, message = "角色编码最长 32 字符")
        String code, // 唯一，小写约束，
        /** 角色名称 */
        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称最长 64 字符")
        String name) {
}
