package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对账运行批次视图：autoMatched/manualMatched 配对计数、diffCount 为本次运行后
 * 仍未匹配的差异行数（流水 + 对账单行）——对账差异调整的量化口径。
 */
public record ReconcileRunView(UUID id, UUID companyId, String period, LocalDateTime runAt,
                               Integer autoMatched, Integer manualMatched, Integer diffCount,
                               LocalDateTime createdAt) {
}
