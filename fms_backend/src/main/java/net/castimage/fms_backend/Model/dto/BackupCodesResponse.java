package net.castimage.fms_backend.Model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 备用码响应：一次性恢复码（DB 盲索引落盘 um.backup_codes，有效期 fms.mfa.backup-code-valid-days；
 * 明文仅本次返回；生成/核销已审计留痕：生成经 auth.record_login 记 um.login_log
 * fail_reason='backup_codes_generated'，核销记 'backup_code_used'）。
 */
public record BackupCodesResponse(
        List<String> codes, // 仅本次返回，
        /** 服务端保留有效期 */
        LocalDateTime expiresAt) {
}
