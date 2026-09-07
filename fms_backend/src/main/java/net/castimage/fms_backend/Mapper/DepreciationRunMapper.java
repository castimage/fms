package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.DepreciationRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * 折旧计提批次 Mapper（fa.depreciation_runs）：lines JSONB 映射 String（fastjson2 解析）；
 * 写入经自定义 insertRun——lines 参数显式 ::jsonb 转换落库（BaseMapper.insert 按 varchar
 * 绑定会触发 42804 'column lines is of type jsonb but expression is of type character varying'）；
 * 确认（DRAFT 转 CONFIRMED）为原子 UPDATE，NOT EXISTS 同公司同期间 CONFIRMED 双闸 +
 * 部分唯一索引 uk_depr_run 兜底（23505 映射 40900），防并发重复计提。
 */
public interface DepreciationRunMapper extends BaseMapper<DepreciationRun> {

    /** 批次落库（lines JSONB 经 ::jsonb 显式转换；created_at/updated_at 走表默认值） */
    @Insert("""
            INSERT INTO fa.depreciation_runs (id, company_id, period, run_by, status, lines)
            VALUES (#{id}::uuid, #{companyId}::uuid, #{period},
                    #{runBy}::uuid, #{status},
                    #{lines, jdbcType=VARCHAR}::jsonb)
            """)
    int insertRun(DepreciationRun run);

    /** 同公司同期间批次（防重复检查）：CONFIRMED 存在则跳过本次计提；返回 null 表示无批次 */
    @Select("""
            SELECT * FROM fa.depreciation_runs
             WHERE company_id = #{companyId}::uuid AND period = #{period}
               AND deleted_at IS NULL
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR status = #{status, jdbcType=VARCHAR})
             ORDER BY created_at DESC
             LIMIT 1
            """)
    DepreciationRun selectByCompanyPeriod(@Param("companyId") UUID companyId,
                                          @Param("period") String period,
                                          @Param("status") String status);

    /** 计提批次分页/明细数据源（分页插件改写） */
    @Select("""
            SELECT * FROM fa.depreciation_runs
             WHERE deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR company_id = #{companyId}::uuid)
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR period = #{period, jdbcType=VARCHAR})
             ORDER BY created_at DESC, id
            """)
    List<DepreciationRun> selectRuns(@Param("companyId") UUID companyId,
                                     @Param("period") String period);

    /**
     * 批次确认（DRAFT 转 CONFIRMED，双闸防重复）：NOT EXISTS 同公司同期间 CONFIRMED
     * （应用层双闸）+ 部分唯一索引 uk_depr_run(company_id, period) WHERE status='CONFIRMED'
     * 终审（并发下 23505 映射 40900）；RLS update 策略（仅 DRAFT 可改）恒 0 行则 Service 40900。
     *
     * @param id 批次 id
     * @return 影响行数（0 = 状态非 DRAFT 或同期间已有 CONFIRMED）
     */
    @Update("""
            UPDATE fa.depreciation_runs
               SET status = 'CONFIRMED', updated_at = now()
             WHERE id = #{id}::uuid
               AND status = 'DRAFT'
               AND deleted_at IS NULL
               AND NOT EXISTS (
                   SELECT 1 FROM fa.depreciation_runs d
                    WHERE d.company_id = fa.depreciation_runs.company_id
                      AND d.period = fa.depreciation_runs.period
                      AND d.status = 'CONFIRMED' AND d.deleted_at IS NULL)
            """)
    int confirm(@Param("id") UUID id);

    /**
     * 折旧凭证草稿回填（批次到凭证链接）：仅 DRAFT 批次可回填（CONFIRMED 后冻结，
     * 与 fa_depreciation_runs_update 策略口径一致）；双路径（cron PG 函数 / 本 Service）
     * 生成凭证后调用。
     *
     * @return 影响行数（0 = 批次非 DRAFT/已删除）
     */
    @Update("""
            UPDATE fa.depreciation_runs
               SET voucher_id = #{voucherId}::uuid, updated_at = now()
             WHERE id = #{id}::uuid
               AND status = 'DRAFT'
               AND deleted_at IS NULL
            """)
    int linkVoucher(@Param("id") UUID id, @Param("voucherId") UUID voucherId);

    /**
     * 折旧凭证幂等键查重（调 gl.fn_idem_voucher_exists，SECURITY DEFINER 无视 RLS）：
     * DRAFT 凭证仅创建人可见，跨路径（cron 以种子管理员建、本 Service 以当前操作员建）
     * 查重必须全量可见，否则同一期间会产出两张折旧凭证。
     */
    @Select("SELECT gl.fn_idem_voucher_exists(#{companyId}::uuid, #{period}, #{idemKey})")
    boolean idemVoucherExists(@Param("companyId") UUID companyId,
                              @Param("period") String period,
                              @Param("idemKey") String idemKey);

    /**
     * 折旧凭证科目解析（调 gl.fn_depr_accounts）：按 um.params fa.depr.*.account 返回
     * 该公司 {depreciation, accumulated} 科目 id（JSONB 文本；科目缺失为 null）——科目
     * 映射参数单一来源在 PG（职责角色无 um.params 表级授权，Java 不可直读）。
     */
    @Select("SELECT gl.fn_depr_accounts(#{companyId}::uuid)")
    String deprAccounts(@Param("companyId") UUID companyId);
}
