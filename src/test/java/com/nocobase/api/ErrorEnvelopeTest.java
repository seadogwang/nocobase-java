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
        // Destroying the built-in 'root' role (id=1) is forbidden even for admins.
        MvcResult result = mockMvc.perform(post("/api/roles:destroy")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("id", "1"))
                .andExpect(status().isForbidden())
                .andReturn();

        Map<String, Object> body = parseResponse(result);
        verifyErrorEnvelope(body);
        verifyNoSensitiveData(body);
    }

    @Test
    @DisplayName("Malformed JSON body (400): HttpMessageNotReadableException maps to 400")
    void malformedJsonReturns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/collections:create")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("invalid-json-body-here"))
                .andExpect(status().isBadRequest())
                .andReturn();

        Map<String, Object> body = parseResponse(result);
        verifyErrorEnvelope(body);
        verifyNoSensitiveData(body);
    }

    // ========================================================================
    // Internal Server Error (500)
    // ========================================================================

    @Test
    @DisplayName("Internal error (500): SQL execution failure returns standard envelope")
    void internalErrorReturns500() throws Exception {
        // Create a collection, then insert a record referencing a non-existent
        // column -> SqlCollectionExecutionException -> 500.
        String coll = "err_test_500_" + System.currentTimeMillis();
        try {
            mockMvc.perform(post("/api/collections:create")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", coll,
                                    "type", "physical",
                                    "tableName", coll,
                                    "fields", java.util.List.of(Map.of("name", "name", "type", "string"))))))
                    .andExpect(status().isOk());

            MvcResult result = mockMvc.perform(post("/api/" + coll + ":create")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "nonexistent_column_xyz", "value"))))
                    .andExpect(status().isInternalServerError())
                    .andReturn();

            Map<String, Object> body = parseResponse(result);
            verifyErrorEnvelope(body);
            verifyNoSensitiveData(body);
        } finally {
            mockMvc.perform(post("/api/collections:destroy")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("name", coll))));
        }
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
    @DisplayName("Service unavailable (503): unreachable external data source surfaces 503")
    void dataSourceUnavailableReturns503() throws Exception {
        // Reliable 503 recipe (verified against the resolver/controller flow):
        // 1. register a WORKING external data source (H2 in-memory) so a SQL
        //    collection can load and enter the runtime registry;
        // 2. create a SQL collection referencing that data source;
        // 3. update the data source to an UNREACHABLE PostgreSQL URL — this
        //    invalidates the resolver cache WITHOUT reloading the collection,
        //    so the collection stays in the registry;
        // 4. list the collection -> DynamicRepository -> SqlQueryCollectionExecutor
        //    -> resolve("work-ds") -> preflight fails -> DataSourceUnavailableException
        //    -> GlobalExceptionHandler -> 503.
        String dsKey = "work_ds_" + System.currentTimeMillis();
        String coll = "err_test_503_" + System.currentTimeMillis();
        try {
            // 1. working H2-mem data source
            mockMvc.perform(post("/api/dataSources:create")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "key", dsKey,
                                    "displayName", "work ds",
                                    "url", "jdbc:h2:mem:" + dsKey + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                                    "driverClassName", "org.h2.Driver",
                                    "username", "sa",
                                    "password", "",
                                    "enabled", true,
                                    "dialect", "h2",
                                    "readOnly", true))))
                    .andExpect(status().isOk());

            // 2. SQL collection referencing work-ds
            mockMvc.perform(post("/api/collections:create")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", coll,
                                    "type", "sql",
                                    "sql", "SELECT 'x' AS \"name\"",
                                    "options", Map.of("dataSourceKey", dsKey),
                                    "fields", java.util.List.of(Map.of("name", "name", "type", "string"))))))
                    .andExpect(status().isOk());

            // 3. flip the data source to an unreachable PostgreSQL URL
            mockMvc.perform(post("/api/dataSources:update")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "key", dsKey,
                                    "url", "jdbc:postgresql://localhost:65432/nosuchdb",
                                    "driverClassName", "org.postgresql.Driver",
                                    "dialect", "postgresql"))))
                    .andExpect(status().isOk());

            // 4. list the collection -> 503
            MvcResult result = mockMvc.perform(get("/api/" + coll + ":list")
                            .header("Authorization", "Bearer " + adminToken)
                            .param("sort", "name"))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn();

            Map<String, Object> body = parseResponse(result);
            verifyErrorEnvelope(body);
            verifyNoSensitiveData(body);
            // The response must not echo the raw bad URL or credentials.
            String serialized = body.toString();
            assertFalse(serialized.contains("65432"),
                    "503 response must not leak the unreachable port: " + serialized);
            assertFalse(serialized.contains("nosuchdb"),
                    "503 response must not leak the database name: " + serialized);
        } finally {
            try {
                mockMvc.perform(post("/api/collections:destroy")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", coll))));
            } catch (Exception ignored) { /* best-effort */ }
            try {
                mockMvc.perform(post("/api/dataSources:destroy")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("filterByTk", dsKey));
            } catch (Exception ignored) { /* best-effort */ }
        }
    }

    // ========================================================================
    // Conflict (409)
    // ========================================================================

    @Test
    @DisplayName("Conflict (409): duplicate field returns 409 with standard envelope")
    void conflictReturns409() throws Exception {
        // Create a collection with a field, then add the SAME field again ->
        // IllegalStateException (Field already exists) -> 409 Conflict.
        String coll = "err_test_409_" + System.currentTimeMillis();
        try {
            mockMvc.perform(post("/api/collections:create")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", coll,
                                    "type", "physical",
                                    "tableName", coll,
                                    "fields", java.util.List.of(Map.of("name", "dup_field", "type", "string"))))))
                    .andExpect(status().isOk());

            // Add the same field name again -> 409
            MvcResult conflictResult = mockMvc.perform(post("/api/fields:create")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "collectionName", coll,
                                    "name", "dup_field",
                                    "type", "string"))))
                    .andExpect(status().isConflict())
                    .andReturn();

            Map<String, Object> conflictBody = parseResponse(conflictResult);
            verifyErrorEnvelope(conflictBody);
            verifyNoSensitiveData(conflictBody);
        } finally {
            mockMvc.perform(post("/api/collections:destroy")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("name", coll))));
        }
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