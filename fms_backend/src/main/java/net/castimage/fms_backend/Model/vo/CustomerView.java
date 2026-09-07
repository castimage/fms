package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 客户视图（MyBatis 结果映射，组件名与列别名 camelCase 一致）：列表/详情共用；
 * 详情越权则 RLS 空集，回 data:null。
 */
public record CustomerView(UUID id, UUID companyId, String code, String name,
                           String taxNo, BigDecimal creditLimit, String status,
                           LocalDateTime createdAt) {
}
