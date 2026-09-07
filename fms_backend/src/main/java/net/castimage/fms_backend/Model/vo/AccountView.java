package net.castimage.fms_backend.Model.vo;

import java.util.UUID;

/**
 * 科目视图（GET /api/gl/accounts 树形列表按 path ltree 自然序返回，前端依 path/parentId 建树）。
 */
public record AccountView(UUID id, UUID companyId, String code, String name, String path,
                          UUID parentId, Integer level, Boolean isLeaf, String accountType,
                          String direction, Boolean isForeignCurrency, String currency,
                          String status) {
}
