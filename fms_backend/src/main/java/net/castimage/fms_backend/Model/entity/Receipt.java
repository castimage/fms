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
 * 收款单实体（ar.receipts）：状态机 RECEIVED 先核销完毕转 ALLOCATED，再 REVERSED（冲销）；
 * 无 DB 触发器状态机（与发票不同），状态推进由 Service 预检完成；核销入口以
 * FOR UPDATE 行锁防并发超分；voucher_id 为凭证联动预留字段（后续批）。
 */
@TableName(schema = "ar", value = "receipts")
@Data
public class Receipt {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 客户 id（语义引用 ar.customers） */
    private UUID customerId;

    /** 收款金额（CHECK amount > 0） */
    private BigDecimal amount;

    /** 收款银行账户（语义引用 cash.bank_accounts，可空） */
    private UUID bankAccountId;

    /** 收款日期 */
    private LocalDate receiptDate;

    /** 状态：RECEIVED/ALLOCATED/REVERSED */
    private String status;

    /** 联动凭证 id（收款登记经 VoucherLinkService 尽力生成 DRAFT 凭证并回填） */
    private UUID voucherId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
