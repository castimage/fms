package net.castimage.fms_backend.Service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Exception.BadRequestException;
import net.castimage.fms_backend.Exception.ForbiddenException;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Mapper.AppLogMapper;
import net.castimage.fms_backend.Mapper.LoginLogMapper;
import net.castimage.fms_backend.Mapper.ParamMapper;
import net.castimage.fms_backend.Mapper.TaskRunMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.vo.AppLogView;
import net.castimage.fms_backend.Model.vo.LoginLogView;
import net.castimage.fms_backend.Model.vo.ParamFieldView;
import net.castimage.fms_backend.Model.vo.ParamMetaView;
import net.castimage.fms_backend.Model.vo.ParamView;
import net.castimage.fms_backend.Model.vo.TaskRunView;
import net.castimage.fms_backend.Service.AdminService;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理侧业务实现，系统参数读写（um.params 业务参数，敏感密钥存 sec.app_secrets，Java 侧
 * 敏感键白名单禁写双保险，value jsonb 经 fastjson2 校验合法 json 后 ::jsonb 落库，标量/对象
 * 两种形态均兼容读取分支），应用日志/任务监控两表仅追加只读（FORCE rls）。
 */
@Service
public class AdminServiceImpl implements AdminService {

    /** 敏感键前缀/子串白名单，sec 之外的业务参数可写，密钥收敛防误写回 um.params */
    private static final String[] FORBIDDEN_PARAM_PREFIXES = {"jwt.", "pgcrypto."};
    private static final String[] FORBIDDEN_PARAM_SUBSTRINGS = {"secret", "password", "token", "master_key", "blind_key"};

    private final ParamMapper paramMapper;
    private final AppLogMapper appLogMapper;
    private final TaskRunMapper taskRunMapper;
    private final LoginLogMapper loginLogMapper;

    public AdminServiceImpl(ParamMapper paramMapper, AppLogMapper appLogMapper,
                            TaskRunMapper taskRunMapper, LoginLogMapper loginLogMapper) {
        this.paramMapper = paramMapper;
        this.appLogMapper = appLogMapper;
        this.taskRunMapper = taskRunMapper;
        this.loginLogMapper = loginLogMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ParamView> pageParams(long page, long size, String keyword) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        String kw = keyword == null ? null : keyword.trim();
        return PageResult.of(paramMapper.selectParamsPage(new Page<>(pageNum, sizeNum), kw));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<ParamMetaView> listParamMeta() {
        // 子字段元数据按 code 分组合并进 meta.fields，对象参数前端渲染子表单，
        // 无子字段的参数 fields 为空列表，前端保持整段 json 编辑兜底
        List<ParamMetaView> meta = paramMapper.selectParamMeta();
        Map<String, List<ParamFieldView>> byCode =
                paramMapper.selectParamMetaField().stream()
                        .collect(Collectors.groupingBy(ParamFieldView::code));
        return meta.stream()
                .map(m -> new ParamMetaView(
                        m.code(), m.grp(), m.label(), m.controlType(), m.unit(),
                        m.minVal(), m.maxVal(), m.step(), m.options(), m.hint(), m.sortNo(),
                        m.sensitive(), byCode.getOrDefault(m.code(), List.of())))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public ParamView getParam(String code) {
        // 经 um.fn_get_param SECURITY DEFINER 函数读取，职责角色无 um.params 表级 SELECT
        ParamView p = paramMapper.selectParamByCode(trim(code));
        if (p == null) {
            throw new NotFoundException("参数不存在：" + trim(code));
        }
        return p;
    }

    /**
     * {@inheritDoc}
     *
     * 写路径三重防护，① RequestContext.requireWriteRole，只读角色 40300，
     * 敏感键白名单（jwt./pgcrypto. 前缀 + secret/password/token/… 子串则 40300，

     * 敏感键只存 sec.app_secrets），③ value fastjson2 校验
     * 合法 json（标量/对象均可，落库 ::jsonb）。code 存在则原子更新 value/updated_by，
     * 不存在则 INSERT（uk_params_code 冲突由 23505 映射 40900 兜底）。
     */
    @Override
    @Transactional
    public void setParam(String code, String value) {
        RequestContext.requireWriteRole();
        String key = trim(code);
        if (key == null || key.isEmpty()) {
            throw new BadRequestException("参数编码必填");
        }
        assertNotSensitive(key);
        if (value == null || value.isBlank()) {
            throw new BadRequestException("参数值必填");
        }
        try {
            JSON.parse(value); // 仅校验合法性，jsonb 落库，标量/对象两种形态均接受，
        } catch (JSONException e) {
            throw new BadRequestException("参数值须为合法 JSON");
        }
        UUID userId = RequestContext.requireUserId();

        // 经 um.fn_upsert_param SECURITY DEFINER 函数原子 upsert（职责角色无表级
        // UPDATE/INSERT，函数内 admin.param.update 闸口 + 敏感键白名单 db 侧双保险）
        paramMapper.upsertParam(key, value, userId);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<AppLogView> pageAppLogs(long page, long size, String level, String logger,
                                              LocalDateTime from, LocalDateTime to) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        return PageResult.of(appLogMapper.selectAppLogPage(new Page<>(pageNum, sizeNum),
                trim(level), trim(logger), from, to));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<LoginLogView> pageLoginLogs(long page, long size, String loginName,
                                                  LocalDateTime from, LocalDateTime to,
                                                  Boolean success, Boolean locked) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        return PageResult.of(loginLogMapper.selectLoginLogPage(new Page<>(pageNum, sizeNum),
                trim(loginName), from, to, success, locked));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<TaskRunView> pageTaskRuns(long page, long size, String jobName, String status) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.min(Math.max(size, 1), 100);
        return PageResult.of(taskRunMapper.selectTaskRunPage(new Page<>(pageNum, sizeNum),
                trim(jobName), trim(status)));
    }

    // 私有方法

    /** 敏感键白名单判定，前缀 + 子串双匹配，大小写不敏感 */
    private void assertNotSensitive(String code) {
        String lower = code.toLowerCase(Locale.ROOT);
        for (String prefix : FORBIDDEN_PARAM_PREFIXES) {
            if (lower.startsWith(prefix)) {
                throw new ForbiddenException("敏感参数禁止经此通道写入：" + code + "（密钥收敛于 sec.app_secrets）");
            }
        }
        for (String substring : FORBIDDEN_PARAM_SUBSTRINGS) {
            if (lower.contains(substring)) {
                throw new ForbiddenException("敏感参数禁止经此通道写入：" + code + "（密钥收敛于 sec.app_secrets）");
            }
        }
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }
}
