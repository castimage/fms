package net.castimage.fms_backend.Controller;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.castimage.fms_backend.Service.NotifyService;
import net.castimage.fms_backend.Service.impl.NotifyWebSocketHub;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;

/**
 * 通知 WebSocket 处理器，路径 /api/nt/ws，Spring WebSocketHandler 注册。
 *
 * 连接建立时从会话属性取网关采信的用户 id 与角色，注册到 NotifyWebSocketHub，
 * 并以该身份查未读数推送 {"type":"unread_count","count":N}；
 * 身份缺失或非法直接关闭连接。文本消息按服务端推送模型处理，仅回 pong 保活。
 *
 * 容器线程没有 HTTP 请求上下文，推送前显式设置 RequestContext，查询结束即清理，
 * 使 rls 与会话变量与 HTTP 路径同语义，防线程复用残留。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotifyWebSocketHandler extends TextWebSocketHandler {

    private final NotifyService notifyService;
    private final NotifyWebSocketHub hub;

    /**
     * 连接建立，校验身份并推送当前未读数。
     *
     * @param session 已升级的会话，属性由 NotifyHandshakeInterceptor 写入
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID uid = attribute(session, NotifyHandshakeInterceptor.ATTR_UID, UUID.class);
        JwtRole role = attribute(session, NotifyHandshakeInterceptor.ATTR_ROLE, JwtRole.class);
        if (uid == null || role == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        hub.register(uid, session);
        RequestContext.set(new RequestContext(uid, role, null, null, null));
        try {
            hub.sendSession(session, "{\"type\":\"unread_count\",\"count\":"
                    + notifyService.unreadCount(uid) + "}");
        } catch (RuntimeException e) {
            log.warn("WebSocket 未读数推送失败 uid={}: {}", uid, e.getMessage());
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * 文本消息回执，业务不做指令解析，通知为服务端推送模型，回发 pong 保活。
     *
     * @param session 当前会话
     * @param message 客户端文本消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        hub.sendSession(session, "{\"type\":\"pong\",\"echo\":" + message.getPayload() + "}");
    }

    /**
     * 连接关闭，注销会话，同用户多端仅移除本会话。
     *
     * @param session 关闭的会话
     * @param status  关闭状态
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.unregister(session);
    }

    /**
     * 传输异常，记日志并注销会话，随后由容器回调关闭。
     *
     * @param session 异常会话
     * @param error   异常原因
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        log.warn("WebSocket 异常 session={} msg={}", session == null ? null : session.getId(),
                error == null ? "" : error.getMessage());
        hub.unregister(session);
        if (session != null) {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    /** 取会话属性并校验类型，缺失或类型不符返回 null */
    private static <T> T attribute(WebSocketSession session, String key, Class<T> type) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttributes().get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /** 静默关闭，关闭失败无需处理 */
    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session != null && session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            // 容器超时兜底
        }
    }
}
