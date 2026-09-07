package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.vo.AppLogView;
import net.castimage.fms_backend.Model.vo.LoginLogView;
import net.castimage.fms_backend.Model.vo.ParamMetaView;
import net.castimage.fms_backend.Model.vo.ParamView;
import net.castimage.fms_backend.Model.vo.TaskRunView;

import java.time.LocalDateTime;
import java.util.List;

/** 管理侧业务接口（admin）：系统参数读写（um.params 业务参数，敏感键白名单禁写）、
 *  应用日志查询（log.app_logs）、登录日志查询（um.login_log）、任务监控（log.task_runs）；
 *  日志三表仅追加只读（FORCE RLS） */
public interface AdminService {

    /**
     * 系统参数分页（code 模糊过滤）。
     */
    PageResult<ParamView> pageParams(long page, long size, String keyword);

    /**
     * 单参数详情（按 code；不存在 40400）。
     */
    ParamView getParam(String code);

    /**
     * 参数元数据全量（管理页分组表单渲染权威源，经 um.fn_page_param_meta 读口）。
     */
    List<ParamMetaView> listParamMeta();

    /**
     * 参数读写（upsert：code 存在则更新 value/updated_by，不存在则插入）：
     * 敏感键（jwt. 前缀 / pgcrypto. 前缀 / 含 secret|password|token|key）白名单禁写 40300，
     * value 须为合法 JSON（fastjson2 校验），写路径 requireWriteRole 预检。
     */
    void setParam(String code, String value);

    /**
     * 应用日志分页（level 精确 + logger 模糊 + 时间范围可选）。
     */
    PageResult<AppLogView> pageAppLogs(long page, long size, String level, String logger,
                                       LocalDateTime from, LocalDateTime to);

    /**
     * 登录日志分页（loginName 模糊 + 时间范围 + success 成功失败 + locked 锁定触发可选；
     * 设计 615 登录审计）：admin 经 RLS 权限点 audit:read:all 放行全量（PG 层映射
     * fms_operator 时 is_self 仅本人可见问题由 init/um_login_log_query.sql 策略调整解决）。
     */
    PageResult<LoginLogView> pageLoginLogs(long page, long size, String loginName,
                                           LocalDateTime from, LocalDateTime to,
                                           Boolean success, Boolean locked);

    /**
     * 任务执行记录分页（jobName 模糊 + status 可选）。
     */
    PageResult<TaskRunView> pageTaskRuns(long page, long size, String jobName, String status);
}
