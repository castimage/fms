package net.castimage.fms_backend.Service.impl;

import net.castimage.fms_backend.Controller.NotifyWebSocketEndpoint;
import net.castimage.fms_backend.Mapper.NotificationMapper;
import net.castimage.fms_backend.Service.NotifyService;
import net.castimage.fms_backend.Utils.RequestContext;
import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.config.FmsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知队列消费者测试，钉住两类容易被改坏的语义：持久效果落库后立即归档，WS 推送排在归档之后；
 * 推送异常属尽力而为，既不上抛也不触发消费失败处理（否则消息回投会因通知插入无幂等键而重复落库）。
 *
 * 推送走 NotifyWebSocketEndpoint 静态入口，故用静态 mock；读队列按 pgmq 约定返回 msg_id 与 read_ct。
 */
class NotifyQueueConsumerTest {

    /** 与消费者内常量一致：vt 30 秒、每轮 50 条 */
    private static final int VISIBILITY_TIMEOUT_SECS = 30;

    private static final int BATCH_SIZE = 50;

    private NotificationMapper notificationMapper;
    private NotifyService notifyService;
    private FmsProperties props;
    private NotifyQueueConsumer consumer;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(NotificationMapper.class);
        notifyService = mock(NotifyService.class);
        props = new FmsProperties();
        consumer = new NotifyQueueConsumer(notificationMapper, notifyService, props);
        when(notificationMapper.pgmqAvailable()).thenReturn(true);
        when(notificationMapper.readQueue(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(notificationMapper.archiveMessage(anyString(), anyLong())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /** 队列行，键名与 pgmq.read 返回的 record 一致 */
    private Map<String, Object> row(long msgId, int readCt, String message) {
        return Map.of("msg_id", msgId, "read_ct", readCt, "message", message);
    }

    private String taskMessage(UUID assigneeId) {
        return "{\"type\":\"wf_task\",\"task_id\":\"" + UuidV7.next() + "\",\"assignee_id\":\""
                + assigneeId + "\"}";
    }

    private String budgetMessage(UUID companyId) {
        return "{\"company_id\":\"" + companyId + "\",\"ratio\":\"0.92\",\"period\":\"2026-08\"}";
    }

    @Test
    void userQueuePersistsThenArchivesThenPushes() {
        UUID assignee = UuidV7.next();
        when(notificationMapper.readQueue("q_notify_task", VISIBILITY_TIMEOUT_SECS, BATCH_SIZE))
                .thenReturn(List.of(row(7L, 1, taskMessage(assignee))));
        when(notifyService.unreadCount(assignee)).thenReturn(3L);
        try (MockedStatic<NotifyWebSocketEndpoint> ws = mockStatic(NotifyWebSocketEndpoint.class)) {
            consumer.consume();
            InOrder order = inOrder(notificationMapper, notifyService);
            order.verify(notificationMapper).insertNotification(eq(assignee), anyString(), anyString(),
                    eq("SYSTEM"), eq("wf_task"), any());
            order.verify(notificationMapper).archiveMessage("q_notify_task", 7L);
            order.verify(notifyService).unreadCount(assignee);
            ws.verify(() -> NotifyWebSocketEndpoint.sendToUser(assignee,
                    "{\"type\":\"unread_count\",\"count\":3}"));
        }
    }

    @Test
    void pushFailureNeitherPropagatesNorCountsAsConsumptionFailure() {
        // readCt 达重试阈值时，若推送异常被当成消费失败，handleFailure 会再归档一次
        UUID assignee = UuidV7.next();
        when(notificationMapper.readQueue("q_notify_task", VISIBILITY_TIMEOUT_SECS, BATCH_SIZE))
                .thenReturn(List.of(row(8L, props.getPgmq().getRetryThreshold(), taskMessage(assignee))));
        when(notifyService.unreadCount(assignee)).thenThrow(new RuntimeException("redis 不可达"));
        try (MockedStatic<NotifyWebSocketEndpoint> ws = mockStatic(NotifyWebSocketEndpoint.class)) {
            assertThatCode(() -> consumer.consume()).doesNotThrowAnyException();
            verify(notificationMapper, times(1)).archiveMessage("q_notify_task", 8L);
            ws.verifyNoInteractions();
        }
    }

    @Test
    void persistFailureSkipsPushAndGivesUpAtRetryThreshold() {
        UUID assignee = UuidV7.next();
        when(notificationMapper.readQueue("q_notify_task", VISIBILITY_TIMEOUT_SECS, BATCH_SIZE))
                .thenReturn(List.of(row(9L, props.getPgmq().getRetryThreshold(), taskMessage(assignee))));
        when(notificationMapper.insertNotification(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("落库失败"));
        try (MockedStatic<NotifyWebSocketEndpoint> ws = mockStatic(NotifyWebSocketEndpoint.class)) {
            consumer.consume();
            verify(notifyService, never()).unreadCount(any());
            verify(notificationMapper, times(1)).archiveMessage("q_notify_task", 9L);
            ws.verifyNoInteractions();
        }
    }

    @Test
    void budgetQueuePersistsThenArchivesThenBroadcasts() {
        UUID company = UuidV7.next();
        when(notificationMapper.readQueue("q_budget_warning", VISIBILITY_TIMEOUT_SECS, BATCH_SIZE))
                .thenReturn(List.of(row(11L, 1, budgetMessage(company))));
        try (MockedStatic<NotifyWebSocketEndpoint> ws = mockStatic(NotifyWebSocketEndpoint.class)) {
            consumer.consume();
            InOrder order = inOrder(notificationMapper);
            order.verify(notificationMapper).broadcastNotification(anyString(), anyString(),
                    eq("budget_warning"), eq(company));
            order.verify(notificationMapper).archiveMessage("q_budget_warning", 11L);
            ws.verify(() -> NotifyWebSocketEndpoint.broadcast(anyString()));
        }
    }
}
