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
 * 应收发票实体（ar.invoices）：状态机经 DRAFT 开票 ISSUED、部分核销 PARTIAL、收清 PAID（可作废 VOID），
 * 由 ar.fn_ar_invoice_immutable 触发器终审（ISSUED/PARTIAL 仅可作废 VOID，
 * PAID/VOID 禁改禁删）；uk_ar_invoice_no(company_id, invoice_no) 唯一；version 为
 * 乐观锁列（预留，核销流程主要锁 receipt_allocations.version）；voucher_id 为
 * 凭证联动预留字段（发票到凭证挂接列入后续批）。
 */
@TableName(schema = "ar", value = "invoices")
@Data
public class ArInvoice {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 客户 id（语义引用 ar.customers） */
    private UUID customerId;

    /** 发票号（公司内唯一） */
    private String invoiceNo;

    /** 开票日期 */
    private LocalDate invoiceDate;

    /** 到期日（账龄/资金预测口径） */
    private LocalDate dueDate;

    /** 币种（CHAR(3)） */
    private String currency;

    /** 含税总额（NUMERIC(18,2)，核销余额基准） */
    private BigDecimal total;

    /** 税额 */
    private BigDecimal tax;

    /** 状态：DRAFT/ISSUED/PARTIAL/PAID/VOID */
    private String status;

    /** 联动凭证 id（开票/核销经 VoucherLinkService 尽力生成 DRAFT 凭证并回填） */
    private UUID voucherId;

    /** 乐观锁版本 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
