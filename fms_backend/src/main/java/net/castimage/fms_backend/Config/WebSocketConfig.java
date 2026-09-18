package net.castimage.fms_backend.config;

import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import net.castimage.fms_backend.Controller.NotifyWebSocketEndpoint;
import net.castimage.fms_backend.Service.NotifyService;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WebSocket 端点注册（JSR-356，内嵌 Tomcat）：项目未引入 spring-boot-starter-websocket
 * （Boot 4 starter-tomcat 自带 tomcat-embed-websocket，jakarta.websocket API 随包提供），
 * 故不走 Spring WebSocketHandler 体系，直接向内嵌 Tomcat ServerContainer 注册
 * NotifyWebSocketEndpoint（ServletContextInitializer 在启动期执行，确定性注册，
 * 不依赖 WsSci 注解扫描）；同时注入 NotifyService 静态引用供端点推送未读数。
 */
@Configuration
public class WebSocketConfig {

    /**
     * 注册通知 WebSocket 端点：从 ServletContext 属性取 ServerContainer（属性名
     * ServerContainer.class.getName()，Tomcat 启动时注入），addEndpoint 注册
     * {@code /api/nt/ws}（与网关的升级判定路径一致）；容器缺失（非 Tomcat 环境）时静默跳过，
     * 不阻断启动。
     */
    @Bean
    public ServletContextInitializer notifyWebSocketRegistration(NotifyService notifyService) {
        // 静态引用注入：端点由 WebSocket 容器按连接实例化（无 Spring 注入能力），启动期一次性注入
        NotifyWebSocketEndpoint.setNotifyService(notifyService);
        return context -> {
            Object container = context.getAttribute(ServerContainer.class.getName());
            if (container instanceof ServerContainer sc) {
                try {
                    sc.addEndpoint(NotifyWebSocketEndpoint.class);
                } catch (DeploymentException e) {
                    throw new IllegalStateException("WebSocket 端点注册失败", e);
                }
            }
        };
    }
}
