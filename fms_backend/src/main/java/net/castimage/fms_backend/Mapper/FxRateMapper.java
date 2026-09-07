package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.FxRate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 期末汇率 Mapper（gl.fx_rates）：查询/软删走 BaseMapper + Wrapper；
 * 写入经自定义 upsert——ON CONFLICT (company_id, currency, period) WHERE deleted_at IS NULL
 * DO UPDATE（同键活跃行 PUT 为更新语义；软删行不占部分唯一索引键则按新行插入复活，
 * BaseMapper.insert 会 23505；2026-08 修复：唯一键改部分索引 uk_fx_rate_active 后
 * ON CONFLICT 须带索引谓词方可仲裁）。
 */
public interface FxRateMapper extends BaseMapper<FxRate> {

    /** 汇率 upsert（幂等：同公司×币种×期间活跃行存在则更新 rate/维护人并刷新 updated_at、清 deleted_at；软删行以新行复活） */
    @Insert("""
            INSERT INTO gl.fx_rates (id, company_id, currency, period, rate, updated_by)
            VALUES (#{id}::uuid, #{companyId}::uuid, #{currency}, #{period},
                    #{rate}, #{updatedBy}::uuid)
            ON CONFLICT (company_id, currency, period) WHERE deleted_at IS NULL DO UPDATE
               SET rate = EXCLUDED.rate,
                   updated_by = EXCLUDED.updated_by,
                   updated_at = now(),
                   deleted_at = NULL
            """)
    int upsert(@Param("id") UUID id,
               @Param("companyId") UUID companyId,
               @Param("currency") String currency,
               @Param("period") String period,
               @Param("rate") BigDecimal rate,
               @Param("updatedBy") UUID updatedBy);
}
