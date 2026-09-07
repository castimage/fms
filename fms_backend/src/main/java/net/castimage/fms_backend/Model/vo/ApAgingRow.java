package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 应付账龄分桶行（GET /api/ap/aging）：按供应商 × 账龄桶（0-30/31-60/61-90/91-180/180+，
 * 以发票日期到当日天数分桶）聚合未核销余额；数据源 rpt.ap_aging 视图
 * （ISSUED/PARTIAL 且未核销 = total - rpt.ap_aging_paid IMMV 已核销，security_invoker=on 行级收敛）。
 */
public record ApAgingRow(UUID supplierId, String supplierName, String bucket,
                         BigDecimal openBalance) {
}
