package net.castimage.fms_backend.config;

import jakarta.annotation.PostConstruct;
import net.castimage.fms_backend.Service.ApiKeyService;
import net.castimage.fms_backend.Utils.ApiKeyFilter;
import net.castimage.fms_backend.Utils.CachePort;
import net.castimage.fms_backend.Utils.GatewaySignatureFilter;
import net.castimage.fms_backend.Utils.InMemoryCachePort;
import net.castimage.fms_backend.Utils.InMemoryNonceStore;
import net.castimage.fms_backend.Utils.NonceStore;
import net.castimage.fms_backend.Utils.PasswordUtils;
import net.castimage.fms_backend.Utils.RateLimitFilter;
import net.castimage.fms_backend.Utils.RedisCachePort;
import net.castimage.fms_backend.Utils.RedisNonceStore;
import net.castimage.fms_backend.Utils.TraceIdFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：CORS 关闭（跨域由网关统一处理，后端不注册任何 CORS 映射）、Jackson 全局时区/日期格式/省略
 * （与 application.yaml 对齐，data 字段 @JsonInclude(ALWAYS) 恒渲染）、过滤链装配
 * （TraceIdFilter order=1 /*，再到 GatewaySignatureFilter order=2 /api/*，再到 ApiKeyFilter
 * order=3 /api/*（X-API-Key 后端校验收敛点，JWT 模式已采信身份时透传）；/internal/health 免签，签名过滤器 shouldNotFilter 二次兜底）。
 * security.* 统一注入 FmsProperties 单一事实来源；CachePort/NonceStore 按 fms.session-store
 * 装配（in-memory 单实例缺省 | redis 多实例共享存储），NonceStore 挂 @Scheduled 兜底清理。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FmsProperties fmsProperties;

    public WebConfig(FmsProperties fmsProperties) {
        this.fmsProperties = fmsProperties;
    }

    /** 启动期按 fms.security.bcrypt-strength 幂等配置 BCrypt 强度（默认 10，与 SQL 种子对齐；首次生效） */
    @PostConstruct
    void configurePasswordStrength() {
        PasswordUtils.configure(fmsProperties.getSecurity().getBcryptStrength());
    }

    /**
     * CachePort Redis 实现（fms.session-store=redis 时装配）：多实例共享存储，
     * nonce 防重放/2FA 票据/TOTP pending 与失败计数/令牌黑名单全部收敛于此，
     * 任意实例写入全局可见。声明在内存实现之前：@ConditionalOnMissingBean 兜底分支
     * 须先看到本 Bean 才能正确跳过
     */
    @Bean
    @ConditionalOnProperty(name = "fms.session-store", havingValue = "redis")
    public CachePort redisCachePort(StringRedisTemplate redisTemplate) {
        return new RedisCachePort(redisTemplate);
    }

    /**
     * CachePort 内存实现（缺省 fms.session-store != redis）：单实例既有行为，
     * 无 Redis 依赖、无网络开销；redis 分支已装配时经 @ConditionalOnMissingBean 跳过
     */
    @Bean
    @ConditionalOnMissingBean(CachePort.class)
    public CachePort inMemoryCachePort() {
        return new InMemoryCachePort();
    }

    /**
     * NonceStore Redis 实现（多实例）：容量按 effectiveCapacity 推导（同内存版口径，
     * 此处仅作水位监控分母——分布式无法原子全局计数，无 fail-closed 容量闸，见
     * RedisNonceStore 类注释）；TTL=replay-window-secs×1000
     */
    @Bean
    @ConditionalOnProperty(name = "fms.session-store", havingValue = "redis")
    public NonceStore redisNonceStore(CachePort cachePort) {
        FmsProperties.Security security = fmsProperties.getSecurity();
        return new RedisNonceStore(cachePort,
                NonceStore.effectiveCapacity(security.getNonceCapacity(),
                        security.getNoncePeakQps(), security.getReplayWindowSecs()),
                security.getReplayWindowSecs() * 1000L);
    }

    /**
     * nonce 去重表内存实现（缺省，保持既有语义；挂 @Scheduled 按 fms.cache-cleanup-interval-ms
     * 兜底清理 + 水位监控）：容量/窗口来自 fms.security.*，显式 nonce-capacity 配置优先，
     * 否则按 nonce-peak-qps × replay-window-secs 动态推导（默认 1000×300=300000，
     * 6 万 QPS 可击穿 10000）
     */
    @Bean
    @ConditionalOnMissingBean(NonceStore.class)
    public NonceStore nonceStore(CachePort cachePort) {
        FmsProperties.Security security = fmsProperties.getSecurity();
        return new InMemoryNonceStore(NonceStore.effectiveCapacity(security.getNonceCapacity(),
                        security.getNoncePeakQps(), security.getReplayWindowSecs()),
                security.getReplayWindowSecs() * 1000L);
    }

    /** traceId 透传/生成（最外层，MDC + RequestContext + 响应头 X-Trace-Id） */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new TraceIdFilter());
        bean.setName("traceIdFilter");
        bean.setOrder(1); // 最外层先拿到 traceId，
        bean.addUrlPatterns("/*");
        return bean;
    }

    /** 网关签名校验（X-FMS-Signature：时间窗 + HMAC 重算含 uid/role + nonce 去重 + 身份采信） */
    @Bean
    public FilterRegistrationBean<GatewaySignatureFilter> gatewaySignatureFilter(NonceStore nonceStore) {
        FilterRegistrationBean<GatewaySignatureFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new GatewaySignatureFilter(fmsProperties.getSecurity().getSignSecret(),
                fmsProperties.getSecurity().getReplayWindowSecs(), nonceStore,
                fmsProperties.getSecurity().getMaxBodyBytes(),
                fmsProperties.getSecurity().isBypassGateway()));
        bean.setName("gatewaySignatureFilter");
        bean.setOrder(2); // 紧随 traceId 之后校验签名，
        bean.addUrlPatterns("/api/*");
        return bean;
    }

    /**
     * API-Key 校验：位于签名校验通过后、身份采信前——
     * JWT 模式身份已采信或匿名端点透传；API-Key 模式（网关不注入身份头）读 X-API-Key
     * 经 SECURITY DEFINER 函数闸口校验 um.api_keys，通过后按 key.user_id + scope 注入
     * 身份，任何失败 40100 fail-closed。
     */
    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeyService apiKeyService) {
        FilterRegistrationBean<ApiKeyFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new ApiKeyFilter(apiKeyService, fmsProperties.getSecurity().isBypassGateway()));
        bean.setName("apiKeyFilter");
        bean.setOrder(3); // 紧随网关签名校验（order=2）之后、Controller 之前，
        bean.addUrlPatterns("/api/*");
        return bean;
    }

    /**
     * 后端补充限流（纵深防御，order=4）：网关已有首道限流，本过滤器覆盖绕过网关直连
     * 后端的三个敏感入口（login/recovery 匿名爆破、/internal/log-batch 日志注入泛滥）——
     * /internal/log-batch 在签名过滤器（urlPatterns=/api/*）覆盖之外，须以独立 urlPatterns
     * 注册；不重复限流业务 /api/** 全量（避免与网关双倍影响）。滑动窗口计数经 CachePort
     * （redis 模式全局计数，多实例共享）。
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(CachePort cachePort) {
        FilterRegistrationBean<RateLimitFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new RateLimitFilter(cachePort, fmsProperties));
        bean.setName("rateLimitFilter");
        bean.setOrder(4); // 过滤链：依次 TraceId(1)、Signature(2)、ApiKey(3)、RateLimit(4)
        bean.addUrlPatterns("/api/auth/login", "/api/auth/recovery", "/internal/log-batch");
        return bean;
    }
}
