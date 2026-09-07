package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.PaymentAllocation;
import net.castimage.fms_backend.Model.vo.AllocationView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 付款核销明细 Mapper（ap.payment_allocations）：核销防重双闸——
 * ① uk_ap_alloc(payment_id, invoice_id) 唯一约束（23505 映射 40900）；
 * ② version 乐观锁（updateWithVersion 带 WHERE version=?，0 行则 40900）。
 * 核销与供应商对账解耦（对账仅回填 matched_invoice_id）。
 */
public interface PaymentAllocationMapper extends BaseMapper<PaymentAllocation> {

    /** 发票已核销金额合计（付款余额预检基准；含软删过滤） */
    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM ap.payment_allocations
             WHERE invoice_id = #{invoiceId} AND deleted_at IS NULL
            """)
    BigDecimal selectPaidSumByInvoice(@Param("invoiceId") UUID invoiceId);

    /** 按付款单查核销历史（join 发票号/开票日期；冲销回算与详情展示数据源） */
    @Select("""
            SELECT pa.id, pa.payment_id AS receipt_id, pa.invoice_id, i.invoice_no,
                   i.invoice_date, pa.amount, pa.version, pa.created_at
              FROM ap.payment_allocations pa
              JOIN ap.invoices i ON i.id = pa.invoice_id
             WHERE pa.payment_id = #{paymentId} AND pa.deleted_at IS NULL
             ORDER BY pa.created_at
            """)
    List<AllocationView> selectByPaymentId(@Param("paymentId") UUID paymentId);

    /**
     * 乐观锁更新核销金额（B 类手写 SQL）：WHERE version = #{oldVersion} 条件化——
     * 并发修改或软删后命中 0 行，Service 侧转 40900；命中则 version+1 推进。
     *
     * @return 更新行数（0 = 版本冲突/已删除）
     */
    @Update("""
            UPDATE ap.payment_allocations
               SET amount = #{amount}, version = version + 1, updated_at = now()
             WHERE id = #{id} AND version = #{oldVersion} AND deleted_at IS NULL
            """)
    int updateWithVersion(@Param("id") UUID id,
                          @Param("oldVersion") int oldVersion,
                          @Param("amount") BigDecimal amount);

    /** 撤销核销（版本化软删经 auth.fn_soft_delete_v 闸口，SECURITY DEFINER 豁免 RLS 新行可见性检查）：0 = 版本冲突或已被撤销，Service 转 40900 */
    @Select("""
            SELECT auth.fn_soft_delete_v('ap', 'payment_allocations', #{id}::uuid, #{oldVersion})
            """)
    int softDeleteWithVersion(@Param("id") UUID id, @Param("oldVersion") int oldVersion);
}
