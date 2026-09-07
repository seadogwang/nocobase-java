package com.nocobase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the Spring Boot application context loads successfully.
 */
@SpringBootTest
@ActiveProfiles("test")
class NocobaseApplicationTests {

    @Test
    void contextLoads() {
        // If this test passes, the application context loads successfully
    }
}