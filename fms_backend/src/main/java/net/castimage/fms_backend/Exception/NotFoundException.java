package net.castimage.fms_backend.Exception;

/**
 * 资源不存在（40400，HTTP 404）：写路径目标记录不存在或超出可见范围（RLS 空集）时抛出；只读详情路径按协议返回 data:null 不抛异常。
 */
public class NotFoundException extends FmsBaseException {

    /**
     * @param message 错误消息
     */
    public NotFoundException(String message) {
        super(FmsBaseException.CODE_NOT_FOUND, message); // 写路径目标缺失统一 40400，
    }

    /**
     * @param message 错误消息
     * @param cause   根因（仅日志）
     */
    public NotFoundException(String message, Throwable cause) {
        super(FmsBaseException.CODE_NOT_FOUND, message, cause);
    }
}
