package net.castimage.fms_backend.Exception;

/**
 * 未认证（40100）：签名失败/伪造身份头/未知角色/API-Key 无效/登录凭据错误。
 */
public class UnauthorizedException extends FmsBaseException {

    public UnauthorizedException(String message) {
        super(FmsBaseException.CODE_UNAUTHORIZED, message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(FmsBaseException.CODE_UNAUTHORIZED, message, cause); // 身份不可信一律 40100，
    }
}
