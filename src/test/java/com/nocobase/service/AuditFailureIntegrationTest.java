package com.nocobase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.NocobaseApplication;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.repository.FieldRepository;
import com.nocobase.runtime.CollectionRuntimeService;
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

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests verifying audit failure logging across all services.
 * <p>
 * Each test triggers a service-level failure and verifies that a corresponding
 * audit failure record is persisted with correct resource, action, status,
 * requestId, and sanitized details.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = NocobaseApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditFailureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DdlSynchronizer ddlSynchronizer;

    @Autowired
    private CollectionRuntimeService runtimeService;

    @Autowired
    private FieldRepository fieldRepository;

    private String adminToken;

    private static final String TEST_AUDIT_COLL = "test_audit_crud";
    private static final String TEST_AUDIT_SOURCE = "test_audit_assoc_src";
    private static final String TEST_AUDIT_TARGET = "test_audit_assoc_tgt";

    private boolean collectionsCreated = false;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult adminResult = mockMvc.perform(post("/api/auth:signIn")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", "admin@nocobase.com", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        Map<String, Object> adminResp = objectMapper.readValue(
                adminResult.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> adminData = (Map<String, Object>) adminResp.get("data");
        adminToken = (String) adminData.get("token");

        // Create test collections for dynamic CRUD and association tests
        if (!collectionsCreated) {
            ensureTestCollections();
            collectionsCreated = true;
        }
    }

    @AfterEach
    void tearDown() {
        // Clean up test collections (best-effort)
        for (String name : List.of(TEST_AUDIT_COLL, TEST_AUDIT_SOURCE, TEST_AUDIT_TARGET)) {
            try {
                if (runtimeService.exists(name)) {
                    ddlSynchronizer.dropCollection(name);
                    runtimeService.reload(name);
                }
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        runtimeService.clearInvalidCollections();
        collectionsCreated = false;
    }

    private void ensureTestCollections() {
        if (!runtimeService.exists(TEST_AUDIT_COLL)) {
            CollectionEntity coll = new CollectionEntity(TEST_AUDIT_COLL, "Test Audit CRUD", "physical");
            coll.setTableName(TEST_AUDIT_COLL);
            ddlSynchronizer.createCollection(coll, List.of(
                    new FieldEntity(TEST_AUDIT_COLL, "name", "string"),
                    new FieldEntity(TEST_AUDIT_COLL, "description", "text")));
            runtimeService.reload(TEST_AUDIT_COLL);
        }

        if (!runtimeService.exists(TEST_AUDIT_SOURCE)) {
            CollectionEntity src = new CollectionEntity(TEST_AUDIT_SOURCE, "Test Audit Source", "physical");
            src.setTableName(TEST_AUDIT_SOURCE);
            ddlSynchronizer.createCollection(src, List.of(
                    new FieldEntity(TEST_AUDIT_SOURCE, "name", "string")));
            runtimeService.reload(TEST_AUDIT_SOURCE);
        }

        if (!runtimeService.exists(TEST_AUDIT_TARGET)) {
            CollectionEntity tgt = new CollectionEntity(TEST_AUDIT_TARGET, "Test Audit Target", "physical");
            tgt.setTableName(TEST_AUDIT_TARGET);
            ddlSynchronizer.createCollection(tgt, List.of(
                    new FieldEntity(TEST_AUDIT_TARGET, "name", "string"),
                    new FieldEntity(TEST_AUDIT_TARGET, "source_id", "bigInt")));
            runtimeService.reload(TEST_AUDIT_TARGET);
        }

        // Add hasMany relation "targets" on source collection pointing to target collection
        if (runtimeService.exists(TEST_AUDIT_SOURCE) && runtimeService.exists(TEST_AUDIT_TARGET)
                && fieldRepository.findByCollectionNameAndName(TEST_AUDIT_SOURCE, "targets").isEmpty()) {
            FieldEntity relField = new FieldEntity(TEST_AUDIT_SOURCE, "targets", "hasMany");
            relField.setTarget(TEST_AUDIT_TARGET);
            relField.setForeignKey("source_id");
            relField.setSourceKey("id");
            fieldRepository.save(relField);
            runtimeService.reload(TEST_AUDIT_SOURCE);
        }
    }

    // ========================================================================
    // Test 1: System plugin disable attempt (ForbiddenException from service)
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("System plugin disable triggers audit failure")
    void systemPluginDisableTriggersAuditFailure() throws Exception {
        mockMvc.perform(post("/api/plugins:disable")
                .header("Authorization", "Bearer " + adminToken)
                .param("name", "users"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Verify failure audit record exists
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "plugin")
                .param("resourceKey", "users")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");
        assertFalse(data.isEmpty(), "Should have at least one audit record");

        boolean foundFailure = data.stream()
                .anyMatch(entry -> "failure".equals(entry.get("status"))
                        && "disable".equals(entry.get("action"))
                        && "plugin".equals(entry.get("resource")));
        assertTrue(foundFailure, "Should find a failure audit record for plugin disable");
    }

    // ========================================================================
    // Test 2: Non-existent plugin disable (IllegalArgumentException from service)
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("Non-existent plugin disable triggers audit failure")
    void nonExistentPluginDisableTriggersAuditFailure() throws Exception {
        String pluginName = "nonexistent_plugin_" + System.currentTimeMillis();

        mockMvc.perform(post("/api/plugins:disable")
                .header("Authorization", "Bearer " + adminToken)
                .param("name", pluginName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Verify failure audit record exists
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "plugin")
                .param("resourceKey", pluginName)
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");
        assertFalse(data.isEmpty(), "Should have at least one audit record");

        boolean foundFailure = data.stream()
                .anyMatch(entry -> "failure".equals(entry.get("status"))
                        && "disable".equals(entry.get("action"))
                        && "plugin".equals(entry.get("resource")));
        assertTrue(foundFailure, "Should find a failure audit record for plugin disable");
    }

    // ========================================================================
    // Test 3: testConnection with invalid URL (IllegalArgumentException from service)
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("testConnection with invalid URL triggers audit failure")
    void testConnectionInvalidUrlTriggersAuditFailure() throws Exception {
        mockMvc.perform(post("/api/dataSources:testConnection")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "url", "jdbc:mysql://localhost:3306/test",
                        "username", "test",
                        "password", "test123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Verify failure audit record exists for dataSource resource
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "dataSource")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");
        assertFalse(data.isEmpty(), "Should have at least one audit record");

        boolean foundFailure = data.stream()
                .anyMatch(entry -> "failure".equals(entry.get("status"))
                        && "testConnection".equals(entry.get("action"))
                        && "dataSource".equals(entry.get("resource")));
        assertTrue(foundFailure, "Should find a failure audit record for testConnection");
    }

    // ========================================================================
    // Test 4: Add field to non-existent collection (service-level error)
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("Add field to non-existent collection triggers audit failure")
    void addFieldToNonExistentCollectionTriggersAuditFailure() throws Exception {
        String collectionName = "nonexistent_field_test_" + System.currentTimeMillis();

        mockMvc.perform(post("/api/fields:create")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "collectionName", collectionName,
                        "name", "test_field",
                        "type", "string"))))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Verify failure audit record exists for field resource
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "field")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");
        assertFalse(data.isEmpty(), "Should have at least one audit record");

        boolean foundFailure = data.stream()
                .anyMatch(entry -> "failure".equals(entry.get("status"))
                        && "create".equals(entry.get("action"))
                        && "field".equals(entry.get("resource")));
        assertTrue(foundFailure, "Should find a failure audit record for field creation");
    }

    // ========================================================================
    // Test 5: Verify audit failure records have correct structure
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("Failure audit records have correct structure")
    void failureAuditRecordsHaveCorrectStructure() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("pageSize", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");

        // Filter for failure records
        List<Map<String, Object>> failureRecords = data.stream()
                .filter(entry -> "failure".equals(entry.get("status")))
                .toList();

        assertFalse(failureRecords.isEmpty(),
                "Should have at least one failure audit record");

        for (Map<String, Object> record : failureRecords) {
            // Verify required fields
            assertNotNull(record.get("id"), "id should not be null");
            assertNotNull(record.get("action"), "action should not be null");
            assertNotNull(record.get("resource"), "resource should not be null");
            // resourceKey may be null for pre-existing records or controller-level failures
            // that never reached the service layer
            assertNotNull(record.get("createdAt"), "createdAt should not be null");
            assertEquals("failure", record.get("status"), "status should be 'failure'");
        }
        // Verify that at least some records have non-null resourceKey
        boolean hasResourceKey = failureRecords.stream()
                .anyMatch(r -> r.get("resourceKey") != null);
        assertTrue(hasResourceKey, "At least one failure record should have a non-null resourceKey");
    }

    // ========================================================================
    // Test 6: Verify requestId is present in failure audit records
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("requestId is present in all failure audit records")
    void requestIdIsPresentInFailureAuditRecords() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("pageSize", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");

        // Filter for failure records
        List<Map<String, Object>> failureRecords = data.stream()
                .filter(entry -> "failure".equals(entry.get("status")))
                .toList();

        assertFalse(failureRecords.isEmpty(),
                "Should have at least one failure audit record");

        for (Map<String, Object> record : failureRecords) {
            Object requestId = record.get("requestId");
            assertNotNull(requestId,
                    "requestId should not be null for failure record: " + record.get("action") + " " + record.get("resource"));
            assertTrue(requestId instanceof String,
                    "requestId should be a String");
            assertFalse(((String) requestId).isEmpty(),
                    "requestId should not be empty for failure record: " + record.get("action") + " " + record.get("resource"));
        }
    }

    // ========================================================================
    // Test 7: Verify failure audit details are sanitized
    // ========================================================================

    @Test
    @Order(7)
    @DisplayName("Failure audit details do not contain sensitive data")
    void failureAuditDetailsAreSanitized() throws Exception {
        String sensitivePassword = "SuperSecret123!";

        // Trigger an error by using an invalid URL (so validation fails)
        // The body contains a password, but the audit details should not contain it
        mockMvc.perform(post("/api/dataSources:testConnection")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "url", "jdbc:mysql://localhost:3306/test",
                        "username", "admin",
                        "password", sensitivePassword))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Query audit logs for dataSource resource
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "dataSource")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");

        // Find failure records
        List<Map<String, Object>> failureRecords = data.stream()
                .filter(entry -> "failure".equals(entry.get("status")))
                .toList();

        assertFalse(failureRecords.isEmpty(),
                "Should have at least one failure audit record");

        for (Map<String, Object> record : failureRecords) {
            // Check that the raw password is not in the details
            Object detailsObj = record.get("details");
            if (detailsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> details = (Map<String, Object>) detailsObj;
                String detailsStr = objectMapper.writeValueAsString(details);
                assertFalse(detailsStr.contains(sensitivePassword),
                        "Audit details should not contain the raw password. Details: " + detailsStr);
            }
        }
    }

    // ========================================================================
    // Test 8: Dynamic CRUD create failure triggers audit failure
    // ========================================================================

    @Test
    @Order(8)
    @DisplayName("Dynamic CRUD create failure triggers audit failure")
    void dynamicCrudCreateFailureTriggersAuditFailure() throws Exception {
        // Trigger a failure by creating a record with a field that doesn't exist in the table
        // This reaches DynamicRepository.create where audit logging happens
        mockMvc.perform(post("/api/" + TEST_AUDIT_COLL + ":create")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "nonexistent_column_" + System.currentTimeMillis(), "value"))))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Verify failure audit record exists for dynamicCrud resource
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "dynamicCrud")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");
        assertFalse(data.isEmpty(), "Should have at least one audit record for dynamicCrud");

        boolean foundFailure = data.stream()
                .anyMatch(entry -> "failure".equals(entry.get("status"))
                        && "create".equals(entry.get("action"))
                        && "dynamicCrud".equals(entry.get("resource")));
        assertTrue(foundFailure, "Should find a failure audit record for dynamic CRUD create");
    }

    // ========================================================================
    // Test 9: Dynamic CRUD update failure triggers audit failure
    // ========================================================================

    @Test
    @Order(9)
    @DisplayName("Dynamic CRUD update failure triggers audit failure")
    void dynamicCrudUpdateFailureTriggersAuditFailure() throws Exception {
        // Try to update a non-existent record (id=999999)
        mockMvc.perform(post("/api/" + TEST_AUDIT_COLL + ":update")
                .header("Authorization", "Bearer " + adminToken)
                .param("filterByTk", "999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "name", "updated_name"))))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Verify failure audit record exists for dynamicCrud resource with action "update"
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "dynamicCrud")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");

        boolean foundFailure = data.stream()
                .anyMatch(entry -> "failure".equals(entry.get("status"))
                        && "update".equals(entry.get("action"))
                        && "dynamicCrud".equals(entry.get("resource")));
        assertTrue(foundFailure, "Should find a failure audit record for dynamic CRUD update");
    }

    // ========================================================================
    // Test 10: Dynamic CRUD destroy failure triggers audit failure
    // ========================================================================

    @Test
    @Order(10)
    @DisplayName("Dynamic CRUD destroy failure triggers audit failure")
    void dynamicCrudDestroyFailureTriggersAuditFailure() throws Exception {
        // Try to destroy a non-existent record (id=999998)
        mockMvc.perform(post("/api/" + TEST_AUDIT_COLL + ":destroy")
                .header("Authorization", "Bearer " + adminToken)
                .param("filterByTk", "999998"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Verify failure audit record exists for dynamicCrud resource with action "destroy"
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "dynamicCrud")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");

        boolean foundFailure = data.stream()
                .anyMatch(entry -> "failure".equals(entry.get("status"))
                        && "destroy".equals(entry.get("action"))
                        && "dynamicCrud".equals(entry.get("resource")));
        assertTrue(foundFailure, "Should find a failure audit record for dynamic CRUD destroy");
    }

    // ========================================================================
    // Test 11: Association add failure triggers audit failure
    // ========================================================================

    @Test
    @Order(11)
    @DisplayName("Association add failure triggers audit failure")
    void associationAddFailureTriggersAuditFailure() throws Exception {
        // Try to add an association with a non-existent source record
        // Use a URL that will be rewritten by the filter to the GenericCrudController
        String assocResource = TEST_AUDIT_SOURCE + ".target";
        mockMvc.perform(post("/api/" + assocResource + ":add")
                .header("Authorization", "Bearer " + adminToken)
                .param("filterByTk", "999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "targetId", 1))))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Verify failure audit record exists for association resource
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "association")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");
        assertFalse(data.isEmpty(), "Should have at least one audit record for association");

        boolean foundFailure = data.stream()
                .anyMatch(entry -> "failure".equals(entry.get("status"))
                        && "add".equals(entry.get("action"))
                        && "association".equals(entry.get("resource")));
        assertTrue(foundFailure, "Should find a failure audit record for association add");
    }

    // ========================================================================
    // Test 12: Association remove failure triggers audit failure
    // ========================================================================

    @Test
    @Order(12)
    @DisplayName("Association remove failure triggers audit failure")
    void associationRemoveFailureTriggersAuditFailure() throws Exception {
        // Try to remove an association with a non-existent source record
        String assocResource = TEST_AUDIT_SOURCE + ".target";
        mockMvc.perform(post("/api/" + assocResource + ":remove")
                .header("Authorization", "Bearer " + adminToken)
                .param("filterByTk", "999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "targetId", 1))))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.errors[0].message").exists());

        // Verify failure audit record exists for association resource
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "association")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");

        boolean foundFailure = data.stream()
                .anyMatch(entry -> "failure".equals(entry.get("status"))
                        && "remove".equals(entry.get("action"))
                        && "association".equals(entry.get("resource")));
        assertTrue(foundFailure, "Should find a failure audit record for association remove");
    }

    // ========================================================================
    // Test 13: Dynamic CRUD audit success records have correct structure
    // ========================================================================

    @Test
    @Order(13)
    @DisplayName("Dynamic CRUD audit success records have correct structure")
    void dynamicCrudAuditSuccessRecordsHaveCorrectStructure() throws Exception {
        // Create a record
        MvcResult createResult = mockMvc.perform(post("/api/" + TEST_AUDIT_COLL + ":create")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "name", "test_record",
                        "description", "test description"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();

        String createResp = createResult.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> createBody = objectMapper.readValue(createResp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> createData = (Map<String, Object>) createBody.get("data");
        int createdId = (Integer) createData.get("id");

        // Update the record
        mockMvc.perform(post("/api/" + TEST_AUDIT_COLL + ":update")
                .header("Authorization", "Bearer " + adminToken)
                .param("filterByTk", String.valueOf(createdId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "name", "updated_name"))))
                .andExpect(status().isOk());

        // Destroy the record
        mockMvc.perform(post("/api/" + TEST_AUDIT_COLL + ":destroy")
                .header("Authorization", "Bearer " + adminToken)
                .param("filterByTk", String.valueOf(createdId)))
                .andExpect(status().isOk());

        // Query audit logs for dynamicCrud resource and verify success records
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "dynamicCrud")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");

        // Find success records for dynamicCrud
        List<Map<String, Object>> successRecords = data.stream()
                .filter(entry -> "success".equals(entry.get("status")))
                .toList();

        assertFalse(successRecords.isEmpty(),
                "Should have at least one success audit record for dynamicCrud");

        for (Map<String, Object> record : successRecords) {
            assertNotNull(record.get("id"), "id should not be null");
            assertNotNull(record.get("action"), "action should not be null");
            assertEquals("dynamicCrud", record.get("resource"),
                    "resource should be 'dynamicCrud'");
            assertNotNull(record.get("resourceKey"), "resourceKey should not be null");
            assertEquals("success", record.get("status"), "status should be 'success'");
            assertNotNull(record.get("requestId"), "requestId should not be null");
            assertNotNull(record.get("createdAt"), "createdAt should not be null");
            assertNotNull(record.get("actorUserId"), "actorUserId should not be null");
        }
    }

    // ========================================================================
    // Test 14: Association audit success records have correct structure
    // ========================================================================

    @Test
    @Order(14)
    @DisplayName("Association audit success records have correct structure")
    void associationAuditSuccessRecordsHaveCorrectStructure() throws Exception {
        // Create source and target records
        MvcResult srcResult = mockMvc.perform(post("/api/" + TEST_AUDIT_SOURCE + ":create")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "name", "source_record"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();

        String srcResp = srcResult.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> srcBody = objectMapper.readValue(srcResp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> srcData = (Map<String, Object>) srcBody.get("data");
        int sourceId = (Integer) srcData.get("id");

        MvcResult tgtResult = mockMvc.perform(post("/api/" + TEST_AUDIT_TARGET + ":create")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "name", "target_record"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();

        String tgtResp = tgtResult.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> tgtBody = objectMapper.readValue(tgtResp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> tgtData = (Map<String, Object>) tgtBody.get("data");
        int targetId = (Integer) tgtData.get("id");

        // Add target to source via hasMany association
        String assocResource = TEST_AUDIT_SOURCE + ".targets";
        mockMvc.perform(post("/api/" + assocResource + ":add")
                .header("Authorization", "Bearer " + adminToken)
                .param("filterByTk", String.valueOf(sourceId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "targetId", targetId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("ok"));

        // Remove the association
        mockMvc.perform(post("/api/" + assocResource + ":remove")
                .header("Authorization", "Bearer " + adminToken)
                .param("filterByTk", String.valueOf(sourceId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "targetId", targetId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("ok"));

        // Query audit logs for association resource
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "association")
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");

        // Find success records for association
        List<Map<String, Object>> successRecords = data.stream()
                .filter(entry -> "success".equals(entry.get("status")))
                .toList();

        assertFalse(successRecords.isEmpty(),
                "Should have at least one success audit record for association");

        for (Map<String, Object> record : successRecords) {
            assertNotNull(record.get("id"), "id should not be null");
            assertNotNull(record.get("action"), "action should not be null");
            assertEquals("association", record.get("resource"),
                    "resource should be 'association'");
            assertNotNull(record.get("resourceKey"), "resourceKey should not be null");
            assertEquals("success", record.get("status"), "status should be 'success'");
            assertNotNull(record.get("requestId"), "requestId should not be null");
            assertNotNull(record.get("createdAt"), "createdAt should not be null");
            assertNotNull(record.get("actorUserId"), "actorUserId should not be null");
        }
    }

    // ========================================================================
    // Test 15: Dynamic CRUD audit details are sanitized
    // ========================================================================

    @Test
    @Order(15)
    @DisplayName("Dynamic CRUD audit details are sanitized")
    void dynamicCrudAuditDetailsAreSanitized() throws Exception {
        // Create a record with some data
        MvcResult createResult = mockMvc.perform(post("/api/" + TEST_AUDIT_COLL + ":create")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "name", "sensitive_data_test",
                        "description", "test description content"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();

        String createResp = createResult.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> createBody = objectMapper.readValue(createResp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> createData = (Map<String, Object>) createBody.get("data");
        int createdId = (Integer) createData.get("id");

        // Query audit logs for dynamicCrud resource
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "dynamicCrud")
                .param("resourceKey", TEST_AUDIT_COLL)
                .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        assertNotNull(data, "Audit log data should not be null");

        // Find success records
        List<Map<String, Object>> successRecords = data.stream()
                .filter(entry -> "success".equals(entry.get("status")))
                .toList();

        assertFalse(successRecords.isEmpty(),
                "Should have at least one success audit record");

        for (Map<String, Object> record : successRecords) {
            // Check that the details do NOT contain the full field values
            Object detailsObj = record.get("details");
            if (detailsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> details = (Map<String, Object>) detailsObj;
                String detailsStr = objectMapper.writeValueAsString(details);

                // Details should contain metadata (collection, fieldNames, id) but NOT full values
                if (details.containsKey("collection")) {
                    assertEquals(TEST_AUDIT_COLL, details.get("collection"),
                            "collection should be in details");
                }
                if (details.containsKey("fieldNames")) {
                    assertTrue(details.get("fieldNames") instanceof List,
                            "fieldNames should be a list");
                }

                // Should NOT contain the actual field values
                assertFalse(detailsStr.contains("sensitive_data_test"),
                        "Audit details should not contain the full field value 'sensitive_data_test'. Details: " + detailsStr);
                assertFalse(detailsStr.contains("test description content"),
                        "Audit details should not contain the full field value 'test description content'. Details: " + detailsStr);
            }
        }
    }

    // ========================================================================
    // Test 16: testConnection outer validation-exception path does not leak
    //          raw URL / credentials into the persisted audit record
    // (Agent A: covers the pre-SQL validation-exception branch that previously
    //  passed the raw JDBC URL as the audit resourceKey.)
    // ========================================================================

    @Test
    @Order(16)
    @DisplayName("testConnection invalid-URL audit record has no raw URL or credentials")
    void testConnectionInvalidUrlAuditRecordHasNoRawUrlOrCredentials() throws Exception {
        // URL with embedded credentials in query string; 'jdbc:mysql:' fails the
        // prefix whitelist and throws IllegalArgumentException BEFORE any SQL
        // connection attempt, hitting the outer catch that previously leaked raw url.
        String leakyUrl = "jdbc:mysql://localhost:3306/test_db?user=admin&password=secret123";

        mockMvc.perform(post("/api/dataSources:testConnection")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "url", leakyUrl,
                        "username", "admin",
                        "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").exists());

        assertNoDataSourceAuditRecordLeaks("admin", "secret123", "localhost", "3306", "test_db");
    }

    // ========================================================================
    // Test 17: testConnection SQLException path does not leak raw URL /
    //          credentials into the persisted audit record
    // (Agent A: covers the DriverManager.getConnection failure branch.)
    // ========================================================================

    @Test
    @Order(17)
    @DisplayName("testConnection connection-failure audit record has no raw URL or credentials")
    void testConnectionSqlFailureAuditRecordHasNoRawUrlOrCredentials() throws Exception {
        // Valid prefix so it reaches DriverManager.getConnection, which fails
        // (no live PG / no suitable driver) and hits the SQLException catch.
        String leakyUrl = "jdbc:postgresql://localhost:5432/test_db?user=admin&password=secret123";

        mockMvc.perform(post("/api/dataSources:testConnection")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "url", leakyUrl,
                        "username", "admin",
                        "password", "secret123"))))
                // SQLException path returns a 200 with success=false body, NOT an error status
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false));

        // The PG driver's own error text may legitimately mention host/port
        // (not credentials), so here we only assert the raw credentials are absent.
        assertNoDataSourceAuditRecordLeaks("admin", "secret123");
    }

    /**
     * Assert that no persisted dataSource testConnection failure audit record
     * contains any of the given sensitive substrings in its resourceKey,
     * details, or any other serialized field.
     */
    @SuppressWarnings("unchecked")
    private void assertNoDataSourceAuditRecordLeaks(String... sensitive) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken)
                .param("resource", "dataSource")
                .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        Map<String, Object> response = objectMapper.readValue(content, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        assertNotNull(data, "Audit log data should not be null");
        assertFalse(data.isEmpty(), "Should have at least one dataSource audit record");

        // Consider only testConnection failure records (the ones the leak fix affects)
        List<Map<String, Object>> records = data.stream()
                .filter(entry -> "failure".equals(entry.get("status"))
                        && "testConnection".equals(entry.get("action"))
                        && "dataSource".equals(entry.get("resource")))
                .toList();
        assertFalse(records.isEmpty(), "Should have at least one testConnection failure audit record");

        for (Map<String, Object> record : records) {
            String recordJson = objectMapper.writeValueAsString(record);
            for (String s : sensitive) {
                assertFalse(recordJson.contains(s),
                        "testConnection failure audit record must not contain '" + s
                                + "'. Record: " + recordJson);
            }
        }
    }
}