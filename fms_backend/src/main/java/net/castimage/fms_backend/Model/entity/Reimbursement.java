package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 费用报销单实体（expense.reimbursements）：状态机经 DRAFT 提交 SUBMITTED、审核 APPROVED、付款 PAID、过账 POSTED，
 * REJECTED 仅自 SUBMITTED 进入（退回可再提交 DRAFT）、POSTED/REVERSED 禁改——由
 * expense.fn_reimburse_status_check 触发器终审（RAISE P0001 映射 40900），Java 只做预检快速失败；
 * 头 amount = SUM(lines.amount)（Service 内强制一致，防头行不一致）；wf_instance_id 由 submit 启动
 * 审批实例并回填；voucher_id 由 PAID 状态的显式动作生成凭证时回填，同一步推进 POSTED。
 */
@TableName(schema = "expense", value = "reimbursements")
@Data
public class Reimbursement {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 申请人（INSERT 策略 is_self(applicant_id) 强制 = 当前用户） */
    private UUID applicantId;

    /** 报销部门（RLS can_access_dept 行级收敛） */
    private UUID deptId;

    /** 报销标题（keyword 检索列） */
    private String title;

    /** 报销总额（= 明细合计，NUMERIC(18,2) >= 0） */
    private BigDecimal amount;

    /** 币种（CHAR(3)，缺省 CNY） */
    private String currency;

    /** 状态：DRAFT/SUBMITTED/APPROVED/REJECTED/PAID/POSTED/REVERSED（触发器状态机终审） */
    private String status;

    /** 审批流实例 id（submit 已挂接：启动实例并回填） */
    private UUID wfInstanceId;

    /** 过账凭证 id（PAID 状态经生成凭证动作回填，非空即视为已生成，重复触发返回既有凭证） */
    private UUID voucherId;

    /** 创建人（RLS is_self(created_by) 同申请人） */
    private UUID createdBy;

    /** 幂等键（规范化请求内容 SHA-256，uk_reimb_idem 部分唯一索引防同 body 重复提交） */
    private String idemKey;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
