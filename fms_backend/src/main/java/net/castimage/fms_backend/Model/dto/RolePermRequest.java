package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 角色-权限绑定请求（经 auth.grant_role_permission / revoke_role_permission）。
 */
public record RolePermRequest(
        /** 权限编码（action:resource:data_scope） */
        @NotBlank(message = "permCode 不能为空")
        String permCode) { // 全键匹配，
}
