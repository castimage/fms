package net.castimage.fms_backend.Utils;

import java.util.UUID;

/**
 * WebSocket 通知跨实例广播器（多实例支撑）：业务实例产生推送事件（已读/全已读后
 * 未读数刷新）时，本地会话表（NotifyWebSocketEndpoint.SESSIONS 为进程内静态表）
 * 只覆盖本实例连接——用户连接挂在 A 实例、事件发生在 B 实例时推送会丢失。
 * 本接口将推送事件跨实例转发：redis 实现经 pub/sub（channel fms:ws:notify）
 * 广播，各实例订阅后查本地会话表推送；in-memory 实现为 no-op（单实例语义不变）。
 * 装配：fms.session-store=redis 时 RedisWsNotifyBroadcaster（WsNotifyRedisBridge），
 * 否则 Noop——NotifyWebSocketEndpoint 静态引用在 WebConfig 装配时注入。
 */
public interface WsNotifyBroadcaster {

    /** 向指定用户广播推送事件（redis 实现 publish；本地推送由调用方先行执行） */
    void broadcast(UUID uid, String payload);

    /**
     * 全量广播（系统公告/公司级预警）：redis 实现 publish 且 uid 置 null（订阅端据此
     * 走本地全量推送）；no-op 缺省实现——单实例（in-memory）或未装配广播器时，
     * NotifyWebSocketEndpoint.broadcast 的本地全量已覆盖全部在线会话，无需转发。
     * 本批业务路径（NotifyWebSocketEndpoint.broadcast）调用；非抽象以兼容
     * lambda 装配（实现类仅需实现 broadcast）。
     */
    default void broadcastAll(String payload) {
        // 缺省空操作：未启用跨实例广播时本地全量推送已足够
    }
}
