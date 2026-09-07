package net.castimage.fms_backend.common.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.Date;

/**
 * LocalDateTime 类型处理器（PG timestamptz 列全局兼容）。
 *
 * <p>PG 时间戳列统一为 {@code timestamp with time zone}（2026-08 联调实测
 * gl.vouchers/um.users 等表 created_at/approved_at/posted_at 均为 timestamptz，
 * 服务器 session timezone=UTC），而应用实体/视图字段为 {@link LocalDateTime}。
 * pgjdbc 42.7.x 对 timestamptz 列调用 {@code getObject(..., LocalDateTime.class)}
 * 会直接抛 42821 "cannot convert the column of type timestamptz to requested type
 * LocalDateTime"（联调实测 sqlState=42821），应用默认 LocalDateTimeTypeHandler
 * 无法读取。本处理器经 {@code mybatis-plus.type-handlers-package} 全局注册
 * （javaType=LocalDateTime 且未声明 @MappedJdbcTypes，注册在 null jdbcType 键下，
 * 自动映射场景优先命中，不覆盖显式 TIMESTAMP 绑定场景）：</p>
 * <ul>
 *   <li>读取：timestamptz 转 OffsetDateTime（pgjdbc 原生），再转 {@code Asia/Shanghai}
 *       本地时间（与 application.yaml jackson time-zone: Asia/Shanghai 一致）；</li>
 *   <li>写回：{@code ps.setObject(LocalDateTime)}（pgjdbc 按 session TZ 转换，与 DB 默认
 *       now()/UTC 语义一致；应用写路径均由 PG 函数/触发器取 now()，不受影响）。</li>
 * </ul>
 */
@MappedTypes(LocalDateTime.class)
public class LocalDateTimeTzTypeHandler extends BaseTypeHandler<LocalDateTime> {

    /** 应用展示时区（与 jackson time-zone 对齐） */
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter);
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toAppLocal(rs.getObject(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toAppLocal(rs.getObject(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toAppLocal(cs.getObject(columnIndex));
    }

    /** 兼容 timestamptz（OffsetDateTime/Instant）与朴素 timestamp（Timestamp/LocalDateTime） */
    private static LocalDateTime toAppLocal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.atZoneSameInstant(APP_ZONE).toLocalDateTime();
        }
        if (value instanceof Instant instant) {
            return instant.atZone(APP_ZONE).toLocalDateTime();
        }
        if (value instanceof Timestamp ts) {
            // 朴素 timestamp（无时区）：按列值原样（session TZ 已由 pgjdbc/DB 解释）
            return ts.toLocalDateTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Date date) {
            return OffsetDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC)
                    .atZoneSameInstant(APP_ZONE).toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value));
    }
}
