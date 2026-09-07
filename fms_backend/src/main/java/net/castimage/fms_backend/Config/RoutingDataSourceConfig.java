package net.castimage.fms_backend.config;

import com.zaxxer.hikari.HikariDataSource;
import net.castimage.fms_backend.Utils.RoutingDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据源装配，读写分离，从库只读分流，
 * <ul>
 *   <li>enabled=false，单机/多机形态，直接返回主写源，与 Boot 自动装配行为一致，
 *       零路由开销，本配置替代自动装配，DataSourceAutoConfiguration 条件缺失跳过，</li>
 *   <li>enabled=true，集群形态，RoutingDataSource 包装 写源 + 只读源，readOnly=true
 *       事务路由到只读 url（pgbouncer-ro 指向从库），其余走主库。</li>
 * </ul>
 * 两源共用同一连接角色（fms_app）与沙箱语义，connection-init-sql=RESET ALL、
 * auto-commit=false，SandboxSessionInterceptor 每语句 SET ROLE 在从库同样生效，流复制携带数据不带会话，rls 策略照常按注入 guc 收敛。
 */
@Configuration
public class RoutingDataSourceConfig {

    /** 主库连接 url（application.yaml spring.datasource.url） */
    @Value("${spring.datasource.url}")
    private String writeUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    /**
     * 路由数据源（@Primary 替代 Boot 自动装配，MyBatis-Plus/事务管理器/健康检查统一注入），
     * 禁用时直接返回写源，不实例化 RoutingDataSource。
     */
    @Bean
    @Primary
    public DataSource routingDataSource(FmsProperties fmsProperties) {
        HikariDataSource write = buildHikari(writeUrl, "fms-hikari", 10);
        FmsProperties.DatasourceRo ro = fmsProperties.getDatasourceRo();
        if (!ro.isEnabled()) {
            return write;
        }

        String readUrl = (ro.getUrl() == null || ro.getUrl().isBlank()) ? writeUrl : ro.getUrl();
        RoutingDataSource routing = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(RoutingDataSource.WRITE, write);
        targets.put(RoutingDataSource.READ, buildHikari(readUrl, "fms-hikari-ro", 10));
        routing.setDefaultTargetDataSource(write);
        routing.setTargetDataSources(targets);
        routing.afterPropertiesSet();
        return routing;
    }

    /** 构建 Hikari 池，与 application.yaml spring.datasource.hikari 同款参数，RESET ALL 防沙箱残留、auto-commit=false */
    private HikariDataSource buildHikari(String url, String poolName, int maxPoolSize) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        ds.setPoolName(poolName);
        ds.setMaximumPoolSize(maxPoolSize);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30_000);
        ds.setIdleTimeout(600_000);
        ds.setMaxLifetime(1_800_000);
        ds.setConnectionInitSql("RESET ALL");
        ds.setAutoCommit(false);
        return ds;
    }
}
