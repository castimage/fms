package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 报销单明细实体（expense.reimbursement_lines）：行金额 > 0（DB CHECK），
 * (reimbursement_id, line_no) 唯一（uk_reimb_line）；行级公司范围经父报销单子查询
 * （RLS can_access_org），company_id 由 Service 显式写父单公司保证 NOT NULL 与
 * WITH CHECK 双成立；头金额一致性在 ExpenseServiceImpl 强制。
 */
@TableName(schema = "expense", value = "reimbursement_lines")
@Data
public class ReimbursementLine {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（与父报销单一致） */
    private UUID companyId;

    /** 父报销单 id（uk_reimb_line 部分键） */
    private UUID reimbursementId;

    /** 行号（1..n 按序，父单内唯一） */
    private Integer lineNo;

    /** 明细类型（餐费/差旅/办公等自由文本） */
    private String type;

    /** 行金额（> 0，CHECK 兜底） */
    private BigDecimal amount;

    /** 发生日期 */
    private LocalDate trxDate;

    /** 摘要 */
    private String summary;

    /** 发票附件关联（本批仅存 id，不落文件） */
    private String invoiceFileId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
