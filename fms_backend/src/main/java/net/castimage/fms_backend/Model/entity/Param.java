package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 系统参数实体（um.params）：code 唯一、value JSONB（标量参数存 JSON 标量，对象参数 {"v":...}——
 * 与 05 文件 fn_block_if_exceeded 按 jsonb_typeof 分支读取兼容）；敏感密钥（jwt.secret/pgcrypto.*）
 * 已迁 sec.app_secrets（09b 收敛），本实体只承载业务参数读写，AdminService 侧白名单禁写敏感键。
 * RLS：SELECT 需已认证上下文（current_uid IS NOT NULL），写策略仅 current_user='fms_admin'
 * （fms_operator 写入运行时 42501 映射 40300，由 DB 侧收敛，Java 预检敏感键双保险）。
 */
@TableName(schema = "um", value = "params")
@Data
public class Param {

    /** 主键（uuidv7） */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 参数编码（唯一，uk_params_code） */
    private String code;

    /** 参数值 JSONB（实体映射 String + fastjson2 解析，读写经 ::jsonb 显式转换） */
    private String value;

    /** 更新人 */
    private UUID updatedBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（触发器刷新） */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
