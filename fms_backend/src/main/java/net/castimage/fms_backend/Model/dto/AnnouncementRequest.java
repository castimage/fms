package net.castimage.fms_backend.Model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 系统公告广播请求（POST /api/notify/announcements，仅 ADMIN）：title/content 与
 * notify.notifications 列约束对齐（title varchar(128)，content text 上限 4000 防超大）。
 */
public record AnnouncementRequest(
        /** 公告标题（1-128 字，幂等键组成部分：同标题+ref 不重复广播） */
        @NotBlank(message = "公告标题不能为空")
        @Size(max = 128, message = "公告标题最长 128 字")
        String title,
        /** 公告正文（≤4000 字，可空） */
        @Size(max = 4000, message = "公告内容过长")
        String content) {
}
