package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.ReconcileRun;
import net.castimage.fms_backend.Model.vo.ReconcileRunView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 对账运行批次 Mapper（cash.reconcile_runs）：自动对账/人工对账落运行统计，分页查询手写 */
public interface ReconcileRunMapper extends BaseMapper<ReconcileRun> {

    /** 运行批次分页列表：companyId 可选过滤（排序白名单固定） */
    @Select("""
            SELECT r.id, r.company_id, r.period, r.run_at, r.auto_matched,
                   r.manual_matched, r.diff_count, r.created_at
              FROM cash.reconcile_runs r
             WHERE r.deleted_at IS NULL
               AND (#{companyId}::uuid IS NULL OR r.company_id = #{companyId}::uuid)
             ORDER BY r.run_at DESC, r.id
            """)
    IPage<ReconcileRunView> selectPage(Page<ReconcileRunView> page,
                                       @Param("companyId") UUID companyId);
}
