package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 应付供应商实体（ap.suppliers）：uk_ap_supplier_code(company_id, code) 唯一；
 * status 由 DB CHECK 兜底（ACTIVE/INACTIVE/BLOCKED）；name 与 ar.customers 同构
 * 支持 trgm 模糊检索（08 种子依赖）。
 */
@TableName(schema = "ap", value = "suppliers")
@Data
public class Supplier {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 供应商编码（公司内唯一） */
    private String code;

    /** 供应商名称（trgm 模糊检索列） */
    private String name;

    /** 税号 */
    private String taxNo;

    /** 信用额度（NUMERIC(18,2)） */
    private BigDecimal creditLimit;

    /** 状态：ACTIVE/INACTIVE/BLOCKED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
