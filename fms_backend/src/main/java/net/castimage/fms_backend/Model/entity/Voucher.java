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
 * 凭证头实体（gl.vouchers）：状态机经 DRAFT 提交 SUBMITTED、审核 APPROVED、过账 POSTED，冲销(reverse) 到 REVERSED；POSTED/REVERSED 不可变（trg_voucher_immutable 触发器 + RLS update 策略双重兜底）；voucher_no 过账时由 gl.fn_get_voucher_no 分配（期间内唯一连续）。
 */
@TableName(schema = "gl", value = "vouchers")
@Data
public class Voucher {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 会计期间（CHAR(7)，YYYY-MM） */
    private String period;

    /** 凭证号（POSTED 时由 DB 触发器分配；DRAFT/SUBMITTED/APPROVED 为 null） */
    private Integer voucherNo;

    /** 凭证日期 */
    private LocalDate voucherDate;

    /** 状态：DRAFT/SUBMITTED/APPROVED/POSTED/REVERSED */
    private String status;

    /** 来源类型（默认 MANUAL） */
    private String sourceType;

    /** 来源业务单据 id（AR/AP/现金联动凭证时回填；expense 报销联动尚未接入） */
    private UUID sourceId;

    /** 私密凭证（本人部门可见性豁免） */
    private Boolean isPrivate;

    /** 部门 id（RLS 行级收敛） */
    private UUID deptId;

    /** 制单人（RLS is_self(created_by)） */
    private UUID createdBy;

    /** 审批人（须 ≠ created_by，SoD） */
    private UUID approvedBy;

    /** 审批时间 */
    private LocalDateTime approvedAt;

    /** 过账人（触发器取号时写入） */
    private UUID postedBy;

    /** 过账时间 */
    private LocalDateTime postedAt;

    /** 借方合计（D.1 行级触发器实时同步） */
    private BigDecimal totalDebit;

    /** 贷方合计（D.1 行级触发器实时同步） */
    private BigDecimal totalCredit;

    /** 摘要/备注（keyword 检索列） */
    private String note;

    /** 创建幂等键（同人+公司+期间+内容哈希；同 body 重复提交仅 1 张，uk_voucher_idem 部分唯一索引兜底） */
    private String idemKey;

    /** 冲销来源凭证 id（本凭证为红字冲销时指向原凭证） */
    private UUID reversalOfVoucherId;

    /** 冲销凭证 id（本凭证被冲销时指向反向凭证） */
    private UUID reversedByVoucherId;

    /** 创建时间 */
    private LocalDateTime createdAt; // 审计时间戳，

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt; // 软删时间戳，

}
