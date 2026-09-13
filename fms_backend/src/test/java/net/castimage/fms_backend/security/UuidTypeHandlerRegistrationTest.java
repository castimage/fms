package net.castimage.fms_backend.security;

import net.castimage.fms_backend.common.handler.UuidTypeHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * uuid 类型处理器测试，
 *
 * MyBatis 3.5.19 默认注册表对 {@code javaType=uuid, jdbcType=null} 返回 null
 * （注解式 Mapper 的 {@code #{id}} 参数映射会抛 "Type handler was null"，阻塞启动），
 * 本测试固化了该前提，防止未来升级 MyBatis 后误删 UuidTypeHandler 注册。
 * 注册 UuidTypeHandler 后，SqlSourceBuilder 所用的
 * {@code getTypeHandler(uuid.class, null)} 必须命中，且命中实例为本处理器。
 */
class UuidTypeHandlerRegistrationTest {

    @Test
    void defaultRegistryHasNoUuidHandler() {
        // 前提固化，缺省注册表不含 uuid 处理器（否则 UuidTypeHandler 与
        // mybatis-plus.type-handlers-package 注册即为冗余）
        Configuration configuration = new Configuration();
        assertNull(configuration.getTypeHandlerRegistry().getTypeHandler(UUID.class, null));
    }

    @Test
    void registeredHandlerResolvesForUuidJavaType() {
        Configuration configuration = new Configuration();
        UuidTypeHandler handler = new UuidTypeHandler();
        configuration.getTypeHandlerRegistry().register(handler);

        Object resolved = configuration.getTypeHandlerRegistry().getTypeHandler(UUID.class, null);
        assertNotNull(resolved, "注册后 UUID+null jdbcType 必须可解析（注解式 Mapper 参数映射依赖）");
        assertSame(handler, resolved, "解析结果应为 UuidTypeHandler 实例");
    }
}
