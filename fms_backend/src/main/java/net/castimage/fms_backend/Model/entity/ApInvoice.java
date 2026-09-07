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
 * 应付发票实体（ap.invoices）：状态机与 ar 同构
 * （经 DRAFT 开票 ISSUED、部分核销 PARTIAL、付清 PAID、可作废 VOID），ap.fn_ap_invoice_immutable 触发器
 * 终审（ISSUED/PARTIAL 仅可作废 VOID，PAID/VOID 禁改禁删）；uk_ap_invoice_no 唯一；
 * version 乐观锁预留；voucher_id 凭证联动预留字段（后续批）。
 */
@TableName(schema = "ap", value = "invoices")
@Data
public class ApInvoice {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 供应商 id（语义引用 ap.suppliers） */
    private UUID supplierId;

    /** 发票号（公司内唯一） */
    private String invoiceNo;

    /** 开票日期 */
    private LocalDate invoiceDate;

    /** 到期日（应付到期日聚合/资金预测口径） */
    private LocalDate dueDate;

    /** 币种（CHAR(3)） */
    private String currency;

    /** 含税总额（NUMERIC(18,2)，付款核销余额基准） */
    private BigDecimal total;

    /** 税额 */
    private BigDecimal tax;

    /** 状态：DRAFT/ISSUED/PARTIAL/PAID/VOID */
    private String status;

    /** 联动凭证 id（发票/付款经 VoucherLinkService 尽力生成 DRAFT 凭证并回填） */
    private UUID voucherId;

    /** 乐观锁版本 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
