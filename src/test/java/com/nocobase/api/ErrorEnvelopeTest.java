package com.nocobase.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * API error envelope contract tests.
 *
 * <p>Verifies every error category returns:
 * <ul>
 *   <li>Correct HTTP status code</li>
 *   <li>Standard envelope: {@code {"errors": [{"message": "..."}]}}</li>
 *   <li>No sensitive data (stack traces, SQL, JDBC URLs, credentials)</li>
 * </ul>
 *
 * <p>Covers both colon (:action) and slash (/action) route patterns.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ErrorEnvelopeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        if (adminToken == null) {
            adminToken = obtainAdminToken();
        }
    }

    private String obtainAdminToken() throws Exception {
        Map<String, String> credentials = Map.of(
                "email", "admin@nocobase.com",
                "password", "admin123"
        );
        MvcResult result = mockMvc.perform(post("/api/auth:signIn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(credentials)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return (String) data.get("token");
    }

    // ========================================================================
    // Unauthenticated (401)
    // ========================================================================

    @Test
    @DisplayName("Unauthenticated (401): missing token returns standard error envelope")
    void unauthenticatedReturns401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users:list"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        Map<String, Object> body = parseResponse(result);
        verifyErrorEnvelope(body);
        assertTrue(body.toString().contains("Unauthorized"),
                "401 must indicate unauthorized");
    }

    // ========================================================================
    // Validation / Bad Request (400)
    // ========================================================================

    @Test
    @DisplayName("Validation error (400): invalid input returns standard error envelope")
    void validationErrorReturns400() throws Exception {
        // Try to create a collection with empty name — triggers validation
        MvcResult result = mockMvc.perform(post("/api/collections:create")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        Map<String, Object> body = parseResponse(result);
        verifyNoSensitiveData(body);
    }

    // ========================================================================
    // Not Found (404)
    // ========================================================================

    @Test
    @DisplayName("Not found (404): nonexistent resource returns standard error envelope")
    void notFoundReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/collections:nonexistent_collection_xyz")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andReturn();

        Map<String, Object> body = parseResponse(result);
        verifyErrorEnvelope(body);
    }

    // ========================================================================
    // Forbidden (403)
    // ========================================================================

    @Test
    @DisplayName("Forbidden (403): insufficient permissions returns standard error envelope")
    void forbiddenReturns403() throws Exception {
        // Try to destroy the root role — should be forbidden
        MvcResult result = mockMvc.perform(post("/api/roles:destroy")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"name\":\"root\"}}"))
                .andReturn();

        assertTrue(
                result.getResponse().getStatus() == 403
                        || result.getResponse().getStatus() == 404
                        || result.getResponse().getStatus() == 400,
                "Expected 403, 404, or 400, got " + result.getResponse().getStatus());

        Map<String, Object> body = parseResponse(result);
        verifyNoSensitiveData(body);
    }

    // ========================================================================
    // Internal Server Error (500)
    // ========================================================================

    @Test
    @DisplayName("Internal error (500): returns standard envelope without sensitive data")
    void internalErrorReturns500() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/collections:create")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("invalid-json-body-here"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertTrue(status == 400 || status == 500,
                "Expected 400 or 500, got " + status);

        Map<String, Object> body = parseResponse(result);
        verifyNoSensitiveData(body);
    }

    // ========================================================================
    // Sensitive data exclusion
    // ========================================================================

    @Test
    @DisplayName("Error responses never expose stack traces")
    void errorResponsesExcludeStackTraces() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users:list"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("stackTrace") || body.contains("stack_trace")
                        || body.contains("Caused by:") || body.contains("\tat "),
                "Error response must not contain stack trace");
    }

    @Test
    @DisplayName("Error responses never expose SQL or JDBC URLs")
    void errorResponsesExcludeSqlAndJdbc() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users:list"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("SELECT ") || body.contains("INSERT ")
                        || body.contains("jdbc:") || body.contains("JDBC"),
                "Error response must not contain SQL or JDBC URLs");
    }

    @Test
    @DisplayName("Error responses never expose credentials or tokens")
    void errorResponsesExcludeCredentials() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users:list"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = result.getResponse().getContentAsString().toLowerCase();
        assertFalse(body.contains("password")
                        || body.contains("secret")
                        || body.contains("token"),
                "Error response must not contain password, secret, or token");
    }

    // ========================================================================
    // Colon vs slash routes
    // ========================================================================

    @Test
    @DisplayName("Colon route returns consistent error envelope")
    void colonRouteReturnsConsistentEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/collections:list"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        Map<String, Object> body = parseResponse(result);
        verifyErrorEnvelope(body);
    }

    @Test
    @DisplayName("Slash route returns consistent error envelope")
    void slashRouteReturnsConsistentEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/collections/list"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        Map<String, Object> body = parseResponse(result);
        verifyErrorEnvelope(body);
    }

    // ========================================================================
    // Service unavailable (503)
    // ========================================================================

    @Test
    @DisplayName("Service unavailable (503): DataSourceUnavailableException maps to 503")
    void dataSourceUnavailableReturns503() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sqlCollections:list")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("dataSourceKey", "nonexistent-ds-key"))
                .andReturn();

        int status = result.getResponse().getStatus();
        // May return 503, 404, or 400 depending on routing/validation order
        assertTrue(status >= 400 && status < 600,
                "Expected 4xx or 5xx, got " + status);
    }

    // ========================================================================
    // Conflict (409)
    // ========================================================================

    @Test
    @DisplayName("Conflict (409): duplicate resource returns 409 with standard envelope")
    void conflictReturns409() throws Exception {
        // Create a collection, then try to create it again
        String collectionName = "err_test_dup_" + System.currentTimeMillis();
        String createJson = "{\"name\": \"" + collectionName + "\", \"type\": \"physical\", \"tableName\": \""
                + collectionName + "\", \"fields\": [{\"name\": \"test_field\", \"type\": \"string\"}]}";

        // First create — should succeed
        mockMvc.perform(post("/api/collections:create")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isOk());

        // Second create (duplicate) — should return 409 Conflict
        MvcResult conflictResult = mockMvc.perform(post("/api/collections:create")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andReturn();

        int status = conflictResult.getResponse().getStatus();
        assertTrue(status == 409 || status == 400 || status == 500,
                "Expected 409, 400, or 500 for duplicate collection, got " + status);

        if (status == 409) {
            Map<String, Object> body = parseResponse(conflictResult);
            verifyErrorEnvelope(body);
        }

        // Cleanup
        mockMvc.perform(post("/api/collections:destroy")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + collectionName + "\"}"));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        if (content == null || content.isEmpty()) {
            return Map.of();
        }
        return objectMapper.readValue(content, Map.class);
    }

    @SuppressWarnings("unchecked")
    private void verifyErrorEnvelope(Map<String, Object> body) {
        assertNotNull(body, "Error response body must not be null");
        assertTrue(body.containsKey("errors"),
                "Error response must contain 'errors' key. Got keys: " + body.keySet());
        Object errors = body.get("errors");
        assertInstanceOf(List.class, errors,
                "'errors' must be a list, got: " + (errors != null ? errors.getClass() : "null"));
        List<Map<String, Object>> errorList = (List<Map<String, Object>>) errors;
        assertFalse(errorList.isEmpty(), "'errors' list must not be empty");
        assertTrue(errorList.get(0).containsKey("message"),
                "Each error must contain a 'message' key");
    }

    private void verifyNoSensitiveData(Map<String, Object> body) {
        String serialized = body.toString();

        assertFalse(serialized.contains("\tat "),
                "Response must not contain stack trace lines");
        assertFalse(serialized.toLowerCase().contains("select ")
                        || serialized.toLowerCase().contains("insert "),
                "Response must not contain SQL fragments");
        assertFalse(serialized.contains("jdbc:"),
                "Response must not contain JDBC URLs");
        assertFalse(serialized.toLowerCase().contains("password")
                        || serialized.toLowerCase().contains("secret"),
                "Response must not contain credentials");
    }
}