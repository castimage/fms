package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 自动对账候选行（B 类 SQL 返回）：金额相等 + 日期差≤3 天 + 摘要 pg_trgm similarity，
 * 按相似度降序取最优；sim ≥ 0.9 才自动配对（<0.9 留待人工）。
 */
public record AutoMatchCandidate(UUID lineId, UUID statementId, BigDecimal sim) {
}
