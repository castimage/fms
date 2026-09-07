package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 供应商对账单实体（ap.reconciliations）：核对动作不改发票/付款状态；状态机
 * 经 DRAFT 确认 CONFIRMED、可作废 VOID，CONFIRMED 后禁改（Java 预检）；对账单行导入后逐行
 * 匹配，全部行处理完方可确认。
 */
@TableName(schema = "ap", value = "reconciliations")
@Data
public class Reconciliation {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 供应商 id（语义引用 ap.suppliers） */
    private UUID supplierId;

    /** 对账基准日（供应商对账单日期） */
    private LocalDate statementDate;

    /** 状态：DRAFT/CONFIRMED/VOID */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
