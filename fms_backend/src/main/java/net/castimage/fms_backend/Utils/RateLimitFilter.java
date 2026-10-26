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
 * /internal/log-batch，网关已有首道限流，本过滤器补签名合法但高频的请求，
 * 不重复限流业务 /api/** 全量，避免与网关双倍影响。
 *
 * 窗口语义，login/recovery 走精确滑动窗口，键 fms:ratelimit:sw:{type}:{ip}，
 * 任意 60 秒窗口内不超过阈值，被拒请求不写入窗口，超限写 429 + 42900 与
 * Retry-After；/internal/log-batch 走固定窗口，键 fms:ratelimit:{type}:{ip}，
 * 先自增再判限、拒绝也计数，不设 Retry-After。
 *
 * 位置说明，/internal/log-batch 在网关签名过滤器（urlPatterns=/api/*）覆盖之外，
 * 本过滤器以独立 urlPatterns 注册同样覆盖它；/api/* 的计数在签名校验（order=2）
 * 之后，未签名垃圾请求先被签名闸拒绝。
 */
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /** 窗口时长 60 秒，与 *-per-min 配置语义一致 */
    private static final Duration WINDOW = Duration.ofSeconds(60);

    /** 滑动窗口键前缀，ZSET 或内存时间戳队列 */
    private static final String SLIDING_KEY_PREFIX = "fms:ratelimit:sw:";

    /** 固定窗口键前缀，字符串自增计数 */
    private static final String FIXED_KEY_PREFIX = "fms:ratelimit:";

    private final CachePort cachePort;
    private final FmsProperties fmsProperties;


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Rule rule = resolve(request.getRequestURI());
        if (rule == null) {
            filterChain.doFilter(request, response); // 非限流目标路径直通
            return;
        }

        // getClientIp 已规范化，非法值归 0.0.0.0，防键膨胀
        String ip = RequestUtil.getClientIp(request);
        long count;
        long retryAfterMillis = 0;
        if (rule.sliding()) {
            CachePort.SlidingWindowResult result = cachePort.checkSlidingWindow(
                    SLIDING_KEY_PREFIX + rule.type() + ":" + ip, WINDOW, rule.limit());
            count = result.count();
            if (!result.allowed()) {
                retryAfterMillis = result.retryAfterMillis();
                reject(response, rule, ip, count, retryAfterMillis);
                return;
            }
        } else {
            count = cachePort.incrementFixedWindow(
                    FIXED_KEY_PREFIX + rule.type() + ":" + ip, WINDOW);
            if (count > rule.limit()) {
                reject(response, rule, ip, count, 0L);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /** 路径到限流规则，非目标路径返回 null */
    private Rule resolve(String uri) {
        if ("/api/auth/login".equals(uri)) {
            return new Rule("login", fmsProperties.getRateLimit().getLoginPerMin(), true);
        }
        if ("/api/auth/recovery".equals(uri)) {
            return new Rule("recovery", fmsProperties.getRateLimit().getRecoveryPerMin(), true);
        }
        if ("/internal/log-batch".equals(uri)) {
            return new Rule("internal-batch", fmsProperties.getRateLimit().getInternalBatchPerMin(), false);
        }
        return null;
    }

    /** 超限响应，滑动窗口写 Retry-After（毫秒向上取整到秒），固定窗口不写 */
    private void reject(HttpServletResponse response, Rule rule, String ip, long count,
                        long retryAfterMillis) throws IOException {
        long retryAfterSeconds = retryAfterMillis <= 0 ? 0
                : Math.max(1L, (retryAfterMillis + 999L) / 1000L);
        log.warn("后端限流触发 type={} mode={} ip={} count={} limit={} retryAfterSec={}",
                rule.type(), rule.sliding() ? "sliding" : "fixed",
                ip, count, rule.limit(), retryAfterSeconds);
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        }
        // 429 + 42900 统一错误响应（四字段 {code,message,data:null,traceId}，错误路径 data 恒 null）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 42900);
        body.put("message", "请求过于频繁，请稍后再试");
        body.put("data", null);
        body.put("traceId", TraceIdUtil.current());
        response.getWriter().write(JSON.toJSONString(body));
    }

    /** 限流规则，type 用于键与日志，limit 为窗口上限，sliding 选择窗口实现 */
    private record Rule(String type, int limit, boolean sliding) {
    }
}
