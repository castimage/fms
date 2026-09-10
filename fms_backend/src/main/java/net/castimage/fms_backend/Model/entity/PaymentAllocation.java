package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 付款核销明细实体（ap.payment_allocations）：uk_ap_alloc(payment_id, invoice_id)
 * 唯一约束防同对重复核销（23505 映射 40900）；version 乐观锁——金额调整/撤销核销须
 * 带 WHERE version=? 条件（B 类 updateWithVersion），0 行则 40900；
 * 与供应商对账解耦：对账仅回填 matched_invoice_id，核销走本表。
 */
@TableName(schema = "ap", value = "payment_allocations")
@Data
public class PaymentAllocation {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 付款单 id（语义引用 ap.payments） */
    private UUID paymentId;

    /** 发票 id（语义引用 ap.invoices） */
    private UUID invoiceId;

    /** 本次核销金额（CHECK amount > 0） */
    private BigDecimal amount;

    /** 乐观锁版本（写入 0，更新/撤销须带版本条件） */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间（撤销核销 = 软删） */
    private LocalDateTime deletedAt;

}
