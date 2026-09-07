package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.AppLog;
import net.castimage.fms_backend.Model.vo.AppLogView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 应用日志 Mapper（log.app_logs，仅追加只读）：BaseMapper 单表查询；分页（level/logger/时间范围
 * 可选过滤）手写 B 类。FORCE RLS + 仅 SELECT 策略（current_uid() IS NOT NULL），写入封闭。
 */
public interface AppLogMapper extends BaseMapper<AppLog> {

    /**
     * 应用日志分页（level 精确 + logger 模糊 + occurred_at 时间范围可选；排序白名单
     * occurred_at DESC, id DESC；context JSONB 经 ::text 映射字符串）。
     */
    @Select("""
            SELECT id, level, logger, message, context::text AS context, occurred_at
              FROM log.app_logs
             WHERE (#{level, jdbcType=VARCHAR} IS NULL OR #{level, jdbcType=VARCHAR} = ''
                    OR level = #{level, jdbcType=VARCHAR})
               AND (#{logger, jdbcType=VARCHAR} IS NULL OR #{logger, jdbcType=VARCHAR} = ''
                    OR logger ILIKE '%' || #{logger, jdbcType=VARCHAR} || '%')
               AND (#{from}::timestamptz IS NULL OR occurred_at >= #{from}::timestamptz)
               AND (#{to}::timestamptz IS NULL OR occurred_at <= #{to}::timestamptz)
             ORDER BY occurred_at DESC, id DESC
            """)
    IPage<AppLogView> selectAppLogPage(Page<AppLogView> page,
                                       @Param("level") String level,
                                       @Param("logger") String logger,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);
}
