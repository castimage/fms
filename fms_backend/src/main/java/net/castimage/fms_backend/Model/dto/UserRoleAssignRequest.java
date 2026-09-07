package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 用户角色分配请求（经 auth.assign_role，按角色 code 分配）。
 */
public record UserRoleAssignRequest(
        /** 角色 id（服务端取 code 后经闸口分配） */
        @NotNull(message = "roleId 不能为空")
        UUID roleId) { // 按角色 code 分配，
}
