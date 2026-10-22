package net.castimage.fms_backend.Controller;


import lombok.extern.slf4j.Slf4j;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import net.castimage.fms_backend.Service.NotifyService;
import net.castimage.fms_backend.Utils.JwtRole;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.WsNotifyBroadcaster;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 通知 WebSocket 端点，路径 /api/nt/ws，JSR-356 @ServerEndpoint，由 WebSocketConfig 注册到
 * 内嵌 Tomcat ServerContainer，连接时按网关采信身份注册会话并按 NotifyService 推送
 * 当前未读数（{"type":"unread_count","count":N}），已读动作后经 NotifyService 的 sendToUser
 * 做增量推送。
 *
 * 路径与网关的 WebSocket 判定一致，网关按 /api/nt/ws 精确匹配升级请求并保路径转发，
 * 本端点即该路径的最终处理者；身份取握手头 X-FMS-UserId 与 X-FMS-Role
 * （NotifyEndpointConfigurator 搬入用户属性），该头由网关注入且被 X-FMS-Signature 覆盖，
 * 端点不读查询参数，客户端改参数无法订阅他人通知。
 *
 * 容器线程没有 HTTP 请求上下文，建立会话时显式 set RequestContext 再查未读数，
 * 使 rls 与会话变量与 HTTP 路径同语义，查询结束即 clear，防线程复用残留。
 * 会话表以 uid 为键，CopyOnWriteArraySet 支持同用户多端并发连接，
 * 断线（OnClose/OnError）自动注销防会话泄漏。
 */
@ServerEndpoint("/api/nt/ws")
@Slf4j
public class NotifyWebSocketEndpoint {

    /** 在线会话表（uid 到会话集合，同用户多端并发在线） */
    private static final Map<UUID, Set<Session>> SESSIONS = new ConcurrentHashMap<>();

    /** NotifyService 静态引用，WebSocketConfig 启动期注入，端点由容器按连接实例化，无 Spring 注入 */
    private static volatile NotifyService notifyService;

    /** 跨实例广播器，WsNotifyRedisBridge 装配期注入，null/未启用 = 单实例本地推送语义 */
    private static volatile WsNotifyBroadcaster broadcaster;

    /** 注入业务服务，仅启动期调用一次，随后只读 */
    public static void setNotifyService(NotifyService service) {
        NotifyWebSocketEndpoint.notifyService = service;
    }

    /** 注入跨实例广播器，仅启动期调用一次，redis 模式装配 Redis 广播器，in-memory 装配 no-op */
    public static void setBroadcaster(WsNotifyBroadcaster b) {
        NotifyWebSocketEndpoint.broadcaster = b;
    }

