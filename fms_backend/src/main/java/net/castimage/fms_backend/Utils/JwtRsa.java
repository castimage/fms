package net.castimage.fms_backend.Utils;

import com.alibaba.fastjson2.JSON;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RS256 JWT 签发与验签，后端只持有 RSA 私钥，网关只持有公钥。
 *
 * 私钥使用 PKCS#8 PEM，从 fms.jwt.private-key-path 指定的只读文件加载。公钥由私钥的
 * CRT 参数推导，供 refresh 验签使用，不单独落盘。数据库的 auth.jwt_sign 和
 * auth.jwt_verify 不再参与运行链路。
 */
public final class JwtRsa {

    private static final Map<String, KeyMaterial> CACHE = new ConcurrentHashMap<>();

    private JwtRsa() {
    }

    private record KeyMaterial(PrivateKey privateKey, PublicKey publicKey) {
    }

    /**
     * 用私钥签发紧凑 JWT。
     *
     * @param payloadJson 已包含 sub、role、exp、iss、aud、type 的 JSON 载荷
     * @param privateKeyPath PKCS#8 PEM 私钥路径
     * @return compact JWT
     */
    public static String sign(String payloadJson, String privateKeyPath) {
        KeyMaterial keys = keys(privateKeyPath);
        try {
            return Jwts.builder()
                    .content(payloadJson)
                    .signWith(keys.privateKey(), Jwts.SIG.RS256)
                    .compact();
        } catch (JwtException e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
    }

    /**
     * 校验 RS256 签名并返回载荷 JSON。
     *
     * 算法固定为 RS256，签名或格式失败统一抛 IllegalArgumentException，由
     * AuthService 映射为 40100，不向调用方泄露密钥或载荷细节。
     *
     * @param token compact JWT
     * @param privateKeyPath PKCS#8 PEM 私钥路径
     * @return JWT 载荷 JSON
     */
    public static String verify(String token, String privateKeyPath) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("令牌为空");
        }
        try {
            return JSON.toJSONString(Jwts.parser()
                    .verifyWith(keys(privateKeyPath).publicKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            throw new IllegalArgumentException("令牌验签失败", e);
        }
    }

    /**
     * 启动期校验私钥可加载，失败直接抛 IllegalStateException，由 Spring 启动阶段终止进程。
     *
     * @param privateKeyPath PKCS#8 PEM 私钥路径
     */
    public static void validate(String privateKeyPath) {
        keys(privateKeyPath);
    }

    private static KeyMaterial keys(String privateKeyPath) {
        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            throw new IllegalStateException("fms.jwt.private-key-path 未配置");
        }
        return CACHE.computeIfAbsent(privateKeyPath, JwtRsa::load);
    }

    private static KeyMaterial load(String privateKeyPath) {
        try {
            String pem = Files.readString(Path.of(privateKeyPath), StandardCharsets.US_ASCII);
            String body = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof RSAPrivateCrtKey rsa)) {
                throw new IllegalStateException("JWT 私钥必须是 RSA CRT 私钥");
            }
            PublicKey publicKey = factory.generatePublic(
                    new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
            return new KeyMaterial(privateKey, publicKey);
        } catch (Exception e) {
            throw new IllegalStateException("JWT 私钥加载失败: " + privateKeyPath, e);
        }
    }
}
