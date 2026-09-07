package net.castimage.fms_backend.Model.vo;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 系统参数行视图（GET /api/admin/params；MyBatis 结果映射，组件名与列别名 camelCase 一致）：
 * value JSONB 经 ::text 转字符串映射（标量参数为 JSON 标量文本，对象参数 {"v":...} 原样透传）。
 */
public record ParamView(UUID id, String code, String value,
                        UUID updatedBy, LocalDateTime createdAt, LocalDateTime updatedAt) { // 敏感键不进视图，
}
