package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.TaskRun;
import net.castimage.fms_backend.Model.vo.TaskRunView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 定时任务执行记录 Mapper（log.task_runs，仅追加只读）：分页（jobName 模糊 + status 精确可选）
 * 手写 B 类；FORCE RLS + 仅 SELECT 策略，写入由 pg_cron/超级用户完成。
 */
public interface TaskRunMapper extends BaseMapper<TaskRun> {

    /**
     * 任务执行记录分页（jobName 模糊 + status 精确可选；排序白名单 started_at DESC, id DESC；
     * detail JSONB 经 ::text 映射字符串）。
     */
    @Select("""
            SELECT id, job_name, status, detail::text AS detail, started_at, finished_at
              FROM log.task_runs
             WHERE (#{jobName, jdbcType=VARCHAR} IS NULL OR #{jobName, jdbcType=VARCHAR} = ''
                    OR job_name ILIKE '%' || #{jobName, jdbcType=VARCHAR} || '%')
               AND (#{status, jdbcType=VARCHAR} IS NULL OR #{status, jdbcType=VARCHAR} = ''
                    OR status = #{status, jdbcType=VARCHAR})
             ORDER BY started_at DESC, id DESC
            """)
    IPage<TaskRunView> selectTaskRunPage(Page<TaskRunView> page,
                                         @Param("jobName") String jobName,
                                         @Param("status") String status);
}
