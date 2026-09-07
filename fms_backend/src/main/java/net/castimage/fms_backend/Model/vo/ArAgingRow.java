package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 应收账龄行（GET /api/rpt/ar-aging；MyBatis 结果映射）：数据源 rpt.ar_aging 视图——ISSUED/PARTIAL
 * 发票按（客户×发票月）计算未核销余额（open_balance = total - ar_aging_paid IMMV 累计已核销额）；
 * LEFT JOIN ar.customers 联查客户编码/名称（Wrapper 无 JOIN 能力故手写 B 类）；账龄分桶（0-30/31-60/…）
 * 由 Java 侧按 due_date 相对查询日计算。
 */
public record ArAgingRow(UUID customerId, String customerCode, String customerName,
                         String period, UUID invoiceId, String invoiceNo,
                         LocalDate invoiceDate, LocalDate dueDate, String currency,
                         BigDecimal total, BigDecimal paidAmount, BigDecimal openBalance) { // 分桶在 Service 侧，
}
