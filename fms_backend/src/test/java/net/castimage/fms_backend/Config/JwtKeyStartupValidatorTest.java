package net.castimage.fms_backend.Config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtKeyStartupValidatorTest {

    private static final String KEY = "src/test/resources/jwt-test-private.txt";
    private static final String PUBLIC_KEY = "src/test/resources/jwt-test-public.txt";

    @Test
    void validKeyPairPasses() {
        FmsProperties properties = new FmsProperties();
        properties.getJwt().setPrivateKeyPath(KEY);
        properties.getJwt().setPublicKeyPath(PUBLIC_KEY);
        assertDoesNotThrow(() -> new JwtKeyStartupValidator(properties).validate());
    }

    @Test
    void missingPrivateKeyFailsStartup() {
        FmsProperties properties = new FmsProperties();
        properties.getJwt().setPrivateKeyPath("src/test/resources/no-such-jwt-key.pem");
        properties.getJwt().setPublicKeyPath(PUBLIC_KEY);
        assertThrows(IllegalStateException.class,
                () -> new JwtKeyStartupValidator(properties).validate());
    }

    @Test
    void missingPublicKeyFailsStartup() {
        FmsProperties properties = new FmsProperties();
        properties.getJwt().setPrivateKeyPath(KEY);
        properties.getJwt().setPublicKeyPath("src/test/resources/no-such-jwt-public.pem");
        assertThrows(IllegalStateException.class,
                () -> new JwtKeyStartupValidator(properties).validate());
    }
}
