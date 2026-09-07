package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 银行账户实体（cash.bank_accounts）：敏感列 account_no_enc（pgp_sym_encrypt 密文）/account_no_hash（SHA256 盲索引）
 * 列级不授任何角色，实体恒不映射、SQL 显式不选、日志不打印——解密唯一出口 PG 函数 auth.decrypt_bank_account；
 * 新建经 SECURITY DEFINER 闸口 cash.fn_create_bank_account（密文+盲索引函数内原子落库），账号变更视为新建（禁改密文列）。
 */
@TableName(schema = "cash", value = "bank_accounts")
@Data
public class BankAccount {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属公司（RLS can_access_org 行级收敛） */
    private UUID companyId;

    /** 开户行名称 */
    private String bankName;

    /** 账户类型：CORPORATE/PERSONAL */
    private String accountType;

    /** 币种（CHAR(3)，缺省 CNY） */
    private String currency;

    /** 是否启用（业务开关，与 status 独立） */
    private Boolean isActive;

    /** 状态：ACTIVE/FROZEN/CLOSED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
