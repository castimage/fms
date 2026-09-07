package net.castimage.fms_backend.Model.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 银行对账单视图（分页列表，join 银行账户名）：total_lines 落库时回填。
 */
public record BankStatementView(UUID id, UUID companyId, UUID bankAccountId, String bankName,
                                String filename, LocalDate importDate, String status,
                                Integer totalLines, LocalDateTime createdAt) {
}
