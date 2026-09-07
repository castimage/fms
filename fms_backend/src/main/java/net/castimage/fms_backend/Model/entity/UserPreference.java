package net.castimage.fms_backend.Model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import net.castimage.fms_backend.common.handler.JsonbTypeHandler;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户级 UI 偏好实体（um.user_preferences）：background 背景配置 JSONB
 * （{enabled,imageUrl,fit,blur,darken,desktop/tablet/mobile 断点档覆盖}，实体映射
 * String + JsonbTypeHandler 防 42804 jsonb 类型不匹配）；user_id 即主键（RLS 本人行
 * 策略，四职责角色一致可读写——后端"仅操作自己" + DB RLS 双层收敛）。
 */
@TableName(schema = "um", value = "user_preferences")
@Data
public class UserPreference {

    /** 用户 id（主键；RLS 限定本人行，admin 亦不可跨用户查看） */
    @TableId(value = "user_id", type = IdType.INPUT)
    private UUID userId;

    /** 背景配置 JSONB（String 透传，读写经 JsonbTypeHandler 显式绑定 jsonb 类型） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String background;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    private LocalDateTime deletedAt;

}
