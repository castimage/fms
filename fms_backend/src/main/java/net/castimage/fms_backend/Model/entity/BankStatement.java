package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 银行对账单实体（cash.bank_statements）：导入两阶段——先内存解析预览（不落库），确认后才落库；
 * status 三态 IMPORTED/RECONCILED/VOID，total_lines 在落库时回填。
 */
@TableName(schema = "cash", value = "bank_statements")
@Data
public class BankStatement {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 银行账户 id */
    private UUID bankAccountId;

    /** 原始文件名（导入时记录） */
    private String filename;

    /** 导入幂等键（filename+全部行内容 SHA-256，uk_bank_stmt_idem 部分唯一索引防重复导入） */
    private String idemKey;

    /** 导入日期（缺省当日） */
    private LocalDate importDate;

    /** 状态：IMPORTED/RECONCILED/VOID */
    private String status;

    /** 行数（落库时回填） */
    private Integer totalLines;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
