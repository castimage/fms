package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.Voucher;
import net.castimage.fms_backend.Model.vo.VoucherGapView;
import net.castimage.fms_backend.Model.vo.VoucherListView;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 凭证头 Mapper（gl.vouchers）：单表软删经 BaseMapper + Wrapper，制单人登录名查询迁至 UserMapper；过账/冲销/分页手写 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    /** 过账（APPROVED 转 POSTED）：调 PG SECURITY DEFINER 函数原子完成鉴权+状态推进+触发器取号+审计（grants_base 已授 EXECUTE，规避 RLS 更新拦截与不可变触发器拒绝顺序；异常 RAISE 映射 40900）。
     * 函数返回 void，直接 SELECT 会返回单行 void 列，MyBatis 无法映射到 void 返回类型（ExecutorException），故经子查询剥离结果列后取常量 1 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE) // 函数以 SELECT 形式调用，MyBatis 不会自动失效 L1 缓存——执行前清缓存，防 buildDetail 回读到事务早前的陈旧状态
    @Select("""
            SELECT 1
              FROM (SELECT gl.fn_post_voucher(#{voucherId}::uuid)) AS _post
            """)
    int postVoucher(@Param("voucherId") UUID voucherId); // 取号/审计在函数内原子完成，

    /** 红字冲销（POSTED 转 REVERSED）：函数内建反向凭证（借贷互换），随后原凭证置 REVERSED 并审计，返回反向凭证 id */
    @Select("SELECT gl.fn_reverse_voucher(#{voucherId}::uuid, #{reverseDate}, #{reason})")
    UUID reverseVoucher(@Param("voucherId") UUID voucherId,
                        @Param("reverseDate") LocalDate reverseDate,
                        @Param("reason") String reason);

    /** 驳回（SUBMITTED 转 REJECTED）：调 PG SECURITY DEFINER 函数原子完成鉴权（gl.voucher.reject）+
     * SoD 复核 + 状态推进 + 状态历史 remark=原因（GUC 透传）+ 审计（GRANT 已授 EXECUTE；
     * 异常 RAISE 映射 40900）。函数返回 void，直接 SELECT 会返回单行 void 列，MyBatis 无法
     * 映射到 void 返回类型（ExecutorException），故经子查询剥离结果列后取常量 1 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE) // 同 postVoucher：函数以 SELECT 形式调用，防 L1 缓存回读陈旧状态
    @Select("""
            SELECT 1
              FROM (SELECT gl.fn_reject_voucher(#{voucherId}::uuid, #{reason})) AS _rej
            """)
    int rejectVoucher(@Param("voucherId") UUID voucherId,
                      @Param("reason") String reason);

    /** 退回（REJECTED 转 DRAFT）：调 PG SECURITY DEFINER 函数 gl.fn_revert_voucher 原子完成鉴权
     * （gl.voucher.revert）+ 状态推进 + 状态历史 + 审计（GRANT 已授 EXECUTE；异常 RAISE 映射 40900
     * 映射）。函数返回 void，同 rejectVoucher 经子查询剥离结果列后取常量 1 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE) // 同 postVoucher：函数以 SELECT 形式调用，防 L1 缓存回读陈旧状态
    @Select("""
            SELECT 1
              FROM (SELECT gl.fn_revert_voucher(#{voucherId}::uuid)) AS _rev
            """)
    int revertVoucher(@Param("voucherId") UUID voucherId);

    /** 凭证分页列表（RLS 生效验证点）：LEFT JOIN um.users 联查制单人 + EXISTS 子查询摘要模糊匹配 + 4 可选条件 + 排序白名单（voucher_date DESC, voucher_no DESC）；越权值返回空集而非报错 */
    @Select("""
            SELECT v.id, v.company_id, v.voucher_no, v.period, v.voucher_date, v.status,
                   v.source_type, v.is_private, v.dept_id, v.created_by,
                   u.login_name AS created_by_name,
                   v.approved_by, v.approved_at, v.posted_by, v.posted_at,
                   v.total_debit, v.total_credit, v.note, v.created_at
              FROM gl.vouchers v
              LEFT JOIN um.users u ON u.id = v.created_by
             WHERE v.deleted_at IS NULL
               AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                    OR v.period = #{period, jdbcType=VARCHAR})
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR v.status = #{status, jdbcType=VARCHAR})
               AND (#{companyId}::uuid IS NULL OR v.company_id = #{companyId}::uuid)
               AND (#{createdByMe, jdbcType=BOOLEAN} IS NULL OR #{createdByMe, jdbcType=BOOLEAN} = false
                    OR v.created_by = #{userId}::uuid)
               AND (#{keyword, jdbcType=VARCHAR} IS NULL OR #{keyword, jdbcType=VARCHAR} = ''
                    OR v.note ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%'
                    OR EXISTS (SELECT 1 FROM gl.voucher_lines vl
                                WHERE vl.voucher_id = v.id AND vl.deleted_at IS NULL
                                  AND vl.summary ILIKE '%' || #{keyword, jdbcType=VARCHAR} || '%'))
             ORDER BY v.voucher_date DESC, v.voucher_no DESC
            """)
    IPage<VoucherListView> selectVoucherPage(Page<VoucherListView> page,
                                             @Param("period") String period,
                                             @Param("status") String status,
                                             @Param("companyId") UUID companyId,
                                             @Param("createdByMe") Boolean createdByMe,
                                             @Param("keyword") String keyword,
                                             @Param("userId") UUID userId); // 排序白名单固定，分页改写交给拦截器，

    /** 凭证所属公司（RLS 收敛；越权/软删则 null，附件上传公司推导用，客户端不可自报） */
    @Select("SELECT company_id FROM gl.vouchers WHERE id = #{id} AND deleted_at IS NULL")
    UUID selectVoucherCompanyId(@Param("id") UUID id);

    /** 凭证断号清单（镜像 gl.fn_cron_voucher_number_check 的期间缺口 SQL——同口径
     * status IN ('POSTED','REVERSED') + voucher_no NOT NULL + lag 窗口差分，但按公司/期间
     * 在线过滤并返回缺失号段而非计数；companyId/period 可空=全范围扫描，RLS 收敛可见集合） */
    @Select("""
            SELECT g.company_id, g.period,
                   (g.prev_no + 1)::integer AS gap_from,
                   (g.voucher_no - 1)::integer AS gap_to,
                   (g.voucher_no - g.prev_no - 1)::integer AS gap_count
              FROM (SELECT company_id, period, voucher_no,
                           lag(voucher_no) OVER
                               (PARTITION BY company_id, period ORDER BY voucher_no) AS prev_no
                      FROM gl.vouchers
                     WHERE status IN ('POSTED','REVERSED') AND deleted_at IS NULL
                       AND voucher_no IS NOT NULL
                       AND (#{period, jdbcType=VARCHAR} IS NULL OR #{period, jdbcType=VARCHAR} = ''
                            OR period = #{period, jdbcType=VARCHAR})
                       AND (#{companyId}::uuid IS NULL OR company_id = #{companyId}::uuid)) g
             WHERE g.prev_no IS NOT NULL AND g.voucher_no - g.prev_no > 1
             ORDER BY g.company_id, g.period, g.prev_no
            """)
    List<VoucherGapView> selectVoucherGaps(@Param("companyId") UUID companyId,
                                           @Param("period") String period);
}
