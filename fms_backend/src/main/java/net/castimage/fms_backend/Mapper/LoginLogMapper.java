package net.castimage.fms_backend.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Model.entity.LoginLog;
import net.castimage.fms_backend.Model.vo.LoginLogView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 登录日志 Mapper（um.login_log，仅追加只读）：BaseMapper 单表查询；分页（登录名模糊/
 * 时间范围/成功失败/锁定触发可选过滤）手写 B 类。FORCE RLS + 策略 login_log_select
 * （is_self OR has_role('fms_auditor') OR has_permission('audit:read:all')），写入封闭。
 */
public interface LoginLogMapper extends BaseMapper<LoginLog> {

    /**
     * 登录日志分页（loginName 模糊 + occurred_at 时间范围 + success 成功失败 + locked
     * 锁定触发可选过滤；排序白名单 occurred_at DESC, id DESC；ip inet 经 ::text 映射字符串）。
     * admin（fms_operator + audit:read:all 权限点）经 RLS 放行全量，operator/viewer/report 仅本人。
     * 注意：布尔参数带 ::boolean 显式类型（PG 18 对 "IS NULL 与布尔比较并存" 的参数推断报
     * 42P18 could not determine data type，与 from/to 的 ::timestamptz 同式规避）。
     */
    @Select("""
            SELECT id, user_id, login_name, ip::text AS ip, user_agent,
                   success, fail_reason, locked_until, occurred_at
              FROM um.login_log
             WHERE (#{loginName, jdbcType=VARCHAR} IS NULL OR #{loginName, jdbcType=VARCHAR} = ''
                    OR login_name ILIKE '%' || #{loginName, jdbcType=VARCHAR} || '%')
               AND (#{from}::timestamptz IS NULL OR occurred_at >= #{from}::timestamptz)
               AND (#{to}::timestamptz IS NULL OR occurred_at <= #{to}::timestamptz)
               AND (#{success}::boolean IS NULL OR success = #{success}::boolean)
               AND (#{locked}::boolean IS NULL
                    OR (#{locked}::boolean AND locked_until IS NOT NULL)
                    OR (NOT #{locked}::boolean AND locked_until IS NULL))
             ORDER BY occurred_at DESC, id DESC
            """)
    IPage<LoginLogView> selectLoginLogPage(Page<LoginLogView> page,
                                           @Param("loginName") String loginName,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           @Param("success") Boolean success,
                                           @Param("locked") Boolean locked);
}
