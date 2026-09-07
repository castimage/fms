package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 坏账计提实体（ar.bad_debt_provision）：按 (invoice_id, period) 查重防重复计提
 * （selectByInvoicePeriod）；状态机经 DRAFT 确认 CONFIRMED、可冲销 REVERSED，确认后禁改禁删
 * （Java 预检）；发票 PAID 后禁止计提（预检）。
 */
@TableName(schema = "ar", value = "bad_debt_provision")
@Data
public class BadDebtProvision {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 发票 id（语义引用 ar.invoices） */
    private UUID invoiceId;

    /** 计提期间（CHAR(7)，YYYY-MM） */
    private String period;

    /** 计提金额（CHECK amount &gt;= 0） */
    private BigDecimal amount;

    /** 状态：DRAFT/CONFIRMED/REVERSED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
