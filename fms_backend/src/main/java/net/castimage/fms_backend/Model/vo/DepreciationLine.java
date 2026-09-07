package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/** 折旧批次明细行（depreciation_runs.lines JSONB 元素，fastjson2 解析） */
public record DepreciationLine(UUID assetId, String assetCode, String category,
                               BigDecimal amount, String method) {
}
