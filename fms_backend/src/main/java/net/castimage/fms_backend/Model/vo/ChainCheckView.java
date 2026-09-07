package net.castimage.fms_backend.Model.vo;

import java.util.UUID;

/**
 * 哈希链校验结果（GET /api/audit/chain-check；MyBatis 结果映射）：调 PG 函数
 * audit.audit_log_chain_check()（STABLE + SECURITY DEFINER，fms_auditor 可执行）——
 * 自链首按 fn_audit_log_hash 同式逐行重算比对，prevOk=false 时 brokenAt 为首断链点记录 id，
 * detail 为断链详情 JSONB（期望/存储 row_hash、prev_hash；String 透传）。
 */
public record ChainCheckView(boolean prevOk, UUID brokenAt, String detail) { // NULL brokenAt = 链完好，
}
