package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.Asset;
import net.castimage.fms_backend.Model.vo.AssetView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 固定资产卡片 Mapper（fa.assets）：CRUD 走 BaseMapper + Wrapper（net_value 生成列只读），分页手写 */
public interface AssetMapper extends BaseMapper<Asset> {

    /**
     * 资产分页列表：keyword 命中 asset_code/category（ILIKE），status 可选过滤；
     * 排序白名单固定（asset_code）；RLS can_access_org 收敛可见公司。
     */
    @Select("""
            SELECT a.id, a.company_id, a.asset_code, a.category, a.original_value,
                   a.residual_value, a.life_months, a.depreciation_method,
                   a.accumulated_depreciation, a.net_value, a.start_date, a.status,
                   a.disposal_date, a.created_at
              FROM fa.assets a
             WHERE a.deleted_at IS NULL
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR a.status = #{status, jdbcType=VARCHAR})
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR a.asset_code ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%'
                    OR a.category ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%')
             ORDER BY a.asset_code
            """)
    IPage<AssetView> selectAssetPage(Page<AssetView> page,
                                     @Param("keyword") String keyword,
                                     @Param("status") String status);
}
