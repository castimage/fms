package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.castimage.fms_backend.Model.entity.Period;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 会计期间 Mapper（gl.periods，查询走 BaseMapper + Wrapper；期间开关/调汇经 PG SECURITY DEFINER 函数） */
public interface PeriodMapper extends BaseMapper<Period> {

    /** 期间结账：调 gl.fn_close_period（未完成凭证/断号检查 + CLOSED + 审计原子完成；权限闸口 gl.period.close 在函数内）。
     * 完整性预检（未完成凭证计数/断号）已内置于函数非 p_force 分支，无需 Service 层重复调查询。
     * 函数返回 void，经子查询剥离结果列后取常量 1（同 VoucherMapper.postVoucher 模式） */
    @Options(flushCache = Options.FlushCachePolicy.TRUE) // 函数以 SELECT 形式调用，清 L1 缓存防回读陈旧状态，
    @Select("""
            SELECT 1
              FROM (SELECT gl.fn_close_period(#{companyId}::uuid, #{period}, #{force})) AS _cp
            """)
    int closePeriod(@Param("companyId") UUID companyId,
                    @Param("period") String period,
                    @Param("force") boolean force); // 业务侧恒 false（p_force 仅历史数据修复用）

    /** 期间反结账：调 gl.fn_reopen_period（原因必填 + fms.force_reopen 二次确认 + 仅最晚期间 + OPEN + 审计原子完成）。
     * 二次确认开关未开启时函数 RAISE，P0001 消息含"结账"则映射 40900，由前端提示先开开关。
     * 反结账须先经 {@link #setForceReopenOn()} 于同事务注入 GUC（持久开关由 Service 读
     * um.params 后 SET LOCAL 传入——API 连接本身不携带该 GUC，见 GlServiceImpl.reopenPeriod） */
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT 1
              FROM (SELECT gl.fn_reopen_period(#{companyId}::uuid, #{period}, #{reason})) AS _rp
            """)
    int reopenPeriod(@Param("companyId") UUID companyId,
                     @Param("period") String period,
                     @Param("reason") String reason);

    /** 事务内注入反结账二次确认 GUC（SET LOCAL 事务级，归还连接自动失效；仅供 reopenPeriod 前置调用） */
    @Select("SET LOCAL fms.force_reopen = 'on'")
    void setForceReopenOn();

    /** 期末调汇：调 gl.fn_fx_adjustment（外币科目期末余额 × 期末汇率与按 1:1 计价账面的差生成 DRAFT 调汇凭证草稿；
     * 幂等键 fx_adjustment:+period 防重复生成；返回生成的调汇凭证数） */
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT gl.fn_fx_adjustment(#{companyId}::uuid, #{period}, #{baseCurrency})")
    int runFxAdjustment(@Param("companyId") UUID companyId,
                        @Param("period") String period,
                        @Param("baseCurrency") String baseCurrency); // 本币恒 'CNY'，函数内缺省同值
}
