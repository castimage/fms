package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 对账候选匹配结果（selectMatchCandidates 行）：金额相等 + 日期差≤3 天 + 未核销
 * （ISSUED/PARTIAL）的应付发票，按 similarity(摘要, 发票号) 降序；Service 取 top1
 * 建议（&gt;0.6 自动建议，否则留人工匹配）。
 */
public record MatchCandidate(UUID invoiceId, String invoiceNo, LocalDate invoiceDate,
                             BigDecimal total, BigDecimal outstanding, BigDecimal similarity) {
}
