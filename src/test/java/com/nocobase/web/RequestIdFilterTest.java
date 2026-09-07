package com.nocobase.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.AuditLog;
import com.nocobase.repository.AuditLogRepository;
import com.nocobase.repository.SystemSettingsRepository;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for RequestIdFilter lifecycle hardening.
 * <p>
 * Verifies:
 * <ol>
 *   <li>Caller-provided X-Request-Id is echoed back in response header</li>
 *   <li>Missing X-Request-Id causes a generated UUID to be set in response</li>
 *   <li>Write operations record the requestId in audit_logs</li>
 *   <li>ThreadLocal and MDC are cleaned up after each request (no leak)</li>
 *   <li>Invalid X-Request-Id values are rejected with 400</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RequestIdFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private SystemSettingsRepository settingsRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        // Sign in as admin to get a valid JWT token
        MvcResult result = mockMvc.perform(post("/api/auth:signIn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@nocobase.com\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> response = objectMapper.readValue(responseBody,
                new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        adminToken = (String) data.get("token");

        // Ensure ThreadLocal and MDC are clean before each test
        RequestIdContext.clear();
        MDC.remove("requestId");
    }

    @AfterEach
    void tearDown() {
        RequestIdContext.clear();
        MDC.remove("requestId");
    }

    /**
     * Build a GET /api/auth:check request with the admin auth token.
     */
    private MockHttpServletRequestBuilder authCheckRequest() {
        return get("/api/auth:check")
                .header("Authorization", "Bearer " + adminToken);
    }

    // ========================================================================
    // Test 1: Caller-provided X-Request-Id is echoed in response header
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("GET /api/auth:check with X-Request-Id -> response header matches")
    void testRequestIdEchoedInResponse() throws Exception {
        String expectedRequestId = "test-request-id-001";

        MvcResult result = mockMvc.perform(authCheckRequest()
                        .header("X-Request-Id", expectedRequestId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", expectedRequestId))
                .andReturn();

        String responseHeader = result.getResponse().getHeader("X-Request-Id");
        assertEquals(expectedRequestId, responseHeader,
                "Response X-Request-Id header must match the caller-provided value");
    }

    // ========================================================================
    // Test 2: No header -> response header is non-empty (generated UUID)
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("GET /api/auth:check without X-Request-Id -> response header non-empty generated UUID")
    void testRequestIdGeneratedWhenMissing() throws Exception {
        MvcResult result = mockMvc.perform(authCheckRequest())
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();

        String responseHeader = result.getResponse().getHeader("X-Request-Id");
        assertNotNull(responseHeader, "X-Request-Id response header must be present");
        assertFalse(responseHeader.isEmpty(), "X-Request-Id response header must not be empty");
        // Generated UUIDs have no dashes (replace("-", "")), so should be 32 hex chars
        assertEquals(32, responseHeader.length(),
                "Generated request ID should be 32 hex characters (UUID without dashes)");
        assertTrue(responseHeader.matches("[0-9a-f]{32}"),
                "Generated request ID should be lowercase hex only");
    }

    // ========================================================================
    // Test 3: Write operation -> audit_logs.request_id matches
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("Write operation records request_id in audit_logs matching response header")
    void testWriteOperationRecordsRequestId() throws Exception {
        String expectedRequestId = "audit-test-req-id-003";

        // Perform a system settings update (write operation that triggers audit)
        MvcResult result = mockMvc.perform(post("/api/systemSettings:update")
                        .header("X-Request-Id", expectedRequestId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"test_request_id\":" + System.currentTimeMillis() + "}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", expectedRequestId))
                .andReturn();

        String responseHeader = result.getResponse().getHeader("X-Request-Id");
        assertEquals(expectedRequestId, responseHeader,
                "Response X-Request-Id header must match the provided value");

        // Verify the audit log entry has the correct request_id
        List<AuditLog> auditLogs = auditLogRepository.findByRequestId(expectedRequestId);
        assertFalse(auditLogs.isEmpty(),
                "Audit log must contain an entry with requestId=" + expectedRequestId);
        assertEquals(expectedRequestId, auditLogs.get(0).getRequestId(),
                "Audit log request_id must match the X-Request-Id header value");

        // Cleanup
        settingsRepository.findBySettingKey("test_request_id").ifPresent(settingsRepository::delete);
    }

    // ========================================================================
    // Test 4: Two consecutive requests -> ThreadLocal/MDC don't leak
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("Two consecutive requests: ThreadLocal and MDC are cleaned up, no leak")
    void testNoThreadLocalOrMdcLeakBetweenRequests() throws Exception {
        String firstRequestId = "first-request-leak-test";
        String secondRequestId = "second-request-leak-test";

        // First request with a specific request ID
        mockMvc.perform(authCheckRequest()
                        .header("X-Request-Id", firstRequestId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", firstRequestId));

        // After the filter's finally block, ThreadLocal and MDC must be null
        assertNull(RequestIdContext.get(),
                "RequestIdContext ThreadLocal must be null after first request completes");
        assertNull(MDC.get("requestId"),
                "MDC requestId must be null after first request completes");

        // Second request with a different request ID
        mockMvc.perform(authCheckRequest()
                        .header("X-Request-Id", secondRequestId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", secondRequestId));

        // After the second request, ThreadLocal and MDC must still be null
        assertNull(RequestIdContext.get(),
                "RequestIdContext ThreadLocal must be null after second request completes");
        assertNull(MDC.get("requestId"),
                "MDC requestId must be null after second request completes");
    }

    // ========================================================================
    // Test 5: Multiple requests with generated UUIDs don't leak
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("Multiple requests with generated UUIDs: no cross-contamination")
    void testNoCrossContaminationWithGeneratedIds() throws Exception {
        // First request (no X-Request-Id header -> generated)
        MvcResult result1 = mockMvc.perform(authCheckRequest())
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();

        String requestId1 = result1.getResponse().getHeader("X-Request-Id");
        assertNotNull(requestId1);

        // Verify cleanup
        assertNull(RequestIdContext.get());
        assertNull(MDC.get("requestId"));

        // Second request (no X-Request-Id header -> generated)
        MvcResult result2 = mockMvc.perform(authCheckRequest())
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();

        String requestId2 = result2.getResponse().getHeader("X-Request-Id");
        assertNotNull(requestId2);

        // The two generated IDs should be different
        assertNotEquals(requestId1, requestId2,
                "Two consecutive requests should generate different request IDs");

        // Verify cleanup again
        assertNull(RequestIdContext.get());
        assertNull(MDC.get("requestId"));
    }

    // ========================================================================
    // Test 6: Invalid X-Request-Id values are rejected with 400
    // (These tests don't need auth because the filter rejects before the controller)
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("Invalid X-Request-Id (too long) is rejected with 400")
    void testRejectTooLongRequestId() throws Exception {
        String tooLong = "a".repeat(256);

        mockMvc.perform(get("/api/auth:check")
                        .header("X-Request-Id", tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_REQUEST_ID"));
    }

    @Test
    @Order(7)
    @DisplayName("Invalid X-Request-Id (special chars) is rejected with 400")
    void testRejectSpecialCharsInRequestId() throws Exception {
        mockMvc.perform(get("/api/auth:check")
                        .header("X-Request-Id", "bad<script>alert(1)</script>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_REQUEST_ID"));
    }

    @Test
    @Order(8)
    @DisplayName("Invalid X-Request-Id (spaces) is rejected with 400")
    void testRejectSpacesInRequestId() throws Exception {
        mockMvc.perform(get("/api/auth:check")
                        .header("X-Request-Id", "request id with spaces"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_REQUEST_ID"));
    }

    @Test
    @Order(9)
    @DisplayName("Invalid X-Request-Id (empty string) triggers generation instead of rejection")
    void testEmptyRequestIdGeneratesNewOne() throws Exception {
        // Empty string is treated as "not provided" and should generate a new ID
        MvcResult result = mockMvc.perform(authCheckRequest()
                        .header("X-Request-Id", ""))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();

        String responseHeader = result.getResponse().getHeader("X-Request-Id");
        assertNotNull(responseHeader);
        assertEquals(32, responseHeader.length(),
                "Empty X-Request-Id should trigger generation of a 32-char hex UUID");
    }

    @Test
    @Order(10)
    @DisplayName("Valid X-Request-Id with dashes and underscores is accepted")
    void testValidRequestIdWithDashesAndUnderscores() throws Exception {
        String validId = "my-request_id-123";

        mockMvc.perform(authCheckRequest()
                        .header("X-Request-Id", validId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", validId));
    }
}