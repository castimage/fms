package net.castimage.fms_backend.Service.impl;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.castimage.fms_backend.Utils.WsNotifyBroadcaster;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 通知 WebSocket 会话容器与推送入口，单例 Bean，保存本实例内的在线会话并按用户或全量发送。
 *
 * 会话按用户 id 分组，同用户多端并发在线；注册时用 ConcurrentWebSocketSessionDecorator
 * 包装，串行化容器线程、业务线程与 Redis 订阅线程的发送。发送失败即移除会话，
 * 断线由处理器关闭回调兜底。
 *
 * 跨实例推送经 WsNotifyBroadcaster：本地已先行发送，广播供其它实例查本地会话表补齐，
 * 订阅端按发送方实例 id 跳过自收消息。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotifyWebSocketHub {

    /** 单条消息发送上限毫秒数，超时按发送失败处理 */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /** 单会话待发缓冲上限字节数，超限按发送失败处理 */
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    /** 会话表，用户 id 到已包装会话集合 */
    private final Map<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    /** 会话 id 到用户 id，注销与发送失败时定位归属 */
    private final Map<String, UUID> owners = new ConcurrentHashMap<>();

    private final WsNotifyBroadcaster broadcaster;

    /**
     * 注册在线会话，包装为并发安全会话。
     *
     * 注册与注销串行执行，保证会话表与归属表同时生效，避免连接建立与断开交错时
     * 新会话被加入已从映射移除的集合。
     *
     * @param uid     会话所属用户
     * @param session 握手建立的原始会话
     */
    public synchronized void register(UUID uid, WebSocketSession session) {
        WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        sessions.computeIfAbsent(uid, key -> new CopyOnWriteArraySet<>()).add(guarded);
        owners.put(session.getId(), uid);
    }

    /**
     * 注销会话，移除本端记录并清理空用户分组。
     *
     * @param session 关闭或异常的原始会话
     */
    public synchronized void unregister(WebSocketSession session) {
        if (session == null) {
            return;
        }
        UUID uid = owners.remove(session.getId());
        if (uid == null) {
            return;
        }
        Set<WebSocketSession> userSessions = sessions.get(uid);
        if (userSessions == null) {
            return;
        }
        userSessions.removeIf(item -> item.getId().equals(session.getId()));
        if (userSessions.isEmpty()) {
            sessions.remove(uid, userSessions);
        }
    }

    /**
     * 向指定会话发送文本，优先使用注册时的包装会话。
     *
     * @param session 目标原始会话
     * @param payload 文本载荷
     */
    public void sendSession(WebSocketSession session, String payload) {
        if (session == null) {
            return;
        }
        UUID uid = owners.get(session.getId());
        WebSocketSession target = findById(uid, session.getId());
        if (target == null) {
            target = session;
        }
        if (!sendQuietly(target, payload) && uid != null) {
            unregister(target);
        }
    }

    /**
     * 向指定用户的本实例在线会话发送，并广播到其它实例。
     *
     * @param uid     目标用户
     * @param payload 文本载荷
     */
    public void sendToUser(UUID uid, String payload) {
        sendLocal(uid, payload);
        broadcaster.broadcast(uid, payload);
    }

    /**
     * 仅向本实例内指定用户的在线会话发送。
     *
     * @param uid     目标用户
     * @param payload 文本载荷
     */
    public void sendLocal(UUID uid, String payload) {
        Set<WebSocketSession> userSessions = sessions.get(uid);
        if (userSessions == null || userSessions.isEmpty()) {
            return;
        }
        for (WebSocketSession session : userSessions) {
            if (!sendQuietly(session, payload)) {
                unregister(session);
            }
        }
    }

    /**
     * 向本实例全部在线会话发送，并广播到其它实例。
     *
     * @param payload 文本载荷
     */
    public void broadcast(String payload) {
        broadcastLocal(payload);
        broadcaster.broadcastAll(payload);
    }

    /**
     * 仅向本实例全部在线会话发送。
     *
     * @param payload 文本载荷
     */
    public void broadcastLocal(String payload) {
        for (Map.Entry<UUID, Set<WebSocketSession>> entry : sessions.entrySet()) {
            for (WebSocketSession session : entry.getValue()) {
                if (!sendQuietly(session, payload)) {
                    unregister(session);
                }
            }
        }
    }

    /** 按用户与会话 id 查找包装会话，未命中返回 null */
    private WebSocketSession findById(UUID uid, String sessionId) {
        if (uid == null) {
            return null;
        }
        Set<WebSocketSession> userSessions = sessions.get(uid);
        if (userSessions == null) {
            return null;
        }
        for (WebSocketSession session : userSessions) {
            if (session.getId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    /** 静默发送，成功返回 true，关闭或异常返回 false */
    private static boolean sendQuietly(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
                return true;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("WebSocket 发送失败 session={}: {}", session.getId(), e.getMessage());
        }
        return false;
    }
}
