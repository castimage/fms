package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 部门视图（含 ltree 路径）。
 */
public record DepartmentView(UUID id, UUID companyId, String code, String name,
                             String path, UUID parentId, Integer level,
                             UUID managerId, LocalDateTime createdAt) {
}
