package net.castimage.fms_backend.Utils;


import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Service.ApiKeyService;
import net.castimage.fms_backend.Model.vo.ApiKeyIdentity;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.RequiredArgsConstructor;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * api-key 校验过滤器（order=3，urlPatterns=/api/*，执行序，TraceIdFilter=1 之后
 * GatewaySignatureFilter=2 签名校验，再到本过滤器）。
 *
 * 职责，系统间集成，网关无密钥库不校验，auth.rs 对
 * api-key 模式透传 X-api-key、剥离客户端 X-FMS-*、不注入身份头、签名身份段为空串，
 * 校验收敛在后端。本过滤器位于签名校验通过后、身份采信前，
 *
 * jwt 模式，GatewaySignatureFilter 已采信身份或匿名端点则直接透传，
 * 读 X-api-key，经 ApiKeyService 的 SECURITY DEFINER 函数 auth.api_key_validate
 * 在库内计算 hex(hmac(key, blind_key, 'sha256'))（盲索引密钥 sec.app_secrets
 * 不对任何角色授权，Java 不可直读）等值查 um.api_keys.key_hash，校验
 * status='ACTIVE' 且未过期并回写 last_used_at，
 * 通过后身份由 key.user_id + scope 决定（read 映射 fms_viewer、read_write 映射 fms_operator），
 * 不采信任何 X-FMS-* 头，网关不注入、伪造头在签名环节已失败，此处双保险，
 * 任何失败，key 缺失/无效/非 ACTIVE/过期/吊销/db 异常一律 40100 fail-closed，
 * 不泄露失败原因，防枚举。
 */
@RequiredArgsConstructor
@Slf4j
public class ApiKeyFilter extends OncePerRequestFilter {

    /**
     * 匿名端点，与 GatewaySignatureFilter.ANONYMOUS_PATHS 同列表，登录发生在身份建立
     * 之前，无身份可采信亦无 X-api-key，直接透传，登录页公开端点，背景配置/背景图同属
     * 匿名白名单，列表重复属有意为之，两过滤器独立演进，任一扩展须两端同步。
     */
    private static final List<String> ANONYMOUS_PATHS = List.of(
            "/api/auth/login", "/api/auth/refresh", "/api/auth/recovery",
            "/api/auth/recovery/reset", "/api/nt/ws", "/api/public/login-background");

    private static boolean isAnonymousPath(String path) {
        return ANONYMOUS_PATHS.contains(path) || path.startsWith("/api/auth/totp/")
                || path.startsWith("/api/public/files/");
    }

    /** api-key 校验服务，SECURITY DEFINER 函数闸口 */
    private final ApiKeyService apiKeyService;

    /** 仅本地开发跳过签名校验，生产必须 false，跳过时本过滤器整体放行 */
    private final boolean bypassGateway;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 本地开发 bypass，无签名无身份，校验无意义，/internal/health 为网关熔断探测端点
        return bypassGateway || "/internal/health".equals(request.getRequestURI());
    }

    /**
     * api-key 校验主流程，身份已采信，jwt 模式或匿名端点则透传，否则读 X-api-key，
     * 缺失则 40100（签名过滤器已对无身份请求 fail-closed，此处双保险），校验失败则 40100，
     * 通过则由 key.user_id + scope 注入身份，orgIds/deptIds 传 null 由 pg 侧 scope 表
     * 预载，finally 清理 ThreadLocal（与 GatewaySignatureFilter 双保险）。
     *
     * @param request     原始请求，body 已被 GatewaySignatureFilter 缓存，X-FMS-* 伪造头已剥离
     * @param response    校验失败时写统一错误响应，http 401 + 业务码 40100
     * @param filterChain 校验通过后继续执行的后继过滤器链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // jwt 模式身份已采信 / 匿名端点则透传。注意，TraceIdFilter（order=1）已注入
        // 仅含 traceId 的占位上下文（userId=null），故不能用 get!=null 判身份，
        // 必须用 authenticated（要求 userId 非 null），api-key 模式网关不注入
        // 身份头，签名过滤器跳过采信后占位上下文保留 userId=null，正是校验入口。
        if (RequestContext.authenticated() || isAnonymousPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 读 X-api-key（与网关 auth.rs 一致，trim 后非空才算 api-key 模式）
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("缺少 X-API-Key: path={} ip={}", request.getRequestURI(), request.getRemoteAddr());
            writeError(response, 40100, "missing api key");
            return;
        }

        // 校验，库内盲索引等值查 + ACTIVE/未过期 + last_used_at 回写，任何失败则 40100
        ApiKeyIdentity identity = apiKeyService.verify(apiKey.trim());
        if (identity == null) {
            log.warn("X-API-Key 校验失败: path={} ip={}", request.getRequestURI(), request.getRemoteAddr());
            writeError(response, 40100, "invalid api key");
            return;
        }

        // scope 映射角色（read 映射 fms_viewer、read_write 映射 fms_operator），未知 scope fail-closed
        JwtRole role = switch (identity.scope()) {
            case "read" -> JwtRole.VIEWER;
            case "read_write" -> JwtRole.OPERATOR;
            default -> null;
        };
        if (role == null) {
            log.warn("X-API-Key scope 非法: scope={}", identity.scope());
            writeError(response, 40100, "invalid api key scope");
            return;
        }

        // 采信身份，仅来自 key.user_id + scope，不读任何 X-FMS-* 头，orgIds/deptIds 传 null
        // 由 pg 侧 auth.set_session 从 scope 表预载，与签名过滤器 jwt 路径同语义
        try {
            RequestContext.set(new RequestContext(identity.userId(), role, null, null, TraceIdUtil.current()));
            filterChain.doFilter(request, response);
        } finally {
            // 双保险清理，GatewaySignatureFilter finally 亦清理，防 ThreadLocal 泄漏
            RequestContext.clear();
        }
    }

    /** 写统一错误响应（{code,message,data:null,traceId}），错误路径 data 恒为 null */
    private void writeError(HttpServletResponse response, int code, String message) throws IOException {
        String traceId = TraceIdUtil.current();
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        body.put("traceId", traceId);
        response.getWriter().write(JSON.toJSONString(body));
    }
}
