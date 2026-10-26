package net.castimage.fms_backend.Controller;

import jakarta.validation.Valid;
import net.castimage.fms_backend.Model.ApiResponse;
import net.castimage.fms_backend.Model.PageResult;
import net.castimage.fms_backend.Model.dto.AnnouncementRequest;
import net.castimage.fms_backend.Model.vo.NotificationView;
import net.castimage.fms_backend.Service.NotifyService;
import net.castimage.fms_backend.Utils.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import java.util.UUID;

/**
 * 通知模块 REST 接口（/api/notify）：本人通知列表/未读数/单条已读/全部已读 +
 * 系统公告广播（/announcements，仅 ADMIN，成功后全量 WS 推送）；
 * WebSocket 端点为 /api/nt/ws（见 NotifyWebSocketHandler，连接即推送未读数，
 * 已读动作后增量推送）。身份取 RequestContext（网关签名校验采信），RLS user_id 兜底。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notify")
public class NotifyController {

    private final NotifyService notifyService;


    /**
     * 系统公告广播（仅 ADMIN）：向全部 ACTIVE 用户插一条 SYSTEM 通知（SECURITY DEFINER
     * 批量 INSERT，幂等防重复），成功后经 WS 全量推送 {"type":"announcement","title":...}
     * （本地全量 + 跨实例广播）。
     */
    @PostMapping("/announcements")
    public ApiResponse<Void> announce(@RequestBody @Valid AnnouncementRequest req) {
        notifyService.broadcast(req.title(), req.content(), "ANNOUNCEMENT", null);
        return ApiResponse.ok(null);
    }

    /**
     * 本人通知分页（isRead 可选过滤）。
     */
    @GetMapping("/list")
    public ApiResponse<PageResult<NotificationView>> list(@RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "20") long size,
                                                          @RequestParam(required = false) Boolean isRead) {
        return ApiResponse.ok(notifyService.pageList(page, size, isRead));
    }

    /**
     * 本人未读通知数（角标/WebSocket 初始化）。
     */
    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(notifyService.unreadCount(RequestContext.requireUserId()));
    }

    /**
     * 单条已读（非本人/不存在 40400；已读后 WebSocket 推送最新未读数）。
     */
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable UUID id) {
        notifyService.markRead(id);
        return ApiResponse.ok(null);
    }

    /**
     * 全部已读（幂等；返回本次置读行数）。
     */
    @PostMapping("/read-all")
    public ApiResponse<Integer> markAllRead() {
        return ApiResponse.ok(notifyService.markAllRead());
    }
}
