package net.castimage.fms_backend.Exception;

/**
 * 冲突（40900，HTTP 409）：状态机非法迁移、借贷不平、期间锁定、凭证不可变、SoD 等 DB 触发器拒绝与 Java 预检路径。
 */
public class ConflictException extends FmsBaseException {

    /**
     * @param message 错误消息（面向用户）
     */
    public ConflictException(String message) {
        super(FmsBaseException.CODE_CONFLICT, message); // 业务规则拒绝统一 40900，
    }

    /**
     * @param message 错误消息
     * @param cause   根因（仅日志，不向客户端暴露）
     */
    public ConflictException(String message, Throwable cause) {
        super(FmsBaseException.CODE_CONFLICT, message, cause);
    }
}
