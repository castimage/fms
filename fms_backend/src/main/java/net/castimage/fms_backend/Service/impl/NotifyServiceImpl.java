package net.castimage.fms_backend.Service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.castimage.fms_backend.Controller.NotifyWebSocketEndpoint;
import net.castimage.fms_backend.Exception.NotFoundException;
import net.castimage.fms_backend.Exception.UnauthorizedException;
import net.castimage.fms_backend.Mapper.NotificationMapper;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.vo.NotificationView;
import net.castimage.fms_backend.Service.NotifyService;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import java.util.UUID;

/**
 * 通知模块业务实现：身份一律取 RequestContext（网关签名校验采信），SQL 恒带 user_id 过滤
 * （RLS 按 auth.current_uid() = user_id 兜底，双层防御）；已读为原子 UPDATE（WHERE user_id
 * 归属校验，0 行则 40400）；已读动作后经 NotifyWebSocketEndpoint 推送本人最新未读数
 * （JSON {"type":"unread_count","count":N}，实时角标刷新）。
 */
@Service
@RequiredArgsConstructor
public class NotifyServiceImpl implements NotifyService {

    private final NotificationMapper notificationMapper;


    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PageResult<NotificationView> pageList(long page, long size, Boolean isRead) {
        long pageNum = Math.max(page, 1);
        long sizeNum = Math.clamp(size, 1, 100);
        return PageResult.of(notificationMapper.selectUserPage(new Page<>(pageNum, sizeNum),
                RequestContext.requireUserId(), isRead));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        // WebSocket 线程无 RequestContext，身份由调用方（连接注册 uid）显式传入；
        // HTTP 路径（NotifyController）经 RequestContext.requireUserId() 取值后转发
        if (userId == null) {
            throw new UnauthorizedException("未认证");
        }
        return notificationMapper.selectUnreadCount(userId);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void markRead(UUID id) {
        UUID userId = RequestContext.requireUserId();
        if (notificationMapper.markRead(id, userId) == 0) {
            throw new NotFoundException("通知不存在或无权操作");
        }
        // 已读后推送本人最新未读数（连接建立时同样推送一次）
        NotifyWebSocketEndpoint.sendToUser(userId, unreadPayload(userId));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public int markAllRead() {
        UUID userId = RequestContext.requireUserId();
        int affected = notificationMapper.markAllRead(userId);
        NotifyWebSocketEndpoint.sendToUser(userId, unreadPayload(userId));
        return affected;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void broadcast(String title, String content, String refType, UUID refId) {
        // 权限闸：init/notify_base.sql 未定义通知广播权限点，按角色收敛——仅 ADMIN 可全量广播
        // （通知是全量触达通道，误用即全员打扰；角色闸 + RLS 双层防御）
        RequestContext.requireAdminRole();
        // SECURITY DEFINER 批量插入（RLS INSERT WITH CHECK user_id=current_uid() 需绕过；
        // 幂等：同 ref_type+ref_id+title 已存在则整批跳过，防 cron/重试重复）
        notificationMapper.broadcastNotification(title, content, refType, refId);
        // 全量 WS 推送：本地全量 + 跨实例广播（uid=null 哨兵，远端实例订阅端本地全量）
        NotifyWebSocketEndpoint.broadcast(JSONObject.of(
                "type", "announcement", "title", title).toJSONString());
    }

    /** 未读数推送载荷（JSON 文本） */
    private String unreadPayload(UUID userId) {
        return "{\"type\":\"unread_count\",\"count\":" + notificationMapper.selectUnreadCount(userId) + "}";
    }
}
