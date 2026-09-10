package net.castimage.fms_backend.common.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * String ↔ PG jsonb 列类型处理器（WfDefinition.nodeJson 等 JSONB 文本列）。
 *
 * 2026-08 联调实测 sqlState=42804：MyBatis-Plus BaseMapper INSERT 把 String 参数
 * 以 character varying 绑定到 jsonb 列被 PG 拒绝（'column "node_json" is of type
 * jsonb but expression is of type character varying'）。本处理器写入侧以
 * {@link PGobject}(type=jsonb) 经 pgjdbc 原生类型绑定——PG 自动按 jsonb 解析（非法
 * JSON 由 PG 侧报错兜底），读取侧 jsonb 转 text 字符串（与 DB 返回语义一致）。
 * 仅经实体字段 {@code @TableField(typeHandler=...)} 显式引用；@MappedTypes(String)
 * + @MappedJdbcTypes(OTHER) 使包扫描注册落在 (String, OTHER) 键下，不覆盖默认
 * String/VARCHAR 绑定路径（防止全局劫持其他 varchar 列）。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue(parameter);
        ps.setObject(i, json);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
