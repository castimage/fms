package net.castimage.fms_backend.Config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtKeyStartupValidatorTest {

    private static final String KEY = "src/test/resources/jwt-test-private.txt";

    @Test
    void validPrivateKeyPasses() {
        FmsProperties properties = new FmsProperties();
        properties.getJwt().setPrivateKeyPath(KEY);
        assertDoesNotThrow(() -> new JwtKeyStartupValidator(properties).validate());
    }

    @Test
    void missingPrivateKeyFailsStartup() {
        FmsProperties properties = new FmsProperties();
        properties.getJwt().setPrivateKeyPath("src/test/resources/no-such-jwt-key.pem");
        assertThrows(IllegalStateException.class,
                () -> new JwtKeyStartupValidator(properties).validate());
    }
}
