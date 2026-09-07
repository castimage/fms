package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 收款核销明细实体（ar.receipt_allocations）：uk_ar_alloc(receipt_id, invoice_id)
 * 唯一约束防同对重复核销（23505 映射 40900）；version 乐观锁——金额调整/撤销核销
 * 必须带 WHERE version=? 条件（B 类 updateWithVersion），命中 0 行则 40900；
 * 撤销核销 = 软删（deleted_at=now()），发票状态由 Service 回算。
 */
@TableName(schema = "ar", value = "receipt_allocations")
@Data
public class ReceiptAllocation {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 收款单 id（语义引用 ar.receipts） */
    private UUID receiptId;

    /** 发票 id（语义引用 ar.invoices） */
    private UUID invoiceId;

    /** 本次核销金额（CHECK amount &gt; 0） */
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
