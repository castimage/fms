package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 任务事件视图：join 操作人登录名；仅追加流水（CLAIM/APPROVE/REJECT/TRANSFER/REMIND/TIMEOUT）
 * 按发生时间升序；RLS can_access_org 公司范围收敛。
 */
public record WfTaskEventView(UUID id, UUID companyId, UUID taskId, String action,
                              UUID operatorId, String operatorName, String fromNode,
                              String toNode, String remark, LocalDateTime occurredAt) {
}
