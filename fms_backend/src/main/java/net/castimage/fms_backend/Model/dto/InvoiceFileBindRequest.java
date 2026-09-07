package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 报销明细发票附件绑定请求（invoice_file_id 写入链路）：fileId 须为已上传至该明细行
 * 名下的附件（business_type=REIMBURSEMENT_LINE、business_id=行 id），归属不符 40001。
 */
public record InvoiceFileBindRequest(
        /** 附件 id（docs.documents 主键） */
        @NotNull(message = "附件 id 必填")
        UUID fileId) {
}
