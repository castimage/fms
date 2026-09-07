package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 流程定义视图：node_json 节点编排 JSONB（String 透传展示）；status DRAFT/ACTIVE/DISABLED；
 * RLS can_access_org 公司范围收敛。
 */
public record WfDefinitionView(UUID id, UUID companyId, String code, String name, Integer version,
                               String formType, String nodeJson, String status,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
}
