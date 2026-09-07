package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 应收客户实体（ar.customers）：name 走 GIN trgm 索引（similarity/% 模糊检索），
 * uk_ar_customer_code(company_id, code) 唯一；status 由 DB CHECK 兜底
 * （ACTIVE/INACTIVE/BLOCKED）。软删统一 deleted_at=now()，无 DELETE 权限。
 */
@TableName(schema = "ar", value = "customers")
@Data
public class Customer {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司 */
    private UUID companyId;

    /** 客户编码（公司内唯一） */
    private String code;

    /** 客户名称（trgm 模糊检索列） */
    private String name;

    /** 税号 */
    private String taxNo;

    /** 信用额度（NUMERIC(18,2)） */
    private BigDecimal creditLimit;

    /** 状态：ACTIVE/INACTIVE/BLOCKED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
