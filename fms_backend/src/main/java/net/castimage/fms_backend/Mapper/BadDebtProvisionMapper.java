package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.BadDebtProvision;
import net.castimage.fms_backend.Model.vo.BadDebtView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 坏账计提 Mapper（ar.bad_debt_provision）：(invoice_id, period) 查重防重复计提；分页 join 发票号 */
public interface BadDebtProvisionMapper extends BaseMapper<BadDebtProvision> {

    /** 按 (invoice_id, period) 查重（防重复计提；软删记录不参与） */
    @Select("""
            SELECT id, company_id, invoice_id, period, amount, status,
                   created_at, updated_at, deleted_at
              FROM ar.bad_debt_provision
             WHERE invoice_id = #{invoiceId} AND period = #{period} AND deleted_at IS NULL
             LIMIT 1
            """)
    BadDebtProvision selectByInvoicePeriod(@Param("invoiceId") UUID invoiceId,
                                           @Param("period") String period);

    /** 计提分页列表（join 发票号；status 过滤） */
    @Select("""
            SELECT b.id, b.company_id, b.invoice_id, i.invoice_no, b.period, b.amount,
                   b.status, b.created_at
              FROM ar.bad_debt_provision b
              JOIN ar.invoices i ON i.id = b.invoice_id
             WHERE b.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR b.company_id = #{companyId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR b.status = #{status, jdbcType=VARCHAR})
             ORDER BY b.period DESC, b.created_at DESC
            """)
    IPage<BadDebtView> selectBadDebtPage(Page<BadDebtView> page,
                                         @Param("companyId") UUID companyId,
                                         @Param("status") String status);
}
