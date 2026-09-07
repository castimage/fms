package net.castimage.fms_backend.Model.vo;

import java.util.UUID;

/**
 * 角色视图（含权限绑定数）。
 */
public record RoleView(UUID id, UUID companyId, String code, String name,
                       Boolean isSystem, Long permissionCount) {
}
