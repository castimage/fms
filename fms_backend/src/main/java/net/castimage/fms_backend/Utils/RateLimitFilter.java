package net.castimage.fms_backend.Utils;


import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Config.FmsProperties;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.RequiredArgsConstructor;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后端补充限流过滤器，order=4，urlPatterns=/api/auth/login、/api/auth/recovery、
 * /internal/log-batch，网关已有首道限流，本过滤器是绕过网关直连后端时的纵深闸，
 * 仅覆盖三个敏感/匿名入口，不重复限流业务 /api/** 全量，避免与网关双倍影响。
 *
 * 实现，CachePort 滑动窗口计数（key=fms:ratelimit:{type}:{ip}，increment + ttl=60s，
 * 与 AuthServiceImpl.totpFailTryAcquire 同款语义，每次计数刷新 ttl 即滚动窗口，
 * ttl 过期自动归零），超限写 429 + 42900 统一错误响应，四字段，与
 * GatewaySignatureFilter.writeError 风格一致并短路返回，不进入 Controller。
 *
 * 位置说明，/internal/log-batch 在网关签名过滤器（urlPatterns=/api/*）覆盖之外，
 * 本过滤器以独立 urlPatterns 注册同样覆盖它，计数在签名校验（order=2）之后，
 * 未签名垃圾请求先被签名闸拒绝，限流闸聚焦"签名合法但高频"的直连流量。
 */
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /** 滑动窗口时长，秒，与 RedisCachePort ttl 对齐，ttl 过期自动归零 */
    private static final Duration WINDOW = Duration.ofSeconds(60);

    /** 限流键前缀，CachePort 命名空间同风格 fms:*，多实例共享，redis 模式全局计数 */
    private static final String KEY_PREFIX = "fms:ratelimit:";

    private final CachePort cachePort;
    private final FmsProperties fmsProperties;


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String type;
        int limit;
        if ("/api/auth/login".equals(uri)) {
            type = "login";
            limit = fmsProperties.getRateLimit().getLoginPerMin();
        } else if ("/api/auth/recovery".equals(uri)) {
            type = "recovery";
            limit = fmsProperties.getRateLimit().getRecoveryPerMin();
        } else if ("/internal/log-batch".equals(uri)) {
            type = "internal-batch";
            limit = fmsProperties.getRateLimit().getInternalBatchPerMin();
        } else {
            filterChain.doFilter(request, response); // 非限流目标路径直通，防御性兜底
            return;
        }

        // 滑动窗口计数，先计数再判限，被拒请求同样计入，滑动窗口语义与 totpFailRecord 一致，
        // getClientIp 已规范化，非法值归 0.0.0.0，防键膨胀
        String ip = RequestUtil.getClientIp(request);
        String key = KEY_PREFIX + type + ":" + ip;
        long count = cachePort.increment(key, WINDOW);
        if (count > limit) {
            log.warn("后端限流触发 type={} ip={} count={} limit={} path={}",
                    type, ip, count, limit, uri);
            // 429 + 42900 统一错误响应（四字段 {code,message,data:null,traceId}，错误路径 data 恒 null）
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 42900);
            body.put("message", "请求过于频繁，请稍后再试");
            body.put("data", null);
            body.put("traceId", TraceIdUtil.current());
            response.getWriter().write(JSON.toJSONString(body));
            return; // 短路，不进入后续过滤器链与 Controller
        }

        filterChain.doFilter(request, response);
    }
}