    /**
     * 连接建立，按网关采信身份注册会话，推送当前未读数，首次握手即回执，供角标初始化。
     *
     * 身份缺失或非白名单角色即关闭连接，fail-closed；未读数查询在本线程显式建立
     * 请求上下文后执行，查询异常只记日志不中断会话，角标另有 REST 通道兜底。
     */
    @OnOpen
    public void onOpen(Session session) {
        UUID uid = sessionUid(session);
        JwtRole role = sessionRole(session);
        if (uid == null || role == null) {
            closeQuietly(session, "身份缺失或非法");
            return;
        }

        SESSIONS.computeIfAbsent(uid, k -> new CopyOnWriteArraySet<>()).add(session);
        if (notifyService == null) {
            return;                              // 正常启动序已注入，缺失时不推送，避免空指针
        }
        RequestContext.set(new RequestContext(uid, role, null, null, null));
        try {
            sendQuietly(session, "{\"type\":\"unread_count\",\"count\":"
                    + notifyService.unreadCount(uid) + "}");
        } catch (RuntimeException e) {
            log.warn("WebSocket 未读数推送失败 uid={}: {}", uid, e.getMessage());
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * 消息回执，业务不做指令解析，通知为服务端推送模型，回发 pong 保活。
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        sendQuietly(session, "{\"type\":\"pong\",\"echo\":" + message + "}");
    }

    /** 连接关闭，注销会话，同用户多端仅移除本会话 */
    @OnClose
    public void onClose(Session session) {
        UUID uid = sessionUid(session);
        if (uid != null) {
            Set<Session> set = SESSIONS.get(uid);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    SESSIONS.remove(uid);
                }
            }
        }
    }

    /** 异常，记日志并注销会话，容器随后回调 onClose，此处防重复清理幂等 */
    @OnError
    public void onError(Session session, Throwable error) {
        log.warn("WebSocket 异常 session={} msg={}", session == null ? null : session.getId(),
                error == null ? "" : error.getMessage());
        if (session != null) {
            onClose(session);
        }
    }

    /**
     * 向指定用户推送文本，通知模块已读/新通知后调用，本地会话表逐发 + 跨实例广播，多实例下用户连接可能挂在其他实例，广播后由各实例订阅端查本地表推送，
     * redis 广播自收消息由订阅端按实例 id 跳过，本实例不重复推送。
     */
    public static void sendToUser(UUID uid, String payload) {
        sendLocal(uid, payload);
        WsNotifyBroadcaster b = broadcaster;
        if (b != null) {
            b.broadcast(uid, payload);
        }
    }

    /**
     * 本地会话表推送，广播订阅端与本实例业务路径共用，该用户所有在线会话逐发，
     * 失败会话静默移除，断线容错，不阻塞业务事务。
     */
    public static void sendLocal(UUID uid, String payload) {
        Set<Session> set = SESSIONS.get(uid);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (Session s : set) {
            if (!sendQuietly(s, payload)) {
                set.remove(s); // 发送失败即视为断线，移除待 OnClose 兜底，
            }
        }
    }

    /**
     * 全量广播，系统公告/公司级预警，本地会话全量 + 跨实例广播，多实例下在线会话
     * 可能挂在其他实例，经 broadcaster.broadcastAll 转发，redis 模式 uid=null 广播，
     * 各实例订阅端再走 broadcastLocal 全量，自收消息由订阅端按实例 id 跳过，本实例
     * 已先行推送不重复。in-memory/no-op 模式广播器为空操作，仅本地全量。
     */
    public static void broadcast(String payload) {
        broadcastLocal(payload);
        WsNotifyBroadcaster b = broadcaster;
        if (b != null) {
            b.broadcastAll(payload);
        }
    }

    /**
     * 本地会话表全量推送，广播订阅端与本实例业务路径共用，全部在线会话逐发，
     * 失败会话静默移除，断线容错，不阻塞业务事务。
     */
    public static void broadcastLocal(String payload) {
        for (Set<Session> set : SESSIONS.values()) {
            for (Session s : set) {
                if (!sendQuietly(s, payload)) {
                    set.remove(s); // 发送失败即视为断线，移除待 OnClose 兜底，
                }
            }
        }
    }

    /** 取会话 uid，握手期由 NotifyEndpointConfigurator 写入用户属性，缺失或非法返回 null */
    private static UUID sessionUid(Session session) {
        if (session == null) {
            return null;
        }
        Object raw = session.getUserProperties().get(NotifyEndpointConfigurator.PROP_UID);
        if (!(raw instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 取会话角色，握手期由 NotifyEndpointConfigurator 写入用户属性，非白名单角色返回 null */
    private static JwtRole sessionRole(Session session) {
        if (session == null) {
            return null;
        }
        Object raw = session.getUserProperties().get(NotifyEndpointConfigurator.PROP_ROLE);
        return raw instanceof String text ? JwtRole.from(text) : null;
    }

    /** 静默发送，成功返回 true，失败记日志并返回 false */
    private static boolean sendQuietly(Session session, String payload) {
        try {
            if (session != null && session.isOpen()) {
                session.getBasicRemote().sendText(payload);
                return true;
            }
        } catch (IOException e) {
            log.warn("WebSocket 发送失败 session={}", session == null ? null : session.getId());
        }
        return false;
    }

    /** 静默关闭，握手失败/参数非法场景 */
    private static void closeQuietly(Session session, String reason) {
        try {
            if (session != null && session.isOpen()) {
                session.close(new jakarta.websocket.CloseReason(
                        jakarta.websocket.CloseReason.CloseCodes.CANNOT_ACCEPT, reason));
            }
        } catch (IOException ignored) {
            // 关闭失败无需处理，容器超时兜底，
        }
    }
}
