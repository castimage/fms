package net.castimage.fms_backend.Utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtRsaTest {

    private static final String KEY = "src/test/resources/jwt-test-private.txt";

    @Test
    void signAndVerifyRoundTrip() {
        String payload = "{\"sub\":\"3f9a2c1d-0000-4000-8000-000000000001\","
                + "\"role\":\"operator\",\"exp\":4102444800,\"type\":\"access\"}";
        String token = JwtRsa.sign(payload, KEY);
        assertEquals(payload, JwtRsa.verify(token, KEY));
    }

    @Test
    void validateConfiguredKey() {
        assertDoesNotThrow(() -> JwtRsa.validate(KEY));
    }

    @Test
    void validateMissingKeyRejected() {
        assertThrows(IllegalStateException.class,
                () -> JwtRsa.validate("src/test/resources/no-such-jwt-key.pem"));
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
