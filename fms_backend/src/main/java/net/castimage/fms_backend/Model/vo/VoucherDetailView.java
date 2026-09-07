package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 凭证详情视图（凭证头 + 分录）：他人 DRAFT / 越权公司则 RLS 空集，Controller 返回 data:null（不泄露存在性）。
 */
public record VoucherDetailView(UUID id, UUID companyId, Integer voucherNo, String period,
                                LocalDate voucherDate, String status, String sourceType,
                                Boolean isPrivate, UUID deptId, UUID createdBy,
                                String createdByName, UUID approvedBy, LocalDateTime approvedAt,
                                UUID postedBy, LocalDateTime postedAt, BigDecimal totalDebit,
                                BigDecimal totalCredit, String note, UUID reversalOfVoucherId,
                                UUID reversedByVoucherId, List<VoucherLineView> lines) { // 分录随详情联查，
}
