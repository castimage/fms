package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 会计期间视图，取自 gl.periods 实际落库行，未创建的月份不在返回中，
 * 前端按所选年补齐 12 个月并以后端状态为准覆盖
 */
public record PeriodView(String period, String status, UUID closedBy, LocalDateTime closedAt) { // OPEN / CLOSED
}
