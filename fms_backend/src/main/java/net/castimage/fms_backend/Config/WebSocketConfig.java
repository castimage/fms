package net.castimage.fms_backend.Config;


import lombok.RequiredArgsConstructor;
import net.castimage.fms_backend.Controller.NotifyHandshakeInterceptor;
import net.castimage.fms_backend.Controller.NotifyWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 通知 WebSocket 注册，路径 /api/nt/ws，与网关的升级判定一致。
 *
 * 处理器由 Spring WebSocketHandler 体系承载，握手拦截器在升级前读取网关采信身份并
 * 拒绝非法连接。Origin 不在此处收敛，网关按 cors.allowed_origins 校验浏览器来源，
 * 后端网络只允许网关访问，故此处放行全部 Origin。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /** 通知端点路径，与网关的升级判定路径一致 */
    private static final String NOTIFY_WS_PATH = "/api/nt/ws";

    private final NotifyWebSocketHandler notifyWebSocketHandler;
    private final NotifyHandshakeInterceptor notifyHandshakeInterceptor;

    /**
     * 注册通知端点与握手拦截器。
     *
     * @param registry WebSocket 处理器注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notifyWebSocketHandler, NOTIFY_WS_PATH)
                .addInterceptors(notifyHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
