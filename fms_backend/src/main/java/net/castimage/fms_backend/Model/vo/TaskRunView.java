package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 定时任务执行记录行视图（GET /api/admin/task-runs 分页；MyBatis 结果映射）：detail JSONB 经
 * ::text 映射 String；status OK/FAIL，供运行监控与审计。
 */
public record TaskRunView(UUID id, String jobName, String status, String detail,
                          LocalDateTime startedAt, LocalDateTime finishedAt) { // started_at 排序，
}
