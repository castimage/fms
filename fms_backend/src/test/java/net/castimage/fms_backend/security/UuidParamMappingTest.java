package net.castimage.fms_backend.security;

import net.castimage.fms_backend.Utils.UuidV7;
import net.castimage.fms_backend.Model.entity.Department;
import net.castimage.fms_backend.common.handler.UuidTypeHandler;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * uuid 参数映射测试，注解式 Mapper 语句（{@code #{id}::uuid}）的参数映射解析路径
 * （SqlSourceBuilder 到 ParameterMapping），注册 UuidTypeHandler 后 uuid 参数
 * 映射必须可解析，未注册时 ParameterMapping.validate 抛 IllegalStateException。
 */
class UuidParamMappingTest {

    @Test
    void annotationSqlWithUuidParamParsesAfterHandlerRegistration() {
        Configuration configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());

        SqlSourceBuilder builder = new SqlSourceBuilder(configuration);
        Map<String, Object> params = new HashMap<>();
        params.put("id", UuidV7.next());
        params.put("companyId", UuidV7.next());

        // 与 DepartmentMapper.updateDepartment 同构的 sql
        assertDoesNotThrow(() -> builder.parse(
                "UPDATE org.departments SET name = #{name} WHERE id = #{id}::uuid", Department.class, params),
                "注册 UuidTypeHandler 后 UUID 参数映射必须可解析");

        org.apache.ibatis.mapping.SqlSource source = builder.parse(
                "SELECT count(*) FROM org.departments WHERE parent_id = #{id}::uuid AND deleted_at IS NULL",
                Department.class, params);
        assertNotNull(source);
    }
}
