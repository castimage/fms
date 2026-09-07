package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.Customer;
import net.castimage.fms_backend.Model.vo.CustomerView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 客户 Mapper（ar.customers）：单表软删经 BaseMapper + Wrapper；分页手写（trgm 模糊检索） */
public interface CustomerMapper extends BaseMapper<Customer> {

    /**
     * 客户分页列表（B 类手写 SQL）：name 走 GIN trgm 索引（similarity/% 可加速），
     * 短词/中文分词不可用场景以 ILIKE 兜底；code 恒 ILIKE；companyId 仅 UI 过滤提示，
     * 可见集合由 RLS（can_access_org）收敛，越权值返回空集而非报错。
     */
    @Select("""
            SELECT c.id, c.company_id, c.code, c.name, c.tax_no, c.credit_limit, c.status, c.created_at
              FROM ar.customers c
             WHERE c.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR c.company_id = #{companyId}::uuid)
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR c.name % #{keyword, jdbcType=VARCHAR}::text
                    OR c.name ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%'
                    OR c.code ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%')
             ORDER BY c.code
            """)
    IPage<CustomerView> selectCustomerPage(Page<CustomerView> page,
                                           @Param("companyId") UUID companyId,
                                           @Param("keyword") String keyword); // 排序白名单固定，分页改写交给拦截器，
}
