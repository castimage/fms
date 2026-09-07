package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 凭证驳回请求（POST /api/gl/vouchers/{id}/reject）：原因必填（btrim 非空，写入
 * voucher_status_history.remark 与审计 jsonb），状态机 SUBMITTED 转 REJECTED，驳回为审核侧
 * 决策（SoD 驳回人≠制单人），驳回后下一批次经 REJECTED 转 DRAFT 退回修改再提交。
 */
public record RejectRequest(
        /** 驳回原因（必填，最长 500 字符） */
        @NotBlank(message = "驳回原因必填")
        @Size(max = 500, message = "驳回原因最长 500 字符")
        String reason) {
}
