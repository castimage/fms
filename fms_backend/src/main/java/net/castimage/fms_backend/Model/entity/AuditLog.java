package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审计日志实体（audit.audit_log，仅追加）：prev_hash/row_hash 构成 sha256 哈希链（篡改任何一行即断链）；
 * old_values/new_values 为 JSONB 快照（实体映射 String + fastjson2 解析）；ip 为 inet 列（实体映射 String）。
 * 本实体只承载只读查询（查询/导出/哈希链校验），写入仅经 DB 触发器与 audit.record（SECURITY DEFINER），
 * 表级不可变触发器 + FORCE RLS 硬拒 UPDATE/DELETE。
 */
@TableName(schema = "audit", value = "audit_log")
@Data
public class AuditLog {

    /** 主键（uuidv7，天然时间序——哈希链逐行顺序依赖 id 与写入序一致） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 操作人（fms.user_id GUC 注入，未注入为 NULL） */
    private UUID actorId;

    /** 动作：INSERT/UPDATE/DELETE 或业务动作（voucher_post/period_close 等） */
    private String action;

    /** 目标表（schema.table 全名） */
    private String tableName;

    /** 目标记录 id */
    private UUID recordId;

    /** 变更前快照 JSONB（String 透传） */
    private String oldValues;

    /** 变更后快照 JSONB（String 透传） */
    private String newValues;

    /** 来源 IP（inet 列，String 透传） */
    private String ip;

    /** 请求关联 ID（网关透传） */
    private String requestId;

    /** 发生时间（timestamptz，Asia/Shanghai 本地化） */
    private LocalDateTime occurredAt;

    /** 上一行 row_hash（链首为 '0'×64） */
    private String prevHash;

    /** 本行哈希（sha256 链哈希，触发器计算） */
    private String rowHash;

}
