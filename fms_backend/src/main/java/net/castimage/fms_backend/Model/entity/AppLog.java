package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 应用日志实体（log.app_logs，仅追加）：结构化日志按天分区（分区父表声明，partman 配套管理），
 * context JSONB 快照（实体映射 String + fastjson2 解析）；FORCE RLS + 仅 SELECT 策略
 * （current_uid() IS NOT NULL），写入仅经 SECURITY DEFINER 消费函数/超级用户——本实体只读。
 */
@TableName(schema = "log", value = "app_logs")
@Data
public class AppLog {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 级别：DEBUG/INFO/WARN/ERROR（ck_app_logs_level） */
    private String level;

    /** 日志器名（logger 定位） */
    private String logger;

    /** 消息 */
    private String message;

    /** 上下文 JSONB（String 透传） */
    private String context;

    /** 发生时间（timestamptz，分区键 NOT NULL） */
    private LocalDateTime occurredAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

}
