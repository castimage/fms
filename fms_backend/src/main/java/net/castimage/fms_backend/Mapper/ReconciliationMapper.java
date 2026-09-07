package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.Reconciliation;
import net.castimage.fms_backend.Model.vo.ReconciliationView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 供应商对账单 Mapper（ap.reconciliations）：核对动作不改发票/付款状态；分页/详情 join 供应商名 */
public interface ReconciliationMapper extends BaseMapper<Reconciliation> {

    /** 对账单分页列表（join 供应商名；supplierId/status 过滤） */
    @Select("""
            SELECT r.id, r.company_id, r.supplier_id, s.name AS supplier_name,
                   r.statement_date, r.status, r.created_at
              FROM ap.reconciliations r
              JOIN ap.suppliers s ON s.id = r.supplier_id
             WHERE r.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR r.company_id = #{companyId}::uuid)
               AND (#{supplierId}::uuid IS NULL OR r.supplier_id = #{supplierId}::uuid)
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR r.status = #{status, jdbcType=VARCHAR})
             ORDER BY r.statement_date DESC, r.created_at DESC
            """)
    IPage<ReconciliationView> selectReconciliationPage(Page<ReconciliationView> page,
                                                       @Param("companyId") UUID companyId,
                                                       @Param("supplierId") UUID supplierId,
                                                       @Param("status") String status);

    /** 对账单头详情（join 供应商名；RLS 空集则返回 null） */
    @Select("""
            SELECT r.id, r.company_id, r.supplier_id, s.name AS supplier_name,
                   r.statement_date, r.status, r.created_at
              FROM ap.reconciliations r
              JOIN ap.suppliers s ON s.id = r.supplier_id
             WHERE r.id = #{id} AND r.deleted_at IS NULL
            """)
    ReconciliationView selectDetail(@Param("id") UUID id);
}
