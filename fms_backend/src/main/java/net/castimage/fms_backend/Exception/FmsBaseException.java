package net.castimage.fms_backend.Exception;

/**
 * FMS 业务异常基类：错误码 40001~50000，HTTP 状态码由错误码前三位推导
 * （40001 映射 400、40100 映射 401、40300 映射 403、40400 映射 404、40900 映射 409、42900 映射 429、50000 映射 500），
 * 子类按错误码逐一定义，供 GlobalExceptionHandler 统一映射。
 */
public class FmsBaseException extends RuntimeException {

    /** 参数/业务校验失败 */
    public static final int CODE_BAD_REQUEST = 40001;
    /** 未认证（签名失败/伪造身份头/未知角色/API-Key 无效） */
    public static final int CODE_UNAUTHORIZED = 40100;
    /** token 过期 */
    public static final int CODE_TOKEN_EXPIRED = 40101;
    /** 无权限（RBAC 预检、PG SQLSTATE 42501、require_permission RAISE） */
    public static final int CODE_FORBIDDEN = 40300;
    /** 资源不存在 */
    public static final int CODE_NOT_FOUND = 40400;
    /** 冲突（唯一键/外键/检查约束/状态机非法迁移/借贷不平/期间锁定/凭证不可变） */
    public static final int CODE_CONFLICT = 40900;
    /** 限流 */
    public static final int CODE_RATE_LIMIT = 42900;
    /** 未预期异常 */
    public static final int CODE_INTERNAL = 50000;

    /** FMS 业务错误码 */
    private final int code;

    /** 映射的 HTTP 状态码（错误码前三位） */
    private final int httpStatus;

    /**
     * @param code    业务错误码（FMS 协议常量）
     * @param message 错误消息（面向用户，不泄露内部细节）
     */
    public FmsBaseException(int code, String message) {
        super(message);
        this.code = code;
        this.httpStatus = code / 100; // 前三位即 HTTP 状态码，
    }

    /**
     * @param code    业务错误码（FMS 协议常量）
     * @param message 错误消息
     * @param cause   根因（用于日志，不向客户端暴露）
     */
    public FmsBaseException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = code / 100;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
