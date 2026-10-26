package net.castimage.fms_backend.Service;

import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.vo.NotificationView;
import java.util.UUID;

/** 通知模块业务接口（notify）：本人通知分页/未读数/已读标记 + WebSocket 实时推送未读数
 * （连接建立与已读动作后经 NotifyWebSocketHub 推送 {"type":"unread_count","count":N}）
 * + 系统公告全量广播（ADMIN 专属，SECURITY DEFINER 批量 INSERT + 全量 WS 推送） */
public interface NotifyService {

    /**
     * 本人通知分页（isRead 可选过滤；身份取 RequestContext，RLS 兜底）。
     */
    PageResult<NotificationView> pageList(long page, long size, Boolean isRead);

    /**
     * 系统公告广播（仅 ADMIN）：通知 RLS 的 INSERT WITH CHECK 要求 user_id =
     * auth.current_uid()，批量插入（向全部 ACTIVE 用户逐行 INSERT）必须经 SECURITY
     * DEFINER 函数 notify.fn_broadcast_notification 绕过；幂等（同 ref_type+ref_id+
     * title 已存在则跳过，防 cron/重试重复）；成功后全量 WS 推送（本地 + 跨实例）。
     *
     * @param title   公告标题
     * @param content 公告正文
     * @param refType 关联类型（公告固定 'ANNOUNCEMENT'；预算预警 'budget_warning' 等）
     * @param refId   关联 id（可空）
     */
    void broadcast(String title, String content, String refType, UUID refId);

    /**
     * 指定用户未读通知数（角标/WebSocket 推送数据源；uid 为连接注册的 canonical uuid，
     * WebSocket 线程无 RequestContext，由调用方显式传入）。
     */
    long unreadCount(UUID userId);

    /**
     * 单条已读（归属校验：非本人/不存在 40400；已读后推送最新未读数）。
     */
    void markRead(UUID id);

    /**
     * 全部已读（仅未读行幂等置读；返回置读行数，随后推送未读数 0）。
     */
    int markAllRead();
}
