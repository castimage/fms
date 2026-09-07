package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 对账单行预览/确认行（import 与 confirm 共用的行口径）：
 * 列 trx_date, amount, summary[, direction]（UTF-8 CSV/TSV 内存解析）。
 */
public record StatementLinePreview(LocalDate trxDate, BigDecimal amount, String summary,
                                   String direction) {
}
