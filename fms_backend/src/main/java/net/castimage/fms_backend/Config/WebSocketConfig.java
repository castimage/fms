package net.castimage.fms_backend.config;

import jakarta.servlet.ServletContext;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import net.castimage.fms_backend.Controller.NotifyEndpointConfigurator;
import net.castimage.fms_backend.Controller.NotifyWebSocketEndpoint;
import net.castimage.fms_backend.Service.NotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.WebApplicationContext;

/**
 * WebSocket 端点注册（JSR-356，内嵌 Tomcat）：项目未引入 spring-boot-starter-websocket
 * （starter-tomcat 自带 tomcat-embed-websocket，jakarta.websocket API 随包提供），
 * 故不走 Spring WebSocketHandler 体系，直接向内嵌 Tomcat ServerContainer 注册
 * NotifyWebSocketEndpoint（路径 /api/nt/ws，与网关的升级判定一致）；
 * 同时注入 NotifyService 静态引用供端点推送未读数。
 *
 * 注册时机取 WebServerInitializedEvent，不用 ServletContextInitializer：后者在
 * ServletContainerInitializer 阶段执行，而 ServerContainer 属性由 Tomcat 的 WsSci 建立，
 * 二者同处一个 SCI 集合、遍历顺序不保证，该阶段属性可能尚未就绪，端点随之不挂载；
 * WebServerInitializedEvent 在 Web 服务器启动完成之后触发，容器属性必已建立。
 */
@Configuration
public class WebSocketConfig {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    /** 通知端点路径，与网关的升级判定路径一致 */
    private static final String NOTIFY_WS_PATH = "/api/nt/ws";

    /**
     * 注册通知 WebSocket 端点：Web 服务器启动完成时从 ServletContext 取 ServerContainer
     * 并 addEndpoint，与内置 Tomcat 的 WebSocket 容器建立映射。
     *
     * 容器缺失或上下文非 Web 应用属启动期不变量被破坏，端点不再挂载、实时通道失效，
     * 故记 WARN 留可观测信号，启动不中断以免掩盖其它故障。
     */
    @Bean
    public ApplicationListener<WebServerInitializedEvent> notifyWebSocketRegistration(
            NotifyService notifyService) {
        // 静态引用注入：端点由 WebSocket 容器按连接实例化（无 Spring 注入能力），启动期一次性注入
        NotifyWebSocketEndpoint.setNotifyService(notifyService);
        return event -> {
            ServletContext servletContext = event.getApplicationContext()
                    instanceof WebApplicationContext wac ? wac.getServletContext() : null;
            Object container = servletContext == null
                    ? null : servletContext.getAttribute(ServerContainer.class.getName());
            if (!(container instanceof ServerContainer serverContainer)) {
                log.warn("ServletContext 未提供 ServerContainer（实际 {}），通知实时通道不可用",
                        container == null ? "null" : container.getClass().getName());
                return;
            }
            try {
                ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder
                        .create(NotifyWebSocketEndpoint.class, NOTIFY_WS_PATH)
                        .configurator(new NotifyEndpointConfigurator())
                        .build();
                serverContainer.addEndpoint(endpointConfig);
                log.info("通知 WebSocket 端点已注册：{}", NOTIFY_WS_PATH);
            } catch (DeploymentException e) {
                throw new IllegalStateException("WebSocket 端点注册失败", e);
            }
        };
    }
}
