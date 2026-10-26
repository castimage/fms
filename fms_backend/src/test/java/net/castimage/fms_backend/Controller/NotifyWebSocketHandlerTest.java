package net.castimage.fms_backend.Controller;

import net.castimage.fms_backend.Service.NotifyService;
import net.castimage.fms_backend.Service.impl.NotifyWebSocketHub;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知 WebSocket 处理器测试，覆盖连接建立后的未读数推送、pong 回执、身份缺失关闭与注销。
 */
class NotifyWebSocketHandlerTest {

    private final NotifyService notifyService = mock(NotifyService.class);
    private final NotifyWebSocketHub hub = mock(NotifyWebSocketHub.class);
    private final NotifyWebSocketHandler handler = new NotifyWebSocketHandler(notifyService, hub);

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void establishedConnectionPushesUnreadCount() {
        UUID uid = UUID.randomUUID();
        WebSocketSession session = session(uid, JwtRole.ADMIN);
        when(notifyService.unreadCount(uid)).thenReturn(4L);

        handler.afterConnectionEstablished(session);

        verify(hub).register(uid, session);
        verify(hub).sendSession(session, "{\"type\":\"unread_count\",\"count\":4}");
        assertNull(RequestContext.userId(), "查询结束必须清理线程上下文");
    }

    @Test
    void missingIdentityClosesConnection() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>());

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(hub, never()).register(any(), any());
    }

    @Test
    void textMessageRepliesPong() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        handler.handleMessage(session, new TextMessage("ping"));

        verify(hub).sendSession(session, "{\"type\":\"pong\",\"echo\":ping}");
    }

    @Test
    void closedConnectionUnregistersSession() {
        WebSocketSession session = mock(WebSocketSession.class);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(hub).unregister(session);
    }

    @Test
    void transportErrorUnregistersAndCloses() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);

        handler.handleTransportError(session, new IllegalStateException("boom"));

        verify(hub).unregister(session);
        verify(session).close(CloseStatus.SERVER_ERROR);
    }

    /** 会话带网关采信身份属性 */
    private static WebSocketSession session(UUID uid, JwtRole role) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(NotifyHandshakeInterceptor.ATTR_UID, uid);
        attributes.put(NotifyHandshakeInterceptor.ATTR_ROLE, role);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
