package net.castimage.fms_backend.Controller;


import net.castimage.fms_backend.Utils.JwtRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * 通知 WebSocket 握手拦截器，在升级完成前读取网关采信的身份头并写入会话属性。
 *
 * 身份头来自签名链，握手请求经 GatewaySignatureFilter 校验 X-FMS-Signature 后放行，
 * 过滤器剥离客户端自带的 X-FMS-* 头再注入可信值，故头值不可伪造。
 * 用户 id 须为 canonical uuid，角色须命中五角色白名单，任一不满足即返回 403，
 * 握手不建立会话，端点无需处理身份不完整的连接。
 */
@Component
public class NotifyHandshakeInterceptor implements HandshakeInterceptor {

    /** 会话属性键，用户 id（uuid） */
    public static final String ATTR_UID = "fmsUid";

    /** 会话属性键，角色短名（JwtRole） */
    public static final String ATTR_ROLE = "fmsRole";

    /** 采信用户 id 的请求头，网关验签后注入 */
    private static final String HEADER_UID = "X-FMS-UserId";

    /** 采信角色短名的请求头，网关验签后注入 */
    private static final String HEADER_ROLE = "X-FMS-Role";

    /**
     * 握手前校验身份并写会话属性。
     *
     * @param request    握手请求，读取网关注入的身份头
     * @param response   握手响应，身份非法时置 403
     * @param wsHandler  目标处理器，本拦截器不修改
     * @param attributes 会话属性容器，合法身份写入 ATTR_UID 与 ATTR_ROLE
     * @return true 继续升级，false 拒绝握手
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        UUID uid = parseUid(request.getHeaders().getFirst(HEADER_UID));
        JwtRole role = JwtRole.from(request.getHeaders().getFirst(HEADER_ROLE));
        if (uid == null || role == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(ATTR_UID, uid);
        attributes.put(ATTR_ROLE, role);
        return true;
    }

    /** 握手完成后无额外处理 */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    /** 解析用户 id，空白或非 uuid 返回 null */
    private static UUID parseUid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
