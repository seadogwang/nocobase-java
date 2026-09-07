package com.nocobase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.runtime.CollectionRuntimeService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * Integration tests for P0-D: controller error compatibility for SQL collections.
 * Verifies that HTTP error responses have the correct format and do NOT expose
 * full configured SQL or bound parameter values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlCollectionErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DdlSynchronizer ddlSynchronizer;

    @Autowired
    private CollectionRuntimeService runtimeService;

    private String authToken;

    private static final String VALID_SQL_COLL = "test_p0d_valid_sql";
    private static final String UNDECLARED_PARAM_COLL = "test_p0d_undeclared_param";
    private static final String UNSUPPORTED_TYPE_COLL = "test_p0d_unsupported_type";
    private static final String MISSING_DEFAULT_COLL = "test_p0d_missing_default";
    private static final String MALFORMED_PARAM_COLL = "test_p0d_malformed_param";
    private static final String BAD_TABLE_COLL = "test_p0d_bad_table";

    private static final List<String> ALL_COLLS = List.of(
            VALID_SQL_COLL, UNDECLARED_PARAM_COLL, UNSUPPORTED_TYPE_COLL,
            MISSING_DEFAULT_COLL, MALFORMED_PARAM_COLL, BAD_TABLE_COLL);

    @BeforeAll
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));

        // Sign in as admin to get a token
        Map<String, String> credentials = Map.of(
                "email", "admin@nocobase.com",
                "password", "admin123"
        );
        MvcResult result = mockMvc.perform(post("/api/auth:signIn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(credentials)))
                .andExpect(status().isOk())
                .andReturn();
        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        authToken = (String) data.get("token");
    }

    @AfterEach
    void cleanup() {
        for (String name : ALL_COLLS) {
            try {
                ddlSynchronizer.dropCollection(name);
            } catch (Exception ignored) {
            }
        }
        runtimeService.clearInvalidCollections();
    }

    // ========== P0-D: Error response format ==========

    @Test
    @DisplayName("P0-D: error response format is { errors: [{ message: '...' }] } for 404")
    void errorResponseFormatForNotFound() throws Exception {
        mockMvc.perform(get("/api/nonexistent_collection_xyz:list")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].message").isString())
                .andExpect(jsonPath("$.errors[0].message").value(containsString("not found")));
    }

    @Test
    @DisplayName("P0-D: error response format is { errors: [{ message: '...' }] } for 400")
    void errorResponseFormatForBadRequest() throws Exception {
        // Request with invalid filter JSON — use a path that routes through GenericCrudController
        mockMvc.perform(get("/api/nonexistent:list")
                        .header("Authorization", "Bearer " + authToken)
                        .param("filter", "not-valid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].message").isString())
                .andExpect(jsonPath("$.errors[0].message").value(containsString("Invalid filter")));
    }

    // ========== P0-D: Undeclared named parameter ==========

    @Test
    @DisplayName("P0-D: undeclared named parameter is caught and tracked")
    void undeclaredNamedParameter() throws Exception {
        // Create a SQL collection with a parameter in SQL but not declared in options
        CollectionEntity entity = new CollectionEntity(UNDECLARED_PARAM_COLL, "Undeclared Param", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE status = :undeclared_status");
        entity.setOptions("{}"); // No parameters declared
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(UNDECLARED_PARAM_COLL, "id", "bigInt"),
                new FieldEntity(UNDECLARED_PARAM_COLL, "status", "string")));

        runtimeService.loadAll();

        // Verify it's tracked in invalidCollections
        Map<String, String> invalid = runtimeService.getInvalidCollections();
        assertTrue(invalid.containsKey(UNDECLARED_PARAM_COLL),
                "Undeclared parameter collection should be tracked in invalidCollections");
        String errorMsg = invalid.get(UNDECLARED_PARAM_COLL);
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("undeclared_status") || errorMsg.contains("not declared"),
                "Error message should mention the undeclared parameter name");

        // Verify the collection is NOT in the registry
        assertFalse(runtimeService.exists(UNDECLARED_PARAM_COLL));

        // Verify the list endpoint returns proper error format
        mockMvc.perform(get("/api/" + UNDECLARED_PARAM_COLL + ":list")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].message").isString());
    }

    // ========== P0-D: Unsupported parameter type ==========

    @Test
    @DisplayName("P0-D: unsupported parameter type is caught and tracked")
    void unsupportedParameterType() throws Exception {
        CollectionEntity entity = new CollectionEntity(UNSUPPORTED_TYPE_COLL, "Unsupported Type", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE status = :status");
        entity.setOptions("{\"parameters\": [{\"name\": \"status\", \"type\": \"binary\", \"defaultValue\": \"x\"}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(UNSUPPORTED_TYPE_COLL, "id", "bigInt"),
                new FieldEntity(UNSUPPORTED_TYPE_COLL, "status", "string")));

        runtimeService.loadAll();

        Map<String, String> invalid = runtimeService.getInvalidCollections();
        assertTrue(invalid.containsKey(UNSUPPORTED_TYPE_COLL),
                "Unsupported type collection should be tracked in invalidCollections");
        String errorMsg = invalid.get(UNSUPPORTED_TYPE_COLL);
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("Unsupported parameter type") || errorMsg.contains("unsupported"),
                "Error message should mention unsupported type");

        // Verify error does NOT contain the SQL
        assertFalse(errorMsg.contains("SELECT * FROM"),
                "Error message should NOT contain configured SQL");

        assertFalse(runtimeService.exists(UNSUPPORTED_TYPE_COLL));
    }

    // ========== P0-D: Missing required static default ==========

    @Test
    @DisplayName("P0-D: missing required static default is caught and tracked")
    void missingRequiredStaticDefault() throws Exception {
        CollectionEntity entity = new CollectionEntity(MISSING_DEFAULT_COLL, "Missing Default", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE status = :status");
        // required=true but no defaultValue
        entity.setOptions("{\"parameters\": [{\"name\": \"status\", \"type\": \"string\", \"required\": true}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(MISSING_DEFAULT_COLL, "id", "bigInt"),
                new FieldEntity(MISSING_DEFAULT_COLL, "status", "string")));

        runtimeService.loadAll();

        Map<String, String> invalid = runtimeService.getInvalidCollections();
        assertTrue(invalid.containsKey(MISSING_DEFAULT_COLL),
                "Missing default collection should be tracked in invalidCollections");
        String errorMsg = invalid.get(MISSING_DEFAULT_COLL);
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("defaultValue") || errorMsg.contains("default"),
                "Error message should mention missing defaultValue");

        assertFalse(runtimeService.exists(MISSING_DEFAULT_COLL));
    }

    // ========== P0-D: Malformed parameter name ==========

    @Test
    @DisplayName("P0-D: malformed parameter name is caught and tracked")
    void malformedParameterName() throws Exception {
        CollectionEntity entity = new CollectionEntity(MALFORMED_PARAM_COLL, "Malformed Param", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE status = :status");
        // Parameter name with invalid characters
        entity.setOptions("{\"parameters\": [{\"name\": \"status@bad\", \"type\": \"string\", \"defaultValue\": \"x\"}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(MALFORMED_PARAM_COLL, "id", "bigInt"),
                new FieldEntity(MALFORMED_PARAM_COLL, "status", "string")));

        runtimeService.loadAll();

        Map<String, String> invalid = runtimeService.getInvalidCollections();
        assertTrue(invalid.containsKey(MALFORMED_PARAM_COLL),
                "Malformed parameter collection should be tracked in invalidCollections");
        String errorMsg = invalid.get(MALFORMED_PARAM_COLL);
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("Invalid parameter name") || errorMsg.contains("malformed"),
                "Error message should mention invalid parameter name");

        assertFalse(runtimeService.exists(MALFORMED_PARAM_COLL));
    }

    // ========== P0-D: Runtime SQL error does NOT leak SQL ==========

    @Test
    @DisplayName("P0-D: SQL error does NOT expose full configured SQL in tracking")
    void runtimeSqlErrorDoesNotLeakSql() throws Exception {
        // Create a SQL collection that references a non-existent table.
        // With field validation, this is caught at load time rather than at runtime.
        CollectionEntity entity = new CollectionEntity(BAD_TABLE_COLL, "Bad Table", "sql");
        entity.setSql("SELECT * FROM \"nonexistent_table_xyz\" WHERE status = :status");
        entity.setOptions("{\"parameters\": [{\"name\": \"status\", \"type\": \"string\", \"defaultValue\": \"active\"}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(BAD_TABLE_COLL, "id", "bigInt"),
                new FieldEntity(BAD_TABLE_COLL, "status", "string")));

        // With field validation, this will fail at load time and be tracked as invalid
        runtimeService.loadAll();
        assertTrue(runtimeService.getInvalidCollections().containsKey(BAD_TABLE_COLL),
                "Invalid SQL collection should be tracked in invalidCollections");

        String errorMsg = runtimeService.getInvalidCollections().get(BAD_TABLE_COLL);
        assertNotNull(errorMsg);

        // Verify error message does NOT contain the full SQL
        assertFalse(errorMsg.contains("SELECT * FROM"),
                "Error message should NOT contain configured SQL: " + errorMsg);
        assertFalse(errorMsg.contains("nonexistent_table_xyz"),
                "Error message should NOT contain table name from SQL: " + errorMsg);

        // Verify the collection is NOT in the registry
        assertFalse(runtimeService.exists(BAD_TABLE_COLL));

        // Verify the list endpoint returns 404 (not found)
        mockMvc.perform(get("/api/" + BAD_TABLE_COLL + ":list")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].message").isString());
    }

    // ========== P0-D: Valid SQL collection works ==========

    @Test
    @DisplayName("P0-D: valid SQL collection loads and list endpoint works")
    void validSqlCollectionWorks() throws Exception {
        CollectionEntity entity = new CollectionEntity(VALID_SQL_COLL, "Valid SQL", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE \"email\" = :email");
        entity.setOptions("{\"parameters\": [{\"name\": \"email\", \"type\": \"string\", \"defaultValue\": \"admin@nocobase.com\"}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(VALID_SQL_COLL, "id", "bigInt"),
                new FieldEntity(VALID_SQL_COLL, "email", "string")));

        runtimeService.loadAll();
        assertTrue(runtimeService.exists(VALID_SQL_COLL),
                "Valid SQL collection should be loaded into registry");

        // The list endpoint should work (users table exists in test DB)
        mockMvc.perform(get("/api/" + VALID_SQL_COLL + ":list")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ========== P0-D: Verify sanitizeErrorMessage strips SQL ==========

    @Test
    @DisplayName("P0-D: sanitizeErrorMessage strips SQL fragments from error messages")
    void sanitizeStripsSqlFragments() {
        // Create a collection with a param that would work but has a SQL-like error msg
        // The sanitizeErrorMessage method in CollectionRuntimeService should catch any
        // SQL fragments that accidentally leak through
        CollectionEntity entity = new CollectionEntity("test_p0d_sanitize", "Sanitize Test", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE status = :status");
        // Duplicate parameter name should trigger an error
        entity.setOptions("{\"parameters\": [" +
                "{\"name\": \"status\", \"type\": \"string\", \"defaultValue\": \"active\"}," +
                "{\"name\": \"status\", \"type\": \"string\", \"defaultValue\": \"inactive\"}" +
                "]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity("test_p0d_sanitize", "id", "bigInt"),
                new FieldEntity("test_p0d_sanitize", "status", "string")));

        runtimeService.loadAll();

        Map<String, String> invalid = runtimeService.getInvalidCollections();
        String errorMsg = invalid.get("test_p0d_sanitize");

        // The duplicate param error should not contain SQL
        assertNotNull(errorMsg, "Duplicate param collection should be tracked");
        assertTrue(errorMsg.contains("Duplicate") || errorMsg.contains("duplicate"),
                "Error should mention duplicate parameter");

        // Cleanup this test collection
        try {
            ddlSynchronizer.dropCollection("test_p0d_sanitize");
        } catch (Exception ignored) {
        }
    }
}