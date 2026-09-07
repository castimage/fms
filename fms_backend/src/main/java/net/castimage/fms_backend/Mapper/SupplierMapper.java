package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.Supplier;
import net.castimage.fms_backend.Model.vo.SupplierView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 供应商 Mapper（ap.suppliers）：单表软删经 BaseMapper + Wrapper；分页手写（trgm 模糊检索） */
public interface SupplierMapper extends BaseMapper<Supplier> {

    /**
     * 供应商分页列表（B 类手写 SQL）：name 走 trgm 相似度（% 可加速），短词/中文
     * 分词不可用场景以 ILIKE 兜底；companyId 仅过滤提示，可见集合由 RLS 收敛。
     */
    @Select("""
            SELECT s.id, s.company_id, s.code, s.name, s.tax_no, s.credit_limit, s.status, s.created_at
              FROM ap.suppliers s
             WHERE s.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR s.company_id = #{companyId}::uuid)
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR s.name % #{keyword, jdbcType=VARCHAR}::text
                    OR s.name ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%'
                    OR s.code ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%')
             ORDER BY s.code
            """)
    IPage<SupplierView> selectSupplierPage(Page<SupplierView> page,
                                           @Param("companyId") UUID companyId,
                                           @Param("keyword") String keyword); // 排序白名单固定，分页改写交给拦截器，
}
