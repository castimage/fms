package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 折旧计提批次视图：lines 为 JSONB 解析后的明细列表（[{asset_id,asset_code,category,amount,method}]）；
 * voucherId 为双路径（cron fa.fn_run_depreciation / 手动 FaServiceImpl）生成的折旧凭证草稿
 * id（null = 未生成/生成失败容错跳过），前端可跳转凭证详情。
 */
public record DepreciationRunView(UUID id, UUID companyId, String period, UUID runBy,
                                  String status, UUID voucherId, List<DepreciationLine> lines,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
}
