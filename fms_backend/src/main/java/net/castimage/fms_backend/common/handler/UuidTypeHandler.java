package net.castimage.fms_backend.common.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * UUID 类型处理器（PG uuid 列）。
 *
 * MyBatis 3.5.x 默认注册表不含 UUID 处理器，
 * 注解式 Mapper 语句（如
 * {@code #{id}::uuid}）解析参数映射时因「Type handler was null … javaType
 * (UUID)」直接抛异常，导致 SqlSessionFactory/应用上下文启动失败。
 * 本处理器经 {@code mybatis-plus.type-handlers-package} 全局注册：
 * 参数侧以 {@link Types#OTHER} 绑定（pgjdbc 原生识别 UUID），
 * 结果侧兼容 PG 返回的 UUID 或字符串形式。
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUuid(rs.getObject(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUuid(rs.getObject(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUuid(cs.getObject(columnIndex));
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }
}
