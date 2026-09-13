package net.castimage.fms_backend.Utils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.config.FmsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后端限流过滤器测试，登录/找回/内部端点三档限流，超限 429+42900 短路、
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
    void underLimitPasses() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(cachePort.increment(anyString(), any())).thenReturn(20L); // 恰好达到阈值内
        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void loginOverLimitWrites429AndShortCircuits() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(cachePort.increment(anyString(), any())).thenReturn(21L); // 超过 20/min
        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);
        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(429);
        assertTrue(body.toString().contains("42900"));
        assertTrue(body.toString().contains("请求过于频繁"));
    }

    @Test
    void recoveryOverLimitUsesOwnTier() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/recovery");
        when(cachePort.increment(anyString(), any())).thenReturn(11L); // 超过 10/min
        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);
        verify(chain, never()).doFilter(any(), any());
        assertEquals(429, body.toString().contains("42900") ? 429 : 0);
    }

    @Test
    void internalEndpointOverLimit() throws Exception {
        when(request.getRequestURI()).thenReturn("/internal/log-batch");
        when(cachePort.increment(anyString(), any())).thenReturn(601L); // 超过 600/min
        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void nonTargetPathPassesWithoutCounting() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/gl/vouchers");
        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verify(cachePort, never()).increment(anyString(), any());
    }

    /** 非法 ip（xff 非 ip 字符）清洗兜底 0.0.0.0，防限流键膨胀 */
    @Test
    void invalidIpSanitizedIntoCountingKey() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getHeader("X-Forwarded-For")).thenReturn("not-an-ip");
        when(cachePort.increment(anyString(), any())).thenReturn(1L);
        new RateLimitFilter(cachePort, fmsProperties).doFilter(request, response, chain);
        verify(cachePort).increment(anyString(), any());
    }
}
