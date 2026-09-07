package net.castimage.fms_backend.Exception;

/**
 * 参数/业务校验失败（40001，HTTP 400）：请求体字段非法、期间格式错误、分录金额方向非法等 Service 层预检失败。
 */
public class BadRequestException extends FmsBaseException {

    /**
     * @param message 错误消息（面向用户）
     */
    public BadRequestException(String message) {
        super(FmsBaseException.CODE_BAD_REQUEST, message); // 预检失败统一 40001，
    }

    /**
     * @param message 错误消息
     * @param cause   根因（仅日志，不向客户端暴露）
     */
    public BadRequestException(String message, Throwable cause) {
        super(FmsBaseException.CODE_BAD_REQUEST, message, cause); // 带根因便于日志排查，
    }
}
