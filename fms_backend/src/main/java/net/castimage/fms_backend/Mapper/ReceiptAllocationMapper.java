package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.ReceiptAllocation;
import net.castimage.fms_backend.Model.vo.AllocationView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 收款核销明细 Mapper（ar.receipt_allocations）：核销防重双闸——
 * ① uk_ar_alloc(receipt_id, invoice_id) 唯一约束（23505 映射 40900）；
 * ② version 乐观锁（updateWithVersion 带 WHERE version=?，0 行则 40900）。
 */
public interface ReceiptAllocationMapper extends BaseMapper<ReceiptAllocation> {

    /** 发票已核销金额合计（SUM(amount)，核销余额预检基准；含软删过滤） */
    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM ar.receipt_allocations
             WHERE invoice_id = #{invoiceId} AND deleted_at IS NULL
            """)
    BigDecimal selectAllocatedSumByInvoice(@Param("invoiceId") UUID invoiceId);

    /** 收款单已核销金额合计（SUM(amount)，收款单剩余可核销额预检基准；含软删过滤） */
    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM ar.receipt_allocations
             WHERE receipt_id = #{receiptId} AND deleted_at IS NULL
            """)
    BigDecimal selectAllocatedSumByReceipt(@Param("receiptId") UUID receiptId);

    /** 按收款单查核销历史（join 发票号/开票日期；冲销回算与详情展示数据源） */
    @Select("""
            SELECT ra.id, ra.receipt_id, ra.invoice_id, i.invoice_no, i.invoice_date,
                   ra.amount, ra.version, ra.created_at
              FROM ar.receipt_allocations ra
              JOIN ar.invoices i ON i.id = ra.invoice_id
             WHERE ra.receipt_id = #{receiptId} AND ra.deleted_at IS NULL
             ORDER BY ra.created_at
            """)
    List<AllocationView> selectByReceiptId(@Param("receiptId") UUID receiptId);

    /**
     * 乐观锁更新核销金额（B 类手写 SQL）：WHERE version = #{oldVersion} 条件化——
     * 并发修改（version 已变）或软删后命中 0 行，Service 侧转 40900 冲突；
     * 命中则 version+1 推进。
     *
     * @param id         核销明细 id
     * @param oldVersion 读到的旧版本号
     * @param amount     新核销金额（不可超过发票余额，Service 预检）
     * @return 更新行数（0 = 版本冲突/已删除）
     */
    @Update("""
            UPDATE ar.receipt_allocations
               SET amount = #{amount}, version = version + 1, updated_at = now()
             WHERE id = #{id} AND version = #{oldVersion} AND deleted_at IS NULL
            """)
    int updateWithVersion(@Param("id") UUID id,
                          @Param("oldVersion") int oldVersion,
                          @Param("amount") BigDecimal amount);

    /** 撤销核销（版本化软删经 auth.fn_soft_delete_v 闸口，SECURITY DEFINER 豁免 RLS 新行可见性检查）：0 = 版本冲突或已被撤销，Service 转 40900 */
    @Select("""
            SELECT auth.fn_soft_delete_v('ar', 'receipt_allocations', #{id}::uuid, #{oldVersion})
            """)
    int softDeleteWithVersion(@Param("id") UUID id, @Param("oldVersion") int oldVersion);
}
