package net.castimage.fms_backend.Service.impl;

import net.castimage.fms_backend.Utils.WsNotifyBroadcaster;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知 WebSocket 会话容器测试，覆盖同用户多端发送、跨实例广播、注销移除与发送失败清理。
 */
class NotifyWebSocketHubTest {

    private final WsNotifyBroadcaster broadcaster = mock(WsNotifyBroadcaster.class);
    private final NotifyWebSocketHub hub = new NotifyWebSocketHub(broadcaster);

    @Test
    void sendLocalDeliversToEverySessionOfUser() throws Exception {
        UUID uid = UUID.randomUUID();
        WebSocketSession first = session("s1");
        WebSocketSession second = session("s2");
        hub.register(uid, first);
        hub.register(uid, second);

        hub.sendLocal(uid, "{\"type\":\"unread_count\",\"count\":2}");

        assertEquals("{\"type\":\"unread_count\",\"count\":2}", payload(first));
        assertEquals("{\"type\":\"unread_count\",\"count\":2}", payload(second));
    }

    @Test
    void sendToUserAlsoBroadcastsToOtherInstances() throws Exception {
        UUID uid = UUID.randomUUID();
        WebSocketSession session = session("s1");
        hub.register(uid, session);

        hub.sendToUser(uid, "payload");

        verify(session).sendMessage(any(TextMessage.class));
        verify(broadcaster).broadcast(uid, "payload");
    }

    @Test
    void broadcastReachesAllSessionsAndBroadcastsAll() throws Exception {
        WebSocketSession first = session("s1");
        WebSocketSession second = session("s2");
        hub.register(UUID.randomUUID(), first);
        hub.register(UUID.randomUUID(), second);

        hub.broadcast("announcement");

        assertEquals("announcement", payload(first));
        assertEquals("announcement", payload(second));
        verify(broadcaster).broadcastAll("announcement");
    }

    @Test
    void unregisterStopsDelivery() throws Exception {
        UUID uid = UUID.randomUUID();
        WebSocketSession session = session("s1");
        hub.register(uid, session);

        hub.unregister(session);
        hub.sendLocal(uid, "payload");

        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void failedSendRemovesSession() throws Exception {
        UUID uid = UUID.randomUUID();
        WebSocketSession session = session("s1");
        hub.register(uid, session);
        doThrow(new IOException("closed")).when(session).sendMessage(any(TextMessage.class));

        hub.sendLocal(uid, "first");
        clearInvocations(session);
        hub.sendLocal(uid, "second");

        verify(session, never()).sendMessage(any(TextMessage.class));
        verify(broadcaster, never()).broadcast(uid, "second");
    }

    /** 并发注册、发送、注销时不丢已注册会话 */
    @Test
    void concurrentRegisterAndUnregisterNeverDropsSession() throws Exception {
        int threads = 4;
        int iterations = 200;
        UUID uid = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        WebSocketSession session = session("s-" + thread + "-" + i);
                        hub.register(uid, session);
                        hub.sendLocal(uid, "probe");
                        verify(session, timeout(1_000).atLeastOnce())
                                .sendMessage(any(TextMessage.class));
                        hub.unregister(session);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static String payload(WebSocketSession session) throws IOException {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return captor.getValue().getPayload();
    }
}
