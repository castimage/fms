package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 附件元数据实体（docs.documents）：文件字节落本地卷（storage_path 相对路径），DB 仅存元数据
 * 与 sha256 完整性哈希；company_id 恒由业务对象子查询推导（RLS 收敛，客户端不可自报）；
 * business_type 白名单（REIMBURSEMENT_LINE/VOUCHER/LOGIN_BACKGROUND/USER_BACKGROUND）
 * Java 与 DB CHECK 双侧同构。
 */
@TableName(schema = "docs", value = "documents")
@Data
public class Document {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS 范围键，上传时由业务对象推导） */
    private UUID companyId;

    /** 业务类型（REIMBURSEMENT_LINE/VOUCHER/LOGIN_BACKGROUND/USER_BACKGROUND） */
    private String businessType;

    /** 业务单据主键（报销明细行 id / 凭证 id；背景图类型 = 上传人 id） */
    private UUID businessId;

    /** 原始文件名（展示用） */
    private String fileName;

    /** 文件大小（字节，>0 且 ≤8MB） */
    private Long fileSize;

    /** 客户端申报 MIME（仅记录） */
    private String contentType;

    /** 相对存储路径（{yyyyMM}/{uuid}.{ext}） */
    private String storagePath;

    /** 文件 SHA-256（64 位 hex） */
    private String sha256;

    /** 上传人 */
    private UUID uploaderId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
