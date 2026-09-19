package com.nocobase.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the health check endpoints.
 * Verifies liveness, readiness, backward compatibility, and security.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "token", "jdbcUrl", "url",
            "jdbc", "driver", "credentials", "username", "privateKey",
            "masterKey", "apiKey", "authKey", "encryption"
    );

    // ========================================================================
    // Liveness endpoint
    // ========================================================================

    @Test
    @DisplayName("GET /api/health/live returns 200 with status=UP")
    void liveReturns200Up() throws Exception {
        mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    @DisplayName("GET /api/health/live does not require authentication")
    void liveAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk());
    }

    // ========================================================================
    // Readiness endpoint
    // ========================================================================

    @Test
    @DisplayName("GET /api/health/ready returns 200 with status=UP")
    void readyReturns200Up() throws Exception {
        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.components.database.status").value("UP"))
                .andExpect(jsonPath("$.components.flyway.status").isString())
                .andExpect(jsonPath("$.components.runtimeRegistry.status").isString());
    }

    @Test
    @DisplayName("GET /api/health/ready does not require authentication")
    void readyAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk());
    }

    // ========================================================================
    // Backward compatibility - /api/health
    // ========================================================================

    @Test
    @DisplayName("GET /api/health is backward compatible")
    void healthBackwardCompatible() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").isString())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.components").isMap())
                .andExpect(jsonPath("$.components.database").isMap())
                .andExpect(jsonPath("$.components.flyway").isMap())
                .andExpect(jsonPath("$.components.runtimeRegistry").isMap())
                .andExpect(jsonPath("$.components.externalDataSources").isMap());
    }

    @Test
    @DisplayName("GET /api/health does not require authentication")
    void healthAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    // ========================================================================
    // No secrets in responses
    // ========================================================================

    @Test
    @DisplayName("GET /api/health/live response contains no secrets or JDBC URLs")
    void liveResponseHasNoSecrets() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertNoSensitiveData(body);
    }

    @Test
    @DisplayName("GET /api/health/ready response contains no secrets or JDBC URLs")
    void readyResponseHasNoSecrets() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertNoSensitiveData(body);
    }

    @Test
    @DisplayName("GET /api/health response contains no secrets or JDBC URLs")
    void healthResponseHasNoSecrets() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertNoSensitiveData(body);
    }

    @Test
    @DisplayName("Health response contains no password field in components")
    void healthResponseHasNoPasswordFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Map<String, Object> response = objectMapper.readValue(body, Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) response.get("components");
        assertNotNull(components, "components must be present");

        // Check external data sources for password fields
        @SuppressWarnings("unchecked")
        Map<String, Object> externalDataSources = (Map<String, Object>) components.get("externalDataSources");
        if (externalDataSources != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) externalDataSources.get("sources");
            if (sources != null) {
                for (Map<String, Object> source : sources) {
                    assertFalse(source.containsKey("password"),
                            "external data source must not expose password");
                    assertFalse(source.containsKey("url"),
                            "external data source must not expose JDBC URL");
                    assertFalse(source.containsKey("username"),
                            "external data source must not expose username");
                    assertFalse(source.containsKey("driverClassName"),
                            "external data source must not expose driver class name");
                }
            }
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Assert that the response body does not contain sensitive data.
     */
    private void assertNoSensitiveData(String body) {
        String lower = body.toLowerCase();
        for (String key : SENSITIVE_KEYS) {
            assertFalse(lower.contains("\"" + key.toLowerCase() + "\""),
                    "Response must not contain key '" + key + "'");
        }
        // Check for JDBC URLs
        assertFalse(body.contains("jdbc:"),
                "Response must not contain JDBC URL");
        assertFalse(body.contains("jdbc%3A"),
                "Response must not contain URL-encoded JDBC URL");
        // Check for common password patterns
        assertFalse(body.contains("\"password\""),
                "Response must not contain password field");
        assertFalse(body.contains("\"secret\""),
                "Response must not contain secret field");
    }
}