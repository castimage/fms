package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.Payment;
import net.castimage.fms_backend.Model.vo.PaymentView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 付款单 Mapper（ap.payments）：核销入口须 FOR UPDATE 行锁（防并发超分）；分页 join 供应商/银行账户 */
public interface PaymentMapper extends BaseMapper<Payment> {

    /**
     * 付款核销入口锁读：SELECT ... FOR UPDATE 锁定付款单行（写事务内使用），
     * 并发核销同一付款单串行化——status 须 PAID 的校验在锁内完成。
     */
    @Select("""
            SELECT id, company_id, supplier_id, amount, bank_account_id, payment_date,
                   status, voucher_id, created_at, updated_at, deleted_at
              FROM ap.payments
             WHERE id = #{id} AND deleted_at IS NULL
             FOR UPDATE
            """)
    Payment selectByIdForUpdate(@Param("id") UUID id);

    /** 付款单分页列表（join 供应商名 + 银行账户名；LEFT JOIN 账户允许未指定） */
    @Select("""
            SELECT p.id, p.company_id, p.supplier_id, s.name AS supplier_name,
                   p.amount, p.bank_account_id, b.bank_name, p.payment_date, p.status,
                   p.voucher_id, p.created_at
              FROM ap.payments p
              JOIN ap.suppliers s ON s.id = p.supplier_id
              LEFT JOIN cash.bank_accounts b ON b.id = p.bank_account_id
             WHERE p.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR p.company_id = #{companyId}::uuid)
               AND (#{supplierId}::uuid IS NULL OR p.supplier_id = #{supplierId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR p.status = #{status, jdbcType=VARCHAR})
             ORDER BY p.payment_date DESC, p.created_at DESC
            """)
    IPage<PaymentView> selectPaymentPage(Page<PaymentView> page,
                                         @Param("companyId") UUID companyId,
                                         @Param("supplierId") UUID supplierId,
                                         @Param("status") String status);

    /**
     * 幂等键查询：同公司下相同 dup_key（公司/供应商/账户/金额/付款日期派生）且未软删的
     * 付款单 —— 重复付款登记预检（uk_ap_payments_dup 唯一索引的应用层前置闸）。
     */
    @Select("""
            SELECT id, company_id, supplier_id, amount, bank_account_id, payment_date,
                   status, voucher_id, created_at, updated_at, deleted_at
              FROM ap.payments
             WHERE company_id = #{companyId} AND dup_key = #{dupKey} AND deleted_at IS NULL
             LIMIT 1
            """)
    Payment selectActiveByDupKey(@Param("companyId") UUID companyId, @Param("dupKey") UUID dupKey);

    /** 付款单详情（join 供应商/银行名；RLS 空集则返回 null） */
    @Select("""
            SELECT p.id, p.company_id, p.supplier_id, s.name AS supplier_name,
                   p.amount, p.bank_account_id, b.bank_name, p.payment_date, p.status,
                   p.voucher_id, p.created_at
              FROM ap.payments p
              JOIN ap.suppliers s ON s.id = p.supplier_id
              LEFT JOIN cash.bank_accounts b ON b.id = p.bank_account_id
             WHERE p.id = #{id} AND p.deleted_at IS NULL
            """)
    PaymentView selectDetail(@Param("id") UUID id);
}
