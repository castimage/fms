package net.castimage.fms_backend.Model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 凭证列表行视图（GET /api/gl/vouchers 分页；MyBatis 结果映射，组件名须与列别名 camelCase 一致）：RLS 生效验证点——companyId 仅作 UI 提示过滤，可见集合由 RLS（公司到部门再到本人）收敛；DRAFT 凭证仅制单人可见。
 */
public record VoucherListView(UUID id, UUID companyId, Integer voucherNo, String period,
                              LocalDate voucherDate, String status, String sourceType,
                              Boolean isPrivate, UUID deptId, UUID createdBy,
                              String createdByName, UUID approvedBy, LocalDateTime approvedAt,
                              UUID postedBy, LocalDateTime postedAt, BigDecimal totalDebit,
                              BigDecimal totalCredit, String note, LocalDateTime createdAt) { // 列表排序依据，
}
