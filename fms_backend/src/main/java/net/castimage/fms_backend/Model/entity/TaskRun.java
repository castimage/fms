package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 定时任务执行记录实体（log.task_runs，仅追加）：pg_cron 任务（07 文件）执行结果统一落库
 * （close-check 汇总、audit-chain-check 哈希链校验结果等）；status OK/FAIL（ck_task_runs_status），
 * detail JSONB（实体映射 String）；FORCE RLS + 仅 SELECT 策略，写入由 pg_cron/超级用户完成——本实体只读。
 */
@TableName(schema = "log", value = "task_runs")
@Data
public class TaskRun {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 任务名（job_name，如 close-check/audit-chain-check） */
    private String jobName;

    /** 状态：OK/FAIL */
    private String status;

    /** 结果详情 JSONB（String 透传） */
    private String detail;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间（NULL=运行中/异常中断） */
    private LocalDateTime finishedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

}
