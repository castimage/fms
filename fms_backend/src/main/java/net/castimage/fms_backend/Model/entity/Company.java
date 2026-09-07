package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 公司实体（org.companies）：创建/更新经 SECURITY DEFINER 闸口 org.fn_upsert_company（新建公司不在任何数据范围，RLS insert WITH CHECK 恒 false，须函数内写入）；软删经 auth.fn_soft_delete。
 */
@TableName(schema = "org", value = "companies")
@Data
public class Company {

    /** 主键（种子固定 id …010） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 自引用列（种子列存在性自适应；一般等于自身 id） */
    private UUID companyId;

    /** 公司编码（唯一，如 COM-001） */
    private String code;

    /** 公司名称 */
    private String name;

    /** 本位币（CHAR(3)，如 CNY） */
    private String currency;

    /** 会计年度起始日 */
    private LocalDate fiscalYearStart;

    /** 状态：ACTIVE/INACTIVE */
    private String status;

    private LocalDateTime createdAt; // 审计时间戳，

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt; // 软删时间戳，

}
