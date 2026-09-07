package net.castimage.fms_backend.Model.vo;

import java.util.UUID;

/**
 * 权限视图（perm-tree 挂载节点）。
 */
public record PermissionView(UUID id, String code, String name, String identifier, String action) {
}
