package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 凭证分录实体（gl.voucher_lines）：单边分录（借/贷互斥）、金额非负；科目必须末级且 ACTIVE（trg_voucher_line_leaf）；父凭证 POSTED/REVERSED 后禁改禁删；非 DRAFT 凭证强制借贷平衡。
 * cost_amount 为列级敏感列，查询列排除（@TableField(select=false)），插入留空走 DB DEFAULT 0。
 */
@TableName(schema = "gl", value = "voucher_lines")
@Data
public class VoucherLine {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属凭证 */
    private UUID voucherId;

    /** 行号（凭证内唯一） */
    private Integer lineNo;

    /** 末级科目 id */
    private UUID accountId;

    /** 摘要 */
    private String summary;

    /** 借方金额（>= 0，与 credit 互斥） */
    private BigDecimal debit;

    /** 贷方金额（>= 0，与 debit 互斥） */
    private BigDecimal credit;

    /** 币种（CHAR(3)） */
    private String currency;

    /** 部门 id */
    private UUID deptId;

    /** 往来单位 id（ar/ap 批次使用） */
    private UUID partnerId;

    /** 所属公司（与凭证头一致，RLS 收敛） */
    private UUID companyId;

    /** 成本/费用金额（列级敏感列，查询列排除；非敏感列 GRANT 不含本列） */
    @TableField(select = false)
    private BigDecimal costAmount;

    /** 创建时间 */
    private LocalDateTime createdAt; // 审计时间戳，

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt; // 软删时间戳，

}
