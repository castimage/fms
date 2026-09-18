package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.BankAccount;
import net.castimage.fms_backend.Model.vo.BankAccountView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/**
 * 银行账户 Mapper（cash.bank_accounts）：新建经 SECURITY DEFINER 闸口 cash.fn_create_bank_account
 * （pgp_sym_encrypt 密文 + sha256 盲索引函数内原子落库）；列表 SQL 恒不选 account_no_enc/
 * account_no_hash（列级不授权 + 不泄露明文）；解密唯一出口 auth.decrypt_bank_account（B 类）。
 */
public interface BankAccountMapper extends BaseMapper<BankAccount> {

    /**
     * 新建银行账户（SECURITY DEFINER 闸口）：密文/盲索引在函数内原子落库，uk_bank_account_hash 冲突 23505 映射 40900；
     * company_id 由函数从当前用户上下文推导（无法确定公司则 RAISE 42501 映射 40300）。
     *
     * @param bankName    开户行名称
     * @param accountNo   银行账号明文（仅经此入口进入密文列，Java 侧不落库不日志）
     * @param accountType 账户类型（CORPORATE/PERSONAL）
     * @param currency    币种（3 位 ISO）
     * @param status      状态（ACTIVE/FROZEN/CLOSED）
     * @param isActive    是否启用
     * @return 新建账户 id
     */
    @Select("""
            SELECT cash.fn_create_bank_account(#{bankName}, #{accountNo}, #{accountType}, #{currency}, #{status}, #{isActive})
            """)
    UUID createBankAccount(@Param("bankName") String bankName, @Param("accountNo") String accountNo,
                           @Param("accountType") String accountType, @Param("currency") String currency,
                           @Param("status") String status, @Param("isActive") Boolean isActive);

    /**
     * 银行账号解密（B 类）：PG SECURITY DEFINER 函数 auth.decrypt_bank_account
     * （pgp_sym_decrypt aes256，current_user 守卫仅 fms_admin/fms_operator，函数内校验
     * 数据范围）——解密出口唯一化，仅详情接口调用，返回值不入日志。
     *
     * @param id 银行账户 id
     * @return 解密后的账号明文（账户不存在 RAISE 则 40400 语义由 SQL 异常映射兜底）
     */
    @Select("SELECT auth.decrypt_bank_account(#{id}::uuid)")
    String decryptAccount(@Param("id") UUID id);

    /**
     * 银行账户分页列表：不选密文/盲索引列（显式列名白名单），accountNo 恒 null、
     * accountNoMasked 由请求方创建时携带；keyword 命中开户行名称（RLS can_access_org 收敛可见公司）。
     */
    @Select("""
            SELECT b.id, b.company_id, b.bank_name, NULL AS account_no,
                   NULL AS account_no_masked,
                   b.account_type, b.currency, b.is_active, b.status,
                   b.created_at, b.updated_at
              FROM cash.bank_accounts b
             WHERE b.deleted_at IS NULL
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR b.bank_name ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%')
             ORDER BY b.created_at DESC
            """)
    IPage<BankAccountView> selectBankAccountPage(Page<BankAccountView> page,
                                                 @Param("keyword") String keyword);
}
