package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 附件元数据视图（docs.documents 对外投影，不暴露 storage_path 内部路径）：
 * 上传/详情/列表共用；RLS 已按公司范围收敛。
 */
public record DocumentView(
        /** 附件 id（uuidv7） */
        UUID id,
        /** 所属公司 */
        UUID companyId,
        /** 业务类型（REIMBURSEMENT_LINE/VOUCHER/LOGIN_BACKGROUND/USER_BACKGROUND） */
        String businessType,
        /** 业务单据主键 */
        UUID businessId,
        /** 原始文件名（展示用） */
        String fileName,
        /** 文件大小（字节） */
        Long fileSize,
        /** 客户端申报 MIME */
        String contentType,
        /** 文件 SHA-256（完整性校验） */
        String sha256,
        /** 上传人 id */
        UUID uploaderId,
        /** 上传时间 */
        LocalDateTime createdAt) {
}
