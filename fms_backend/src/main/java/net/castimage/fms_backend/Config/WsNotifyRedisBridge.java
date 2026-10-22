package net.castimage.fms_backend.Config;


import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import net.castimage.fms_backend.Controller.NotifyWebSocketEndpoint;
import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Utils.WsNotifyBroadcaster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * WebSocket 通知跨实例广播装配，仅 fms.session-store=redis 时生效，
 * 多后端实例下用户 ws 连接可能挂在任意实例，已读事件在业务实例推送时本地会话表
 * 无该用户，SESSIONS 为进程内静态表，经 Redis pub/sub（channel fms:ws:notify）
 * 广播，各实例订阅后查本地会话表推送。消息带发送方实例 id，自收消息跳过防重复，本地推送由 sendToUser 先行执行，广播仅服务远端实例。
 *
 * in-memory 模式装配 no-op 广播器，单实例语义，零开销。
 */
@Configuration
@Slf4j
public class WsNotifyRedisBridge {

    /** 广播 channel（与 CachePort key 命名空间同风格 fms:*） */
    static final String CHANNEL = "fms:ws:notify";

    /** 本实例 id（publish 消息身份标记，订阅端跳过自收消息防重复推送） */
    private static final String INSTANCE_ID = UuidV7.next().toString();

    /** redis 广播器，publish {"from":instanceId,"uid":uid,"payload":payload} 供各实例订阅端消费，
     * 匿名内部类，非 lambda，broadcastAll 需同时实现，lambda 只能实现单一抽象方法 */
    @Bean
    @ConditionalOnProperty(name = "fms.session-store", havingValue = "redis")
    public WsNotifyBroadcaster redisWsNotifyBroadcaster(StringRedisTemplate stringRedisTemplate) {
        WsNotifyBroadcaster broadcaster = new WsNotifyBroadcaster() {
            /** 指定用户推送，uid 带值，订阅端按用户查本地会话表 */
            @Override
            public void broadcast(UUID uid, String payload) {
                JSONObject msg = new JSONObject();
                msg.put("from", INSTANCE_ID);
                msg.put("uid", uid.toString());
                msg.put("payload", payload);
                stringRedisTemplate.convertAndSend(CHANNEL, msg.toJSONString());
            }

            /** 全量广播，uid 置 null，哨兵，订阅端改走 broadcastLocal 本地全量 */
            @Override
            public void broadcastAll(String payload) {
                JSONObject msg = new JSONObject();
                msg.put("from", INSTANCE_ID);
                msg.put("uid", null);
                msg.put("payload", payload);
                stringRedisTemplate.convertAndSend(CHANNEL, msg.toJSONString());
            }
        };

        // 装配期注入静态引用，端点由 WebSocket 容器按连接实例化，无 Spring 注入能力
        NotifyWebSocketEndpoint.setBroadcaster(broadcaster);
        return broadcaster;
    }

    /** 订阅容器，收到广播若非本实例消息则按 uid 处理，为 null 走本地全量，否则按用户查本地会话表推送，防自收重复 */
    @Bean
    @ConditionalOnProperty(name = "fms.session-store", havingValue = "redis")
    public RedisMessageListenerContainer wsNotifyListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                JSONObject msg = JSON.parseObject(
                        new String(message.getBody(), StandardCharsets.UTF_8));
                if (msg == null || INSTANCE_ID.equals(msg.getString("from"))) {
                    return; // 自收消息跳过，本地推送已由业务实例执行
                }
                String uidText = msg.getString("uid");
                String payload = msg.getString("payload");
                if (uidText == null || uidText.isBlank()) {
                    // 全量广播，系统公告/公司级预警，本实例所有在线会话全量推送
                    NotifyWebSocketEndpoint.broadcastLocal(payload);
                } else {
                    NotifyWebSocketEndpoint.sendLocal(UUID.fromString(uidText), payload);
                }
            } catch (RuntimeException e) {
                // 广播消息解析失败仅 WARN，通知推送属尽力而为，不影响订阅与业务
                log.warn("WS 广播消息解析失败（丢弃）: {}", e.getMessage());
            }
        }, new ChannelTopic(CHANNEL));
        return container;
    }

    /** 单实例（in-memory）模式，no-op 广播器，行为与改造前完全一致 */
    @Bean
    @ConditionalOnMissingBean(WsNotifyBroadcaster.class)
    public WsNotifyBroadcaster noopWsNotifyBroadcaster() {
        return (uid, payload) -> {
            // 单实例本地推送已直接送达，无需广播
        };
    }
}
