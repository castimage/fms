package net.castimage.fms_backend.Config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import net.castimage.fms_backend.Security.SandboxSessionInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：注册沙箱会话注入与分页插件（PostgreSQL 方言，支持 ltree/JSONB 等 PG 特性）。
 * 沙箱必须注册在前：PaginationInnerInterceptor 的 COUNT 子查询经原始 executor 直接执行（不走拦截器链），
 * 若沙箱后注册，COUNT 会以 fms_app 空壳角色运行则 42501 permission denied（sqlState=42501）。
 */
@Configuration
public class MybatisPlusConfig {

    /** 先沙箱会话注入，后分页，保证 COUNT 子查询执行时沙箱上下文已就位 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new SandboxSessionInterceptor()); // 先注入 auth.set_session + SET ROLE，
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
