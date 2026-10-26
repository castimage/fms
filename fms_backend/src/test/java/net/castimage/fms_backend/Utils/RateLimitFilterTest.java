package net.castimage.fms_backend.Utils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Config.FmsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后端限流过滤器测试，login/recovery 滑动窗口、internal-batch 固定窗口，
 * 超限 429+42900 短路、滑动窗口带 Retry-After、固定窗口不带头、
 * 非目标路径直通、限流计数键含清洗后 ip（非法 ip 兜底 0.0.0.0 防键膨胀）。
 */
class RateLimitFilterTest {

    private CachePort cachePort;
    private FmsProperties fmsProperties;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter body;

    @BeforeEach
    void setUp() throws Exception {
        cachePort = mock(CachePort.class);
        fmsProperties = new FmsProperties(); // 默认档，login 20 / recovery 10 / internal-batch 600
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        body = new StringWriter();
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(response.getWriter()).thenReturn(new PrintWriter(body));
    }

    @Test
    void loginUnderLimitPasses() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(cachePort.checkSlidingWindow(eq("fms:ratelimit:sw:login:1.2.3.4"),
                eq(Duration.ofSeconds(60)), eq(20L)))
                .thenReturn(new CachePort.SlidingWindowResult(true, 20, 0));

        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(cachePort, never()).incrementFixedWindow(anyString(), any());
    }

    @Test
    void loginOverLimitWrites429AndRetryAfter() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(cachePort.checkSlidingWindow(anyString(), any(), anyLong()))
                .thenReturn(new CachePort.SlidingWindowResult(false, 20, 6000));

        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "6");
        assertTrue(body.toString().contains("42900"));
        assertTrue(body.toString().contains("请求过于频繁"));
    }

    @Test
    void recoveryOverLimitUsesOwnSlidingTier() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/recovery");
        when(cachePort.checkSlidingWindow(eq("fms:ratelimit:sw:recovery:1.2.3.4"),
                eq(Duration.ofSeconds(60)), eq(10L)))
                .thenReturn(new CachePort.SlidingWindowResult(false, 10, 1000));

        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "1");
    }

    @Test
    void internalEndpointOverLimitKeepsFixedWindowWithoutRetryAfter() throws Exception {
        when(request.getRequestURI()).thenReturn("/internal/log-batch");
        when(cachePort.incrementFixedWindow(anyString(), any())).thenReturn(601L);

        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(429);
        verify(response, never()).setHeader(eq("Retry-After"), anyString());
        verify(cachePort, never()).checkSlidingWindow(anyString(), any(), anyLong());
    }

    @Test
    void internalEndpointUnderLimitPasses() throws Exception {
        when(request.getRequestURI()).thenReturn("/internal/log-batch");
        when(cachePort.incrementFixedWindow(anyString(), any())).thenReturn(600L);

        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void nonTargetPathPassesWithoutCounting() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/gl/vouchers");

        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(cachePort, never()).checkSlidingWindow(anyString(), any(), anyLong());
        verify(cachePort, never()).incrementFixedWindow(anyString(), any());
    }

    /** 非法 ip（xff 非 ip 字符）清洗兜底 0.0.0.0，防限流键膨胀 */
    @Test
    void invalidIpSanitizedIntoCountingKey() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getHeader("X-Forwarded-For")).thenReturn("not-an-ip");
        when(cachePort.checkSlidingWindow(anyString(), any(), anyLong()))
                .thenReturn(new CachePort.SlidingWindowResult(true, 1, 0));

        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);

        verify(cachePort).checkSlidingWindow(eq("fms:ratelimit:sw:login:0.0.0.0"),
                eq(Duration.ofSeconds(60)), eq(20L));
    }

    /** Retry-After 毫秒向上取整到秒，最少 1 秒 */
    @Test
    void retryAfterRoundsUpToSeconds() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(cachePort.checkSlidingWindow(anyString(), any(), anyLong()))
                .thenReturn(new CachePort.SlidingWindowResult(false, 20, 1500));

        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);

        verify(response).setHeader("Retry-After", "2");
        assertEquals(429, body.toString().contains("42900") ? 429 : 0);
    }
}
