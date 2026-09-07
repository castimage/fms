package net.castimage.fms_backend.security;

import jakarta.servlet.FilterChain;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TraceIdFilter 单元测试，不依赖 Spring 容器，
 * 合法 uuid 透传并规范化小写、非法/缺失生成 v4、响应头恒写回、
 * RequestContext/MDC 在线程结束时清理，防 ThreadLocal 泄漏。
 */
class TraceIdFilterTest {

    @Test
    void validTraceIdHeaderIsPassedThroughCanonicalized() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/login");
        req.addHeader("X-Trace-Id", "01234567-89ab-cdef-0123-456789abcdef");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        new TraceIdFilter().doFilter(req, resp, chain(seen));

        assertEquals("01234567-89ab-cdef-0123-456789abcdef", resp.getHeader("X-Trace-Id"));
        assertEquals(resp.getHeader("X-Trace-Id"), seen.get());
    }

    @Test
    void uppercaseValidUuidIsNormalizedToCanonicalLowercase() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/org/companies");
        req.addHeader("X-Trace-Id", "01234567-89AB-CDEF-0123-456789ABCDEF");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        new TraceIdFilter().doFilter(req, resp, chain(new AtomicReference<>()));

        assertEquals("01234567-89ab-cdef-0123-456789abcdef", resp.getHeader("X-Trace-Id"));
    }

    @Test
    void invalidOrMissingHeaderGeneratesUuidV4() throws Exception {
        // 非法值，重新生成
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/org/companies");
        req.addHeader("X-Trace-Id", "not-a-uuid");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(req, resp, chain(new AtomicReference<>()));
        assertNotNull(UUID.fromString(resp.getHeader("X-Trace-Id")));

        // 缺失，生成
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/org/companies");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(req2, resp2, chain(new AtomicReference<>()));
        String generated = resp2.getHeader("X-Trace-Id");
        assertNotNull(generated);
        assertEquals(36, generated.length());
        assertTrue(generated.matches("[0-9a-f-]{36}"));
    }

    @Test
    void requestContextAndMdcAreClearedAfterFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        new TraceIdFilter().doFilter(req, resp, chain(new AtomicReference<>()));

        assertNull(RequestContext.get(), "RequestContext 必须在线程结束时清理");
        assertNull(MDC.get("traceId"), "MDC 必须在线程结束时清理");
    }

    /** 最小 FilterChain，记录链内 RequestContext.traceId */
    private static FilterChain chain(AtomicReference<String> seen) {
        return (servletRequest, servletResponse) -> {
            // 链内应已注入上下文，供下游/GatewaySignatureFilter 取用
            assertNotNull(RequestContext.get());
            seen.set(RequestContext.traceId());
        };
    }
}
