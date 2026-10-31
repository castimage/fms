package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.Receipt;
import net.castimage.fms_backend.Model.vo.ReceiptView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 收款单 Mapper（ar.receipts）：核销入口须 FOR UPDATE 行锁（防并发超分）；分页 join 客户/银行账户 */
public interface ReceiptMapper extends BaseMapper<Receipt> {

    /**
     * 银行账户可用性校验，账户须存在、同公司、启用且 ACTIVE；RLS 已按公司收敛，
     * 他公司/停用/软删返回 0。
     */
    @Select("""
            SELECT count(*)
              FROM cash.bank_accounts
             WHERE id = #{bankAccountId}::uuid
               AND company_id = #{companyId}::uuid
               AND is_active
               AND status = 'ACTIVE'
               AND deleted_at IS NULL
            """)
    int countActiveBankAccount(@Param("companyId") UUID companyId,
                               @Param("bankAccountId") UUID bankAccountId);

    /**
     * 核销入口锁读：SELECT ... FOR UPDATE 锁定收款单行（写事务内使用，@Transactional
     * 强制），并发核销同一收款单时串行化——status 必须 RECEIVED 的校验在锁内完成。
     */
    @Select("""
            SELECT id, company_id, customer_id, amount, bank_account_id, receipt_date,
                   status, voucher_id, created_at, updated_at, deleted_at
              FROM ar.receipts
             WHERE id = #{id} AND deleted_at IS NULL
             FOR UPDATE
            """)
    Receipt selectByIdForUpdate(@Param("id") UUID id);

    /** 收款单分页列表（join 客户名 + 银行账户名；LEFT JOIN 账户允许未指定） */
    @Select("""
            SELECT r.id, r.company_id, r.customer_id, c.name AS customer_name,
                   r.amount, r.bank_account_id, b.bank_name, r.receipt_date, r.status,
                   r.voucher_id, r.created_at
              FROM ar.receipts r
              JOIN ar.customers c ON c.id = r.customer_id
              LEFT JOIN cash.bank_accounts b ON b.id = r.bank_account_id
             WHERE r.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR r.company_id = #{companyId}::uuid)
               AND (#{customerId}::uuid IS NULL OR r.customer_id = #{customerId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR r.status = #{status, jdbcType=VARCHAR})
             ORDER BY r.receipt_date DESC, r.created_at DESC
            """)
    IPage<ReceiptView> selectReceiptPage(Page<ReceiptView> page,
                                         @Param("companyId") UUID companyId,
                                         @Param("customerId") UUID customerId,
                                         @Param("status") String status);

    /** 收款单详情（join 客户/银行名；RLS 空集则返回 null） */
    @Select("""
            SELECT r.id, r.company_id, r.customer_id, c.name AS customer_name,
                   r.amount, r.bank_account_id, b.bank_name, r.receipt_date, r.status,
                   r.voucher_id, r.created_at
              FROM ar.receipts r
              JOIN ar.customers c ON c.id = r.customer_id
              LEFT JOIN cash.bank_accounts b ON b.id = r.bank_account_id
             WHERE r.id = #{id} AND r.deleted_at IS NULL
            """)
    ReceiptView selectDetail(@Param("id") UUID id);
}
