package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 账龄分桶行（GET /api/ar/aging）：按客户 × 账龄桶（0-30/31-60/61-90/91-180/180+，
 * 以发票日期到当日天数分桶）聚合未核销余额；数据源 rpt.ar_aging 视图
 * （ISSUED/PARTIAL 且未核销，security_invoker=on 行级收敛）。
 */
public record AgingRow(UUID customerId, String customerName, String bucket,
                       BigDecimal openBalance) {
}
