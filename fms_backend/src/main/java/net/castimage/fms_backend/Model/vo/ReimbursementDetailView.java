package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 报销单详情视图（头 + 明细行）：GET /api/exp/reimbursements/{id}；RLS 空集返回 null 则
 * data:null（不泄露存在性）；状态机终审在 DB 触发器，Java 仅预检。
 */
public record ReimbursementDetailView(UUID id, UUID companyId, UUID applicantId, String applicantName,
                                      UUID deptId, String deptName, String title, BigDecimal amount,
                                      String currency, String status, UUID wfInstanceId, UUID voucherId,
                                      UUID createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                                      List<ReimbursementLineView> lines) {
}
