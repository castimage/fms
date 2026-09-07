package net.castimage.fms_backend.Exception;

/**
 * 未预期异常（50000）：兜底，不向客户端泄露内部细节。
 */
public class InternalServerException extends FmsBaseException {

    public InternalServerException(String message) {
        super(FmsBaseException.CODE_INTERNAL, message);
    }

    public InternalServerException(String message, Throwable cause) {
        super(FmsBaseException.CODE_INTERNAL, message, cause); // 堆栈只进日志，
    }
}
