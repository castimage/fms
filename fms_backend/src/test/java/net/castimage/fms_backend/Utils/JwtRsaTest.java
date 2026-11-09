package net.castimage.fms_backend.Utils;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtRsaTest {

    private static final String KEY = "src/test/resources/jwt-test-private.txt";
    private static final String PUBLIC_KEY = "src/test/resources/jwt-test-public.txt";

    @Test
    void signAndVerifyRoundTrip() {
        String payload = "{\"sub\":\"3f9a2c1d-0000-4000-8000-000000000001\","
                + "\"role\":\"operator\",\"exp\":4102444800,\"type\":\"access\"}";
        String token = JwtRsa.sign(payload, KEY);
        assertEquals(payload, JwtRsa.verify(token, KEY));
    }

    @Test
    void validateConfiguredKeyPair() {
        assertDoesNotThrow(() -> JwtRsa.validate(KEY, PUBLIC_KEY));
    }

    @Test
    void validateMissingKeyRejected() {
        assertThrows(IllegalStateException.class,
                () -> JwtRsa.validate("src/test/resources/no-such-jwt-key.pem", PUBLIC_KEY));
    }

    @Test
    void validateMismatchedPublicKeyRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey other = (RSAPublicKey) generator.generateKeyPair().getPublic();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(other.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Path otherKey = Files.createTempFile("jwt-other-public", ".pem");
        Files.writeString(otherKey, pem);
        try {
            assertThrows(IllegalStateException.class,
                    () -> JwtRsa.validate(KEY, otherKey.toString()));
        } finally {
            Files.deleteIfExists(otherKey);
        }
    }

    @Test
    void nonRs256AlgorithmRejected() throws Exception {
        String token = Jwts.builder()
                .content("{\"sub\":\"x\",\"exp\":4102444800}")
                .signWith(privateKey(), Jwts.SIG.RS512)
                .compact();
        assertThrows(IllegalArgumentException.class, () -> JwtRsa.verify(token, KEY));
    }

    private static PrivateKey privateKey() throws Exception {
        String pem = Files.readString(Path.of(KEY), StandardCharsets.US_ASCII);
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    @Test
    void tamperedSignatureRejected() {
        String token = JwtRsa.sign("{\"sub\":\"x\",\"exp\":1}", KEY);
        int index = token.length() - 2;
        char c = token.charAt(index) == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, index) + c + token.substring(index + 1);
        assertThrows(IllegalArgumentException.class, () -> JwtRsa.verify(tampered, KEY));
    }
}
