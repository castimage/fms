package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 银行账户视图：accountNo 仅详情接口经 auth.decrypt_bank_account 解密返回（列表恒 null，
 * 列表 SQL 不选密文列），accountNoMasked 为请求方创建时携带的展示掩码（后端不存明文）。
 */
public record BankAccountView(UUID id, UUID companyId, String bankName, String accountNo,
                              String accountNoMasked, String accountType, String currency,
                              Boolean isActive, String status, LocalDateTime createdAt,
                              LocalDateTime updatedAt) {
}
