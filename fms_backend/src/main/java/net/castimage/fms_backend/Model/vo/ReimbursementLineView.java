package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 报销单明细行视图（ReimbursementDetailView.lines 元素）：行号升序；行级公司范围经父报销单
 * RLS 子查询收敛，无需额外过滤。
 */
public record ReimbursementLineView(UUID id, UUID reimbursementId, Integer lineNo, String type,
                                    BigDecimal amount, LocalDate trxDate, String summary,
                                    String invoiceFileId) {
}
