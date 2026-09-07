package net.castimage.fms_backend.Exception;

/**
 * 无权限（40300，HTTP 403）：RBAC 预检失败或 PG SQLSTATE 42501 / require_permission RAISE（P0001 含"无权限"）的映射。
 */
public class ForbiddenException extends FmsBaseException {

    /**
     * @param message 错误消息
     */
    public ForbiddenException(String message) {
        super(FmsBaseException.CODE_FORBIDDEN, message); // 越权访问统一 40300，
    }

    /**
     * @param message 错误消息
     * @param cause   根因（仅日志）
     */
    public ForbiddenException(String message, Throwable cause) {
        super(FmsBaseException.CODE_FORBIDDEN, message, cause);
    }
}
