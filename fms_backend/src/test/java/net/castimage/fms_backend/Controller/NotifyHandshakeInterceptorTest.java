package net.castimage.fms_backend.Controller;

import net.castimage.fms_backend.Utils.JwtRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知握手拦截器测试，覆盖网关身份头的合法写入与缺失/非法拒绝，拒绝时返回 403 且不建会话。
 */
class NotifyHandshakeInterceptorTest {

    private final NotifyHandshakeInterceptor interceptor = new NotifyHandshakeInterceptor();

    @Test
    void validHeadersBecomeSessionAttributes() {
        UUID uid = UUID.randomUUID();
        Map<String, Object> attributes = new HashMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-FMS-UserId", uid.toString());
        headers.set("X-FMS-Role", "admin");
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);

        boolean accepted = interceptor.beforeHandshake(request, mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), attributes);

        assertTrue(accepted);
        assertEquals(uid, attributes.get(NotifyHandshakeInterceptor.ATTR_UID));
        assertEquals(JwtRole.ADMIN, attributes.get(NotifyHandshakeInterceptor.ATTR_ROLE));
    }

    @Test
    void missingIdentityIsRejectedWith403() {
        Map<String, Object> attributes = new HashMap<>();
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor.beforeHandshake(request, response,
                mock(WebSocketHandler.class), attributes);

        assertFalse(accepted);
        assertTrue(attributes.isEmpty());
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidUidOrRoleIsRejectedWith403() {
        Map<String, Object> attributes = new HashMap<>();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-FMS-UserId", "not-a-uuid");
        headers.set("X-FMS-Role", "root");
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor.beforeHandshake(request, response,
                mock(WebSocketHandler.class), attributes);

        assertFalse(accepted);
        assertTrue(attributes.isEmpty());
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }
}
