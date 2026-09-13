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
 * 付款单实体（ap.payments）：状态机 PAID（登记即付款）先核销完毕转 ALLOCATED，再
 * REVERSED（冲销）；无 DB 触发器状态机，状态推进由 Service 预检；核销入口以
 * FOR UPDATE 行锁防并发超分；voucher_id 凭证联动预留字段（后续批）。
 */
@TableName(schema = "ap", value = "payments")
@Data
public class Payment {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 供应商 id（语义引用 ap.suppliers） */
    private UUID supplierId;

    /** 付款金额（CHECK amount > 0） */
    private BigDecimal amount;

    /** 付款银行账户（语义引用 cash.bank_accounts，可空） */
    private UUID bankAccountId;

    /** 付款日期 */
    private LocalDate paymentDate;

    /** 状态：PAID/ALLOCATED/REVERSED */
    private String status;

    /** 联动凭证 id（付款经 VoucherLinkService 尽力生成 DRAFT 凭证并回填） */
    private UUID voucherId;

    /** 幂等键（uk_ap_payments_dup）：由 公司/供应商/银行账户/金额/付款日期 规范化派生，
     * 同一有效付款单防重复登记，DB 部分唯一索引 + Service 预检双闸（23505 映射 40900） */
    private UUID dupKey;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
