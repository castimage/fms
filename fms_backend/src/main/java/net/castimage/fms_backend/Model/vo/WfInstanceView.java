package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 流程实例视图：join 流程定义（编码/名称）+ 发起人登录名；RLS SELECT 限定本人发起或
 * 终态（APPROVED/REJECTED）——他人 RUNNING 实例不可见（详情命中空集返回 data:null）。
 */
public record WfInstanceView(UUID id, UUID companyId, UUID definitionId, String definitionCode,
                             String definitionName, String businessType, UUID businessId,
                             UUID initiatorId, String initiatorName, String currentNode,
                             String status, LocalDateTime createdAt) {
}
