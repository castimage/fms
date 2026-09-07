package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 固定资产卡片视图：netValue 为生成列（原值-累计折旧，只读）。
 */
public record AssetView(UUID id, UUID companyId, String assetCode, String category,
                        BigDecimal originalValue, BigDecimal residualValue, Integer lifeMonths,
                        String depreciationMethod, BigDecimal accumulatedDepreciation,
                        BigDecimal netValue, LocalDate startDate, String status,
                        LocalDate disposalDate, LocalDateTime createdAt) {
}
