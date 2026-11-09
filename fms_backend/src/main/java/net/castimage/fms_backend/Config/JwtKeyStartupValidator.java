package net.castimage.fms_backend.Config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import net.castimage.fms_backend.Utils.JwtRsa;
import org.springframework.stereotype.Component;

/**
 * JWT 私钥启动校验，Spring 上下文装配完成后立即加载 PKCS#8 私钥。
 *
 * 私钥缺失、PEM 损坏、非 RSA CRT 私钥或路径不可读都会在启动阶段抛出异常，
 * 避免服务健康但登录时才暴露密钥配置错误。
 */
@Component
@RequiredArgsConstructor
public class JwtKeyStartupValidator {

    private final FmsProperties fmsProperties;

    /**
     * 校验 fms.jwt.private-key-path 指向的私钥可加载。
     */
    @PostConstruct
    public void validate() {
        JwtRsa.validate(fmsProperties.getJwt().getPrivateKeyPath());
    }
}
