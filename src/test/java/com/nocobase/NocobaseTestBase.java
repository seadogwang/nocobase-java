package com.nocobase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for Spring Boot integration tests.
 * Provides MockMvc, ObjectMapper, and common test utilities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class NocobaseTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected String authToken;

    /**
     * Obtain a valid JWT token for the admin user.
     * Subclasses should call this in @BeforeEach if needed.
     */
    @BeforeEach
    void baseSetUp() throws Exception {
        // Default: no auth token; subclasses can override
        authToken = null;
    }
}