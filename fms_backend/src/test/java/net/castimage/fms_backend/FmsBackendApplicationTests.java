package net.castimage.fms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "fms.jwt.private-key-path=src/test/resources/jwt-test-private.txt",
        "fms.jwt.public-key-path=src/test/resources/jwt-test-public.txt"
})
class FmsBackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
