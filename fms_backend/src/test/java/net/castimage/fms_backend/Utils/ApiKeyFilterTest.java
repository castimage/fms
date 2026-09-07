package net.castimage.fms_backend.Utils;

import net.castimage.fms_backend.Utils.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.castimage.fms_backend.Model.vo.ApiKeyIdentity;
import net.castimage.fms_backend.Service.ApiKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * api-key 校验过滤器测试，匿名端点/jwt 已认证透传、缺失/无效 key 40100 fail-closed、
 * scope 映射角色（read 映射 viewer、read_write 映射 operator）、校验通过后身份注入与 ThreadLocal 清理。
 */
class ApiKeyFilterTest {

    private static final UUID UID = UuidV7.next();

    private ApiKeyService apiKeyService;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter body;

    @BeforeEach
    void setUp() throws Exception {
        apiKeyService = mock(ApiKeyService.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear(); // 防 ThreadLocal 跨测试串扰
    }

    @Test
    void anonymousPathPassesWithoutValidation() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        new ApiKeyFilter(apiKeyService, false).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void jwtAuthenticatedPassesWithoutValidation() throws Exception {
        RequestContext.set(new RequestContext(UID, JwtRole.OPERATOR, null, null, "t"));
        when(request.getRequestURI()).thenReturn("/api/ar/invoices");
        new ApiKeyFilter(apiKeyService, false).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void missingApiKeyWrites40100() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/ar/invoices");
        new ApiKeyFilter(apiKeyService, false).doFilter(request, response, chain);
        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(401);
        assertTrue(body.toString().contains("40100"));
        assertTrue(body.toString().contains("missing api key"));
    }

    @Test
    void invalidApiKeyWrites40100() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/ar/invoices");
        when(request.getHeader("X-API-Key")).thenReturn("fms_invalid");
        when(apiKeyService.verify("fms_invalid")).thenReturn(null);
        new ApiKeyFilter(apiKeyService, false).doFilter(request, response, chain);
        verify(chain, never()).doFilter(any(), any());
        assertTrue(body.toString().contains("invalid api key"));
    }

    /** read scope 映射 fms_viewer 身份注入，chain 内可见且 finally 清理 */
    @Test
    void readScopeInjectsViewerIdentity() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/ar/invoices");
        when(request.getHeader("X-API-Key")).thenReturn("fms_key");
        when(apiKeyService.verify("fms_key")).thenReturn(new ApiKeyIdentity(UID, "read"));

        doAnswer(inv -> {
            assertTrue(RequestContext.authenticated());
            assertEquals(UID, RequestContext.userId());
            assertEquals(JwtRole.VIEWER, RequestContext.role());
            return null;
        }).when(chain).doFilter(any(), any());

        new ApiKeyFilter(apiKeyService, false).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        assertNull(RequestContext.get()); // finally 清理
    }

    @Test
    void readWriteScopeInjectsOperatorIdentity() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/ar/invoices");
        when(request.getHeader("X-API-Key")).thenReturn("fms_key");
        when(apiKeyService.verify("fms_key")).thenReturn(new ApiKeyIdentity(UID, "read_write"));

        doAnswer(inv -> {
            assertEquals(JwtRole.OPERATOR, RequestContext.role());
            return null;
        }).when(chain).doFilter(any(), any());

        new ApiKeyFilter(apiKeyService, false).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void invalidScopeWrites40100() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/ar/invoices");
        when(request.getHeader("X-API-Key")).thenReturn("fms_key");
        when(apiKeyService.verify("fms_key")).thenReturn(new ApiKeyIdentity(UID, "write"));
        new ApiKeyFilter(apiKeyService, false).doFilter(request, response, chain);
        verify(chain, never()).doFilter(any(), any());
        assertTrue(body.toString().contains("invalid api key scope"));
    }

    @Test
    void bypassGatewayPassesThrough() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/ar/invoices");
        new ApiKeyFilter(apiKeyService, true).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(apiKeyService);
    }
}
