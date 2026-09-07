package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 站内通知行视图（GET /api/notify/list 分页；MyBatis 结果映射，组件名与列别名 camelCase 一致）：
 * 本人可见（RLS + Java user_id 过滤双保险）；isRead=false 行供未读数与列表角标。
 */
public record NotificationView(UUID id, String title, String content, String type,
                               String refType, UUID refId, Boolean isRead,
                               LocalDateTime createdAt, LocalDateTime readAt) { // 未读数 = NOT is_read 计数，
}
