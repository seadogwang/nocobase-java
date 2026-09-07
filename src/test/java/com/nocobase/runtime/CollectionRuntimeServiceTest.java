package com.nocobase.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.repository.CollectionRepository;
import com.nocobase.repository.FieldRepository;
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
 * Integration tests for P0-C: SQL parameter validation at collection reload time.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>SQL collections with invalid SQL are caught during reload (fail-fast)</li>
 *   <li>SQL collections with invalid parameter metadata are caught during reload</li>
 *   <li>loadAll() skips invalid collections without crashing</li>
 *   <li>Valid SQL collections with parameter metadata load successfully</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CollectionRuntimeServiceTest {

    @Autowired private CollectionRuntimeService runtimeService;
    @Autowired private DdlSynchronizer ddlSynchronizer;
    @Autowired private CollectionRepository collectionRepository;
    @Autowired private FieldRepository fieldRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String authToken;

    private static final String VALID_SQL_COLL = "test_p0c_valid_sql_col_v2";
    private static final String INVALID_SQL_COLL = "test_p0c_invalid_sql_col_v2";
    private static final String INVALID_PARAM_COLL = "test_p0c_invalid_param_col_v2";
    private static final String UNDECLARED_PARAM_COLL = "test_p0c_undeclared_param_col_v2";
    private static final String UNUSED_PARAM_COLL = "test_p0c_unused_param_col_v2";

    private static final String P0B_FIX_INVALID = "test_p0b_fix_invalid";
    private static final String P0B_BREAK_VALID = "test_p0b_break_valid";
    private static final String P0B_CONSECUTIVE_FAIL = "test_p0b_consecutive_fail";
    private static final String P0C_INVALID_JSON = "test_p0c_invalid_json";
    private static final String P0C_RELOAD_INVALID_JSON = "test_p0c_reload_invalid_json";
    private static final String P0C_CONTROLLER_INVALID_JSON = "test_p0c_controller_invalid_json";

    @BeforeAll
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @BeforeEach
    void setUpAuth() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));

        // Sign in as admin to get a token for controller tests
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
        // Delete test collection metadata directly from the database.
        // Delete field metadata BEFORE collection metadata (FK constraint).
        List<String> testNames = List.of(VALID_SQL_COLL, INVALID_SQL_COLL, INVALID_PARAM_COLL,
                UNDECLARED_PARAM_COLL, UNUSED_PARAM_COLL,
                P0B_FIX_INVALID, P0B_BREAK_VALID, P0B_CONSECUTIVE_FAIL,
                P0C_INVALID_JSON, P0C_RELOAD_INVALID_JSON, P0C_CONTROLLER_INVALID_JSON);
        for (String name : testNames) {
            try {
                // Remove from runtime registry first
                runtimeService.reload(name);
            } catch (Exception ignored) {
                // May not exist or may fail to reload
            }
            try {
                // Delete fields first, then collection
                List<FieldEntity> fields = fieldRepository.findByCollectionName(name);
                fieldRepository.deleteAll(fields);
                collectionRepository.findByName(name).ifPresent(collectionRepository::delete);
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        runtimeService.clearInvalidCollections();
    }

    // ========== P0-C: Valid SQL collection with parameters ==========

    @Test
    @DisplayName("P0-C: valid SQL collection with parameter metadata loads successfully")
    void validSqlCollectionWithParamsLoadsSuccessfully() {
        CollectionEntity entity = new CollectionEntity(VALID_SQL_COLL, "Valid SQL Collection", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE \"email\" = :email");
        entity.setOptions("{\"parameters\": [{\"name\": \"email\", \"type\": \"string\", \"defaultValue\": \"admin@nocobase.com\"}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(VALID_SQL_COLL, "id", "bigInt"),
                new FieldEntity(VALID_SQL_COLL, "email", "string")));

        // reload() should succeed — valid SQL and valid parameter metadata
        assertDoesNotThrow(() -> runtimeService.reload(VALID_SQL_COLL));
        assertTrue(runtimeService.exists(VALID_SQL_COLL));

        CollectionDefinition def = runtimeService.get(VALID_SQL_COLL);
        assertTrue(def.isSql());
        assertEquals("SELECT * FROM \"users\" WHERE \"email\" = :email", def.getSql());
    }

    // ========== P0-C: Invalid SQL (semicolons) - fail-fast on reload ==========

    @Test
    @DisplayName("P0-C: reload fails on SQL with semicolons (fail-fast)")
    void reloadFailsOnInvalidSql() {
        CollectionEntity entity = new CollectionEntity(INVALID_SQL_COLL, "Invalid SQL Collection", "sql");
        entity.setSql("SELECT * FROM users; DROP TABLE users");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(INVALID_SQL_COLL, "id", "bigInt")));

        // reload() should throw — fail-fast on invalid SQL
        assertThrows(Exception.class, () -> runtimeService.reload(INVALID_SQL_COLL));
    }

    // ========== P0-C: Invalid parameter metadata - fail-fast on reload ==========

    @Test
    @DisplayName("P0-C: reload fails on non-list parameters (fail-fast)")
    void reloadFailsOnInvalidParameterMetadata() {
        CollectionEntity entity = new CollectionEntity(INVALID_PARAM_COLL, "Invalid Param Collection", "sql");
        entity.setSql("SELECT * FROM users WHERE status = :status");
        // parameters as a string instead of a list
        entity.setOptions("{\"parameters\": \"not_a_list\"}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(INVALID_PARAM_COLL, "id", "bigInt"),
                new FieldEntity(INVALID_PARAM_COLL, "status", "string")));

        // reload() should throw — fail-fast on invalid parameter metadata
        assertThrows(Exception.class, () -> runtimeService.reload(INVALID_PARAM_COLL));
    }

    // ========== P0-C: Undeclared SQL parameter - fail-fast on reload ==========

    @Test
    @DisplayName("P0-C: reload fails on SQL referencing undeclared parameter (fail-fast)")
    void reloadFailsOnUndeclaredSqlParameter() {
        CollectionEntity entity = new CollectionEntity(UNDECLARED_PARAM_COLL, "Undeclared Param Collection", "sql");
        entity.setSql("SELECT * FROM users WHERE status = :undeclared_status");
        // No parameters declared in options
        entity.setOptions("{}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(UNDECLARED_PARAM_COLL, "id", "bigInt"),
                new FieldEntity(UNDECLARED_PARAM_COLL, "status", "string")));

        // reload() should throw — SQL references undeclared parameter
        assertThrows(Exception.class, () -> runtimeService.reload(UNDECLARED_PARAM_COLL));
    }

    // ========== P0-C: Declared-but-unused parameter - fail-fast on reload ==========

    @Test
    @DisplayName("P0-C: reload fails on declared-but-unused parameter (fail-fast)")
    void reloadFailsOnUnusedParameter() {
        CollectionEntity entity = new CollectionEntity(UNUSED_PARAM_COLL, "Unused Param Collection", "sql");
        entity.setSql("SELECT * FROM users");
        // Parameter declared but not used in SQL
        entity.setOptions("{\"parameters\": [{\"name\": \"unused\", \"type\": \"string\", \"defaultValue\": \"x\"}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(UNUSED_PARAM_COLL, "id", "bigInt")));

        // reload() should throw — parameter declared but not used in SQL
        assertThrows(Exception.class, () -> runtimeService.reload(UNUSED_PARAM_COLL));
    }

    // ========== P0-C: loadAll() skips invalid collections ==========

    @Test
    @DisplayName("P0-C: loadAll skips invalid SQL collections without crashing")
    void loadAllSkipsInvalidCollections() {
        String skipName = "test_p0c_skip_invalid_v2";
        // Create an invalid collection
        CollectionEntity invalidEntity = new CollectionEntity(skipName, "Skip Invalid", "sql");
        invalidEntity.setSql("SELECT * FROM users; DROP TABLE users");
        invalidEntity.setOptions("{}");
        ddlSynchronizer.createCollection(invalidEntity, List.of(
                new FieldEntity(skipName, "id", "bigInt")));

        // loadAll should not crash — it should skip the invalid collection
        assertDoesNotThrow(() -> runtimeService.loadAll());

        // The invalid collection should NOT be in the registry
        assertFalse(runtimeService.exists(skipName),
                "Invalid SQL collection should be skipped during loadAll");

        // Clean up the test entity from DB — delete fields first, then collection
        try {
            runtimeService.reload(skipName);
        } catch (Exception ignored) {
        }
        try {
            List<FieldEntity> fields = fieldRepository.findByCollectionName(skipName);
            fieldRepository.deleteAll(fields);
            collectionRepository.findByName(skipName).ifPresent(collectionRepository::delete);
        } catch (Exception ignored) {
        }
    }

    // ========== P0-C: defaultValue type validation during reload ==========

    @Test
    @DisplayName("P0-C: reload fails on type-mismatched defaultValue (fail-fast)")
    void reloadFailsOnTypeMismatchedDefaultValue() {
        String mismatchName = "test_p0c_type_mismatch_v2";
        CollectionEntity entity = new CollectionEntity(mismatchName, "Type Mismatch", "sql");
        entity.setSql("SELECT * FROM users WHERE age > :min_age");
        // number type with non-numeric defaultValue
        entity.setOptions("{\"parameters\": [{\"name\": \"min_age\", \"type\": \"number\", \"defaultValue\": \"abc\"}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(mismatchName, "id", "bigInt"),
                new FieldEntity(mismatchName, "age", "bigInt")));

        // reload() should throw — defaultValue type mismatch
        assertThrows(Exception.class, () -> runtimeService.reload(mismatchName));

        // Cleanup — delete fields first, then collection
        try {
            runtimeService.reload(mismatchName);
        } catch (Exception ignored) {
        }
        try {
            List<FieldEntity> fields = fieldRepository.findByCollectionName(mismatchName);
            fieldRepository.deleteAll(fields);
            collectionRepository.findByName(mismatchName).ifPresent(collectionRepository::delete);
        } catch (Exception ignored) {
        }
    }

    // ========== P0-C: Non-SQL collections are unaffected ==========

    @Test
    @DisplayName("P0-C: non-SQL collections load without validation errors")
    void nonSqlCollectionsUnaffected() {
        // Physical collections should not be affected by SQL validation
        assertDoesNotThrow(() -> runtimeService.loadAll());

        // Verify that existing physical collections are still in the registry
        // (assuming the test database has some collections from other tests)
        CollectionDefinition def = runtimeService.get("collections");
        assertNotNull(def);
        assertTrue(def.isPhysical());
    }

    // ========== P0-E: Invalid collections tracking ==========

    @Test
    @DisplayName("P0-E: invalidCollections is cleared on each loadAll() call")
    void invalidCollectionsClearedOnLoadAll() {
        // Create an invalid collection to populate invalidCollections
        CollectionEntity entity = new CollectionEntity("test_p0e_clear", "Clear Test", "sql");
        entity.setSql("SELECT * FROM users; DROP TABLE users");
        entity.setOptions("{}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity("test_p0e_clear", "id", "bigInt")));

        runtimeService.loadAll();
        assertTrue(runtimeService.getInvalidCollections().containsKey("test_p0e_clear"),
                "Invalid collection should be tracked after first loadAll");

        // Remove the invalid collection from DB
        try {
            ddlSynchronizer.dropCollection("test_p0e_clear");
        } catch (Exception ignored) {
        }

        // Second loadAll should clear the map — no more invalid collections
        runtimeService.loadAll();
        assertFalse(runtimeService.getInvalidCollections().containsKey("test_p0e_clear"),
                "invalidCollections should be cleared on each loadAll() call");
    }

    @Test
    @DisplayName("P0-E: invalidCollections tracks multiple invalid collections")
    void invalidCollectionsTracksMultipleFailures() {
        // Create two invalid collections
        CollectionEntity e1 = new CollectionEntity("test_p0e_multi_1", "Multi 1", "sql");
        e1.setSql("SELECT * FROM users; DROP TABLE users");
        e1.setOptions("{}");
        ddlSynchronizer.createCollection(e1, List.of(
                new FieldEntity("test_p0e_multi_1", "id", "bigInt")));

        CollectionEntity e2 = new CollectionEntity("test_p0e_multi_2", "Multi 2", "sql");
        e2.setSql("SELECT * FROM users WHERE status = :undeclared");
        e2.setOptions("{}");
        ddlSynchronizer.createCollection(e2, List.of(
                new FieldEntity("test_p0e_multi_2", "id", "bigInt"),
                new FieldEntity("test_p0e_multi_2", "status", "string")));

        runtimeService.loadAll();

        Map<String, String> invalid = runtimeService.getInvalidCollections();
        assertTrue(invalid.containsKey("test_p0e_multi_1"),
                "First invalid collection should be tracked");
        assertTrue(invalid.containsKey("test_p0e_multi_2"),
                "Second invalid collection should be tracked");

        // Verify both error messages are non-null and non-empty
        assertNotNull(invalid.get("test_p0e_multi_1"));
        assertFalse(invalid.get("test_p0e_multi_1").isEmpty());
        assertNotNull(invalid.get("test_p0e_multi_2"));
        assertFalse(invalid.get("test_p0e_multi_2").isEmpty());

        // Cleanup
        try { ddlSynchronizer.dropCollection("test_p0e_multi_1"); } catch (Exception ignored) {}
        try { ddlSynchronizer.dropCollection("test_p0e_multi_2"); } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("P0-E: invalidCollections error messages do NOT contain SQL text")
    void invalidCollectionsErrorMessagesDoNotContainSql() {
        CollectionEntity entity = new CollectionEntity("test_p0e_no_sql", "No SQL Leak", "sql");
        entity.setSql("SELECT * FROM users WHERE status = :status");
        // Missing required defaultValue
        entity.setOptions("{\"parameters\": [{\"name\": \"status\", \"type\": \"string\", \"required\": true}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity("test_p0e_no_sql", "id", "bigInt"),
                new FieldEntity("test_p0e_no_sql", "status", "string")));

        runtimeService.loadAll();

        Map<String, String> invalid = runtimeService.getInvalidCollections();
        assertTrue(invalid.containsKey("test_p0e_no_sql"));
        String errorMsg = invalid.get("test_p0e_no_sql");

        // Error message should NOT contain the SQL
        assertFalse(errorMsg.contains("SELECT * FROM"),
                "Error message should NOT contain full SQL text");
        assertFalse(errorMsg.contains("users"),
                "Error message should NOT contain table name from SQL");

        // Cleanup
        try { ddlSynchronizer.dropCollection("test_p0e_no_sql"); } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("P0-E: clearInvalidCollections removes all tracked entries")
    void clearInvalidCollectionsRemovesAllEntries() {
        CollectionEntity entity = new CollectionEntity("test_p0e_clear2", "Clear Test 2", "sql");
        entity.setSql("SELECT * FROM users; DROP TABLE users");
        entity.setOptions("{}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity("test_p0e_clear2", "id", "bigInt")));

        runtimeService.loadAll();
        assertTrue(runtimeService.getInvalidCollections().containsKey("test_p0e_clear2"));

        runtimeService.clearInvalidCollections();
        assertTrue(runtimeService.getInvalidCollections().isEmpty(),
                "invalidCollections should be empty after clearInvalidCollections()");

        // Cleanup
        try { ddlSynchronizer.dropCollection("test_p0e_clear2"); } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("P0-E: getInvalidCollections returns unmodifiable map")
    void getInvalidCollectionsReturnsUnmodifiableMap() {
        Map<String, String> invalid = runtimeService.getInvalidCollections();
        assertNotNull(invalid);
        assertThrows(UnsupportedOperationException.class, () -> invalid.put("x", "y"),
                "getInvalidCollections should return an unmodifiable map");
    }

    @Test
    @DisplayName("P0-E: valid collections are NOT in invalidCollections")
    void validCollectionsNotInInvalidCollections() {
        // Create a valid SQL collection
        CollectionEntity entity = new CollectionEntity("test_p0e_valid", "Valid E", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE \"email\" = :email");
        entity.setOptions("{\"parameters\": [{\"name\": \"email\", \"type\": \"string\", \"defaultValue\": \"admin@nocobase.com\"}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity("test_p0e_valid", "id", "bigInt"),
                new FieldEntity("test_p0e_valid", "email", "string")));

        runtimeService.loadAll();

        assertTrue(runtimeService.exists("test_p0e_valid"),
                "Valid SQL collection should be loaded");
        assertFalse(runtimeService.getInvalidCollections().containsKey("test_p0e_valid"),
                "Valid collection should NOT be in invalidCollections");

        // Cleanup
        try { ddlSynchronizer.dropCollection("test_p0e_valid"); } catch (Exception ignored) {}
    }

    // ========== P0-B: reload() invalidCollections lifecycle ==========

    @Test
    @DisplayName("P0-B: loadAll produces invalid collection, fix metadata, reload clears invalid state and registry is readable")
    void reloadClearsInvalidStateAfterFix() {
        // Create an invalid SQL collection
        CollectionEntity entity = new CollectionEntity(P0B_FIX_INVALID, "Fix Invalid Test", "sql");
        entity.setSql("SELECT * FROM users; DROP TABLE users");
        entity.setOptions("{}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(P0B_FIX_INVALID, "id", "bigInt")));

        // loadAll: should be tracked as invalid, not in registry
        runtimeService.loadAll();
        assertTrue(runtimeService.getInvalidCollections().containsKey(P0B_FIX_INVALID),
                "Invalid collection should be tracked after loadAll");
        assertFalse(runtimeService.exists(P0B_FIX_INVALID),
                "Invalid collection should NOT be in registry");

        // Fix the metadata: update SQL to be valid
        collectionRepository.findByName(P0B_FIX_INVALID).ifPresent(c -> {
            c.setSql("SELECT * FROM \"users\"");
            c.setOptions("{\"parameters\": []}");
            collectionRepository.save(c);
        });

        // reload() should succeed, clear invalid state, and make registry readable
        assertDoesNotThrow(() -> runtimeService.reload(P0B_FIX_INVALID));
        assertTrue(runtimeService.exists(P0B_FIX_INVALID),
                "Collection should be in registry after successful reload");
        assertFalse(runtimeService.getInvalidCollections().containsKey(P0B_FIX_INVALID),
                "Invalid collection entry should be removed after successful reload");

        // Verify the definition is readable
        CollectionDefinition def = runtimeService.get(P0B_FIX_INVALID);
        assertNotNull(def);
        assertEquals("sql", def.getType());
    }

    @Test
    @DisplayName("P0-B: existing valid collection, break its SQL metadata, reload marks it invalid and removes old def from registry")
    void reloadMarksInvalidAndRemovesOldDef() {
        // Create a valid SQL collection
        CollectionEntity entity = new CollectionEntity(P0B_BREAK_VALID, "Break Valid Test", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE \"email\" = :email");
        entity.setOptions("{\"parameters\": [{\"name\": \"email\", \"type\": \"string\", \"defaultValue\": \"admin@nocobase.com\"}]}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(P0B_BREAK_VALID, "id", "bigInt"),
                new FieldEntity(P0B_BREAK_VALID, "email", "string")));

        // Initial load: should be in registry
        runtimeService.reload(P0B_BREAK_VALID);
        assertTrue(runtimeService.exists(P0B_BREAK_VALID),
                "Valid collection should be in registry");
        assertFalse(runtimeService.getInvalidCollections().containsKey(P0B_BREAK_VALID),
                "Valid collection should NOT be in invalidCollections");

        // Break the metadata: update SQL to be invalid
        collectionRepository.findByName(P0B_BREAK_VALID).ifPresent(c -> {
            c.setSql("SELECT * FROM users; DROP TABLE users");
            collectionRepository.save(c);
        });

        // reload() should throw, mark invalid, and remove old def from registry
        assertThrows(RuntimeException.class, () -> runtimeService.reload(P0B_BREAK_VALID));
        assertFalse(runtimeService.exists(P0B_BREAK_VALID),
                "Old definition should be removed from registry after failed reload");
        assertTrue(runtimeService.getInvalidCollections().containsKey(P0B_BREAK_VALID),
                "Collection should be tracked in invalidCollections after failed reload");

        // Verify error message does NOT contain SQL
        String errorMsg = runtimeService.getInvalidCollections().get(P0B_BREAK_VALID);
        assertNotNull(errorMsg);
        assertFalse(errorMsg.contains("SELECT * FROM"),
                "Error message should NOT contain SQL text");
    }

    @Test
    @DisplayName("P0-B: two consecutive reload failures — invalid reason is updated, not accumulated")
    void consecutiveReloadFailuresUpdateReason() {
        // Create a SQL collection with invalid SQL (reason A: semicolons)
        CollectionEntity entity = new CollectionEntity(P0B_CONSECUTIVE_FAIL, "Consecutive Fail Test", "sql");
        entity.setSql("SELECT * FROM users; DROP TABLE users");
        entity.setOptions("{}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(P0B_CONSECUTIVE_FAIL, "id", "bigInt")));

        // First reload: fails due to invalid SQL
        assertThrows(RuntimeException.class, () -> runtimeService.reload(P0B_CONSECUTIVE_FAIL));
        String firstError = runtimeService.getInvalidCollections().get(P0B_CONSECUTIVE_FAIL);
        assertNotNull(firstError, "First failure should be tracked");

        // Fix the SQL but introduce a different error (reason B: undeclared parameter)
        collectionRepository.findByName(P0B_CONSECUTIVE_FAIL).ifPresent(c -> {
            c.setSql("SELECT * FROM \"users\" WHERE \"email\" = :undeclared_param");
            c.setOptions("{}");
            collectionRepository.save(c);
        });

        // Second reload: fails due to undeclared parameter
        assertThrows(RuntimeException.class, () -> runtimeService.reload(P0B_CONSECUTIVE_FAIL));
        String secondError = runtimeService.getInvalidCollections().get(P0B_CONSECUTIVE_FAIL);
        assertNotNull(secondError, "Second failure should be tracked");
        assertNotEquals(firstError, secondError,
                "Second failure reason should replace the first, not accumulate");
        assertFalse(secondError.contains("SELECT * FROM"),
                "Error message should NOT contain SQL text");
    }

    // ========== P0-C: SQL/view collection options JSON parse fail-fast ==========

    @Test
    @DisplayName("P0-C: SQL collection with invalid options JSON → loadAll marks invalid")
    void sqlCollectionWithInvalidOptionsJsonMarkedInvalid() {
        // Create a SQL collection with invalid JSON in options
        CollectionEntity entity = new CollectionEntity(P0C_INVALID_JSON, "Invalid JSON Test", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE status = :status");
        entity.setOptions("{invalid json content !!!}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(P0C_INVALID_JSON, "id", "bigInt"),
                new FieldEntity(P0C_INVALID_JSON, "status", "string")));

        // loadAll should not crash, but mark the collection as invalid
        assertDoesNotThrow(() -> runtimeService.loadAll());

        // The collection should be in invalidCollections
        assertTrue(runtimeService.getInvalidCollections().containsKey(P0C_INVALID_JSON),
                "Collection with invalid options JSON should be tracked in invalidCollections");

        // The collection should NOT be in the registry
        assertFalse(runtimeService.exists(P0C_INVALID_JSON),
                "Collection with invalid options JSON should NOT be in registry");

        // Error message should mention invalid options/JSON
        String errorMsg = runtimeService.getInvalidCollections().get(P0C_INVALID_JSON);
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("invalid options JSON") || errorMsg.contains("options"),
                "Error message should mention options JSON parsing failure");
        assertFalse(errorMsg.contains("SELECT * FROM"),
                "Error message should NOT contain SQL text");
    }

    @Test
    @DisplayName("P0-C: reload() with invalid JSON → enters invalid state")
    void reloadWithInvalidJsonEntersInvalidState() {
        // Create a SQL collection with invalid JSON in options
        CollectionEntity entity = new CollectionEntity(P0C_RELOAD_INVALID_JSON, "Reload Invalid JSON", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE status = :status");
        entity.setOptions("{not valid json}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(P0C_RELOAD_INVALID_JSON, "id", "bigInt"),
                new FieldEntity(P0C_RELOAD_INVALID_JSON, "status", "string")));

        // reload() should throw
        assertThrows(RuntimeException.class, () -> runtimeService.reload(P0C_RELOAD_INVALID_JSON));

        // Collection should be in invalidCollections
        assertTrue(runtimeService.getInvalidCollections().containsKey(P0C_RELOAD_INVALID_JSON),
                "Collection with invalid options JSON should be tracked in invalidCollections after reload");

        // Collection should NOT be in registry
        assertFalse(runtimeService.exists(P0C_RELOAD_INVALID_JSON),
                "Collection with invalid options JSON should NOT be in registry after failed reload");
    }

    @Test
    @DisplayName("P0-C: controller returns 404/error envelope, response does NOT contain SQL text")
    void controllerReturnsErrorWithoutSqlText() throws Exception {
        // Create a SQL collection with invalid options JSON
        CollectionEntity entity = new CollectionEntity(P0C_CONTROLLER_INVALID_JSON, "Controller Invalid JSON", "sql");
        entity.setSql("SELECT * FROM \"users\" WHERE status = :status");
        entity.setOptions("{bad json}");
        ddlSynchronizer.createCollection(entity, List.of(
                new FieldEntity(P0C_CONTROLLER_INVALID_JSON, "id", "bigInt"),
                new FieldEntity(P0C_CONTROLLER_INVALID_JSON, "status", "string")));

        // loadAll tracks it as invalid
        runtimeService.loadAll();
        assertTrue(runtimeService.getInvalidCollections().containsKey(P0C_CONTROLLER_INVALID_JSON),
                "Collection should be tracked as invalid");

        // Request the collection's list endpoint — should return 404
        MvcResult result = mockMvc.perform(get("/api/" + P0C_CONTROLLER_INVALID_JSON + ":list")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].message").isString())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> errorResponse = objectMapper.readValue(responseBody, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> errors = (List<Map<String, String>>) errorResponse.get("errors");
        String errorMessage = errors.get(0).get("message");

        // Error response should NOT contain SQL text
        assertFalse(errorMessage.contains("SELECT * FROM"),
                "Error response should NOT contain configured SQL");
        assertFalse(errorMessage.contains("users"),
                "Error response should NOT contain table name from SQL");
        assertFalse(errorMessage.contains("status"),
                "Error response should NOT contain column name from SQL");
    }

    // ========================================================================
    // P0-D1: Relation metadata validation tests
    // ========================================================================

    private String uniqueName(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    @Test
    @DisplayName("P0-D1: Target collection must exist for belongsTo relation")
    void targetCollectionMustExistForBelongsTo() {
        String src = uniqueName("p0d1_src");
        try {
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity nameField = new FieldEntity(src, "name", "string");
            FieldEntity relField = new FieldEntity(src, "bad_target", "belongsTo");
            relField.setTarget("nonexistent_target");
            relField.setForeignKey("bad_target_id");
            ddlSynchronizer.createCollection(srcColl, List.of(nameField, relField));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("does not exist"),
                    "Error should mention target does not exist");
        } finally {
            safeDropCollection(src);
        }
    }

    @Test
    @DisplayName("P0-D1: Through collection must exist for belongsToMany")
    void throughCollectionMustExistForBelongsToMany() {
        String src = uniqueName("p0d1_src");
        String tgt = uniqueName("p0d1_tgt");
        try {
            // Create target first
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with belongsToMany to non-existent through
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity btmRel = new FieldEntity(src, "bad_btm", "belongsToMany");
            btmRel.setTarget(tgt);
            btmRel.setThrough("nonexistent_through");
            btmRel.setForeignKey("source_id");
            btmRel.setOtherKey("target_id");
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, btmRel));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("does not exist"),
                    "Error should mention through collection does not exist");
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P0-D1: sourceKey must exist in source collection for belongsTo")
    void sourceKeyMustExistForBelongsTo() {
        String src = uniqueName("p0d1_src");
        String tgt = uniqueName("p0d1_tgt");
        try {
            // Create target first
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with belongsTo referencing non-existent sourceKey
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity relField = new FieldEntity(src, "bad_rel", "belongsTo");
            relField.setTarget(tgt);
            relField.setForeignKey("bad_fk");
            relField.setSourceKey("nonexistent_source_key"); // doesn't exist in source
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, relField));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("sourceKey"),
                    "Error should mention sourceKey. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P0-D1: sourceKey must be a physical field")
    void sourceKeyMustBePhysicalField() {
        String src = uniqueName("p0d1_src");
        String tgt = uniqueName("p0d1_tgt");
        String thr = uniqueName("p0d1_thr");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create through
            CollectionEntity thrColl = new CollectionEntity(thr, "Through", "physical");
            thrColl.setTableName(thr);
            ddlSynchronizer.createCollection(thrColl, List.of(
                    new FieldEntity(thr, "source_id", "bigInt"),
                    new FieldEntity(thr, "target_id", "bigInt")));
            runtimeService.reload(thr);

            // Create source with belongsToMany using non-physical sourceKey
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity btmRel = new FieldEntity(src, "bad_btm", "belongsToMany");
            btmRel.setTarget(tgt);
            btmRel.setThrough(thr);
            btmRel.setForeignKey("source_id");
            btmRel.setSourceKey("bad_source_key"); // non-existent field in source
            btmRel.setOtherKey("target_id");
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, btmRel));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("not found"),
                    "Error should mention key not found. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
            safeDropCollection(thr);
        }
    }

    @Test
    @DisplayName("P0-D1: Key type compatibility check — string vs number mismatch")
    void keyTypeCompatibilityCheckDetectsMismatch() {
        String src = uniqueName("p0d1_src");
        String tgt = uniqueName("p0d1_tgt");
        try {
            // Create target with bigInt field
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(
                    new FieldEntity(tgt, "name", "string"),
                    new FieldEntity(tgt, "count", "bigInt")));
            runtimeService.reload(tgt);

            // Create source with string field and belongsTo to target's bigInt field
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "code", "string");
            FieldEntity relField = new FieldEntity(src, "type_mismatch", "belongsTo");
            relField.setTarget(tgt);
            relField.setForeignKey("type_mismatch_fk");
            relField.setSourceKey("code"); // string type
            relField.setTargetKey("count"); // bigInt type - mismatch!
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, relField));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("type mismatch"),
                    "Error should mention type mismatch. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P0-D1: belongsTo requires foreignKey")
    void belongsToRequiresForeignKey() {
        String src = uniqueName("p0d1_src");
        String tgt = uniqueName("p0d1_tgt");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with belongsTo but no foreignKey
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity relField = new FieldEntity(src, "no_fk", "belongsTo");
            relField.setTarget(tgt);
            // foreignKey is NOT set
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, relField));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("requires foreignKey"),
                    "Error should mention foreignKey is required. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P0-D1: belongsToMany requires through, foreignKey, and otherKey")
    void belongsToManyRequiresThroughAndKeys() {
        String src = uniqueName("p0d1_src");
        String tgt = uniqueName("p0d1_tgt");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with belongsToMany but no through
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity btmRel = new FieldEntity(src, "no_through", "belongsToMany");
            btmRel.setTarget(tgt);
            btmRel.setForeignKey("source_id");
            btmRel.setOtherKey("target_id");
            // through is NOT set
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, btmRel));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("requires through"),
                    "Error should mention through is required. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P0-D1: Valid relation passes all validation checks")
    void validRelationPassesAllChecks() {
        String src = uniqueName("p0d1_src");
        String tgt = uniqueName("p0d1_tgt");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with valid belongsTo
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity relField = new FieldEntity(src, "valid_rel", "belongsTo");
            relField.setTarget(tgt);
            relField.setForeignKey("valid_fk");
            relField.setSourceKey("name"); // string type in source
            relField.setTargetKey("name"); // string type in target - compatible
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, relField));

            // Should succeed
            assertDoesNotThrow(() -> runtimeService.reload(src));

            CollectionDefinition def = runtimeService.get(src);
            RelationDefinition rel = def.getRelation("valid_rel");
            assertNotNull(rel);
            assertEquals("belongsTo", rel.getType());
            assertEquals(tgt, rel.getTargetCollection());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P0-D1: Relation validation failure marks collection invalid, other collections unaffected")
    void relationValidationFailureMarksCollectionInvalid() {
        String badSrc = uniqueName("p0d1_bad_src");
        String other = uniqueName("p0d1_other");
        try {
            // Create a valid collection first
            CollectionEntity otherColl = new CollectionEntity(other, "Other", "physical");
            otherColl.setTableName(other);
            ddlSynchronizer.createCollection(otherColl, List.of(new FieldEntity(other, "name", "string")));
            runtimeService.reload(other);

            // Create a collection with an invalid relation
            CollectionEntity badColl = new CollectionEntity(badSrc, "Bad Source", "physical");
            badColl.setTableName(badSrc);
            FieldEntity badName = new FieldEntity(badSrc, "name", "string");
            FieldEntity badRel = new FieldEntity(badSrc, "bad_rel", "belongsTo");
            badRel.setTarget("nonexistent_xyz");
            badRel.setForeignKey("bad_fk");
            ddlSynchronizer.createCollection(badColl, List.of(badName, badRel));

            // loadAll should not crash
            assertDoesNotThrow(() -> runtimeService.loadAll());

            // badSrc should be marked invalid
            assertTrue(runtimeService.getInvalidCollections().containsKey(badSrc),
                    "Invalid relation should mark collection as invalid. Invalid: "
                    + runtimeService.getInvalidCollections().keySet());
            assertFalse(runtimeService.exists(badSrc),
                    "Collection with invalid relation should not be in registry");

            // Other collection should still be valid
            assertTrue(runtimeService.exists(other),
                    "Other collections should be unaffected");
        } finally {
            safeDropCollection(badSrc);
            safeDropCollection(other);
        }
    }

    @Test
    @DisplayName("P0-D1: Error messages do NOT contain SQL text")
    void relationValidationErrorMessagesDoNotContainSql() {
        String src = uniqueName("p0d1_src");
        try {
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity badRel = new FieldEntity(src, "no_sql_rel", "belongsTo");
            badRel.setTarget("nonexistent_target");
            badRel.setForeignKey("bad_fk");
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, badRel));

            runtimeService.loadAll();

            String errorMsg = runtimeService.getInvalidCollections().get(src);
            assertNotNull(errorMsg, "Invalid collection should have an error message");
            assertFalse(errorMsg.contains("SELECT"),
                    "Error message should NOT contain SQL. Actual: " + errorMsg);
            assertFalse(errorMsg.contains("CREATE TABLE"),
                    "Error message should NOT contain DDL. Actual: " + errorMsg);
            assertFalse(errorMsg.contains("DROP"),
                    "Error message should NOT contain DDL. Actual: " + errorMsg);
        } finally {
            safeDropCollection(src);
        }
    }

    @Test
    @DisplayName("P0-D1: hasMany requires foreignKey")
    void hasManyRequiresForeignKey() {
        String src = uniqueName("p0d1_src");
        String tgt = uniqueName("p0d1_tgt");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with hasMany but no foreignKey
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity hmRel = new FieldEntity(src, "no_fk_hm", "hasMany");
            hmRel.setTarget(tgt);
            // foreignKey is NOT set
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, hmRel));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("requires foreignKey"),
                    "Error should mention foreignKey is required for hasMany. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P0-D1: Same-type keys (string-to-string) pass compatibility check")
    void sameTypeKeysPassCompatibilityCheck() {
        String src = uniqueName("p0d1_src");
        String tgt = uniqueName("p0d1_tgt");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with string-to-string belongsTo
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "code", "string");
            FieldEntity relField = new FieldEntity(src, "string_rel", "belongsTo");
            relField.setTarget(tgt);
            relField.setForeignKey("string_fk");
            relField.setSourceKey("code"); // string
            relField.setTargetKey("name"); // string - compatible
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, relField));

            assertDoesNotThrow(() -> runtimeService.reload(src));

            CollectionDefinition def = runtimeService.get(src);
            assertNotNull(def.getRelation("string_rel"));
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    private void safeDropCollection(String name) {
        try {
            if (runtimeService.exists(name)) {
                ddlSynchronizer.dropCollection(name);
                runtimeService.reload(name);
            }
        } catch (Exception ignored) {
            // Best-effort cleanup
        }
    }

    // ========================================================================
    // P1-I: belongsTo foreignKey validation tests
    // ========================================================================

    @Test
    @DisplayName("P1-I: belongsTo foreignKey matching a non-physical field is rejected")
    void belongsToForeignKeyMatchingNonPhysicalFieldRejected() {
        String src = uniqueName("p1i_fk_src");
        String tgt = uniqueName("p1i_fk_tgt");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with a hasMany relation field named "ref_id"
            // and a belongsTo field that uses "ref_id" as its foreignKey
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            // A hasMany relation field named "ref_id" — this is NOT a physical field
            FieldEntity hmRel = new FieldEntity(src, "ref_id", "hasMany");
            hmRel.setTarget(tgt);
            hmRel.setForeignKey("source_id");
            // A belongsTo field that uses "ref_id" as its foreignKey — conflict!
            FieldEntity btRel = new FieldEntity(src, "bad_bt", "belongsTo");
            btRel.setTarget(tgt);
            btRel.setForeignKey("ref_id"); // matches the hasMany field name, which is non-physical

            ddlSynchronizer.createCollection(srcColl, List.of(srcName, hmRel, btRel));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("foreignKey") || ex.getMessage().contains("must be a physical field"),
                    "Error should mention foreignKey must be a physical field. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P1-I: belongsTo foreignKey validated successfully for implicit FK (not matching any field)")
    void belongsToForeignKeyNotMatchingAnyFieldIsValid() {
        String src = uniqueName("p1i_fk_src");
        String tgt = uniqueName("p1i_fk_tgt");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with a belongsTo whose foreignKey does NOT match any declared field
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity btRel = new FieldEntity(src, "tag", "belongsTo");
            btRel.setTarget(tgt);
            btRel.setForeignKey("tag_id"); // does NOT match any declared field — valid

            ddlSynchronizer.createCollection(srcColl, List.of(srcName, btRel));

            // Should succeed — foreignKey is an implicit FK column
            assertDoesNotThrow(() -> runtimeService.reload(src));

            CollectionDefinition def = runtimeService.get(src);
            RelationDefinition rel = def.getRelation("tag");
            assertNotNull(rel);
            assertEquals("tag_id", rel.getForeignKey());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P1-I: belongsTo foreignKey matching a physical field is valid")
    void belongsToForeignKeyMatchingPhysicalFieldIsValid() {
        String src = uniqueName("p1i_fk_src");
        String tgt = uniqueName("p1i_fk_tgt");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with a physical bigInt field
            // The belongsTo FK will be validated against this field
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            // Physical field that will serve as the FK column
            FieldEntity srcFkField = new FieldEntity(src, "category_id", "bigInt");

            ddlSynchronizer.createCollection(srcColl, List.of(srcName, srcFkField));
            runtimeService.reload(src);

            // Now add a belongsTo field via metadata-only update (not DDL)
            // The belongsTo field references the existing physical field as its FK
            // We can't use addField() because it would try to add a duplicate column
            // Instead, we directly save the belongsTo field metadata and reload
            FieldEntity btRel = new FieldEntity(src, "category", "belongsTo");
            btRel.setTarget(tgt);
            btRel.setForeignKey("category_id"); // matches the physical field — valid
            btRel.setSortOrder(2);
            btRel.setCreatedAt(java.time.LocalDateTime.now());
            btRel.setUpdatedAt(java.time.LocalDateTime.now());
            fieldRepository.save(btRel);

            // reload() should pass validation because category_id is a physical field
            assertDoesNotThrow(() -> runtimeService.reload(src));

            CollectionDefinition def = runtimeService.get(src);
            assertNotNull(def.getRelation("category"));
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    // ========================================================================
    // P1-I: boolean type compatibility tests
    // ========================================================================

    @Test
    @DisplayName("P1-I: boolean is NOT in the number category — boolean vs bigInt is a type mismatch")
    void booleanNotInNumberCategory() {
        String src = uniqueName("p1i_bool_src");
        String tgt = uniqueName("p1i_bool_tgt");
        try {
            // Create target with bigInt field
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(
                    new FieldEntity(tgt, "name", "string"),
                    new FieldEntity(tgt, "count", "bigInt")));
            runtimeService.reload(tgt);

            // Create source with boolean field and belongsTo to target's bigInt field
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "active", "boolean");
            FieldEntity relField = new FieldEntity(src, "bool_rel", "belongsTo");
            relField.setTarget(tgt);
            relField.setForeignKey("bool_rel_fk");
            relField.setSourceKey("active"); // boolean type
            relField.setTargetKey("count"); // bigInt type - should be mismatch now
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, relField));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("type mismatch"),
                    "Error should mention type mismatch since boolean is not in number category. Actual: "
                    + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P1-I: boolean-to-boolean key compatibility passes")
    void booleanToBooleanKeyCompatibilityPasses() {
        String src = uniqueName("p1i_bool_src");
        String tgt = uniqueName("p1i_bool_tgt");
        try {
            // Create target with boolean field
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(
                    new FieldEntity(tgt, "name", "string"),
                    new FieldEntity(tgt, "enabled", "boolean")));
            runtimeService.reload(tgt);

            // Create source with boolean field and belongsTo to target's boolean field
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "active", "boolean");
            FieldEntity relField = new FieldEntity(src, "bool_rel", "belongsTo");
            relField.setTarget(tgt);
            relField.setForeignKey("bool_rel_fk");
            relField.setSourceKey("active"); // boolean type
            relField.setTargetKey("enabled"); // boolean type - compatible
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, relField));

            // Should succeed — boolean-to-boolean is compatible
            assertDoesNotThrow(() -> runtimeService.reload(src));

            CollectionDefinition def = runtimeService.get(src);
            assertNotNull(def.getRelation("bool_rel"));
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    // ========================================================================
    // P1-F: Index sync in reload tests
    // ========================================================================

    @Test
    @DisplayName("P1-F: index sync is triggered during reload for physical collections")
    void indexSyncTriggeredDuringReload() {
        String src = uniqueName("p1f_idx_src");
        try {
            // Create a collection with a field that has an index option
            CollectionEntity srcColl = new CollectionEntity(src, "IndexTest", "physical");
            srcColl.setTableName(src);
            FieldEntity nameField = new FieldEntity(src, "name", "string");
            FieldEntity codeField = new FieldEntity(src, "code", "string");
            codeField.setOptions("{\"index\": true}");

            ddlSynchronizer.createCollection(srcColl, List.of(nameField, codeField));

            // reload() should trigger index sync and succeed
            assertDoesNotThrow(() -> runtimeService.reload(src));

            // The collection should be in the registry
            assertTrue(runtimeService.exists(src));
        } finally {
            safeDropCollection(src);
        }
    }

    @Test
    @DisplayName("P1-F: index sync skips view/sql collections (metadata only)")
    void indexSyncSkipsViewAndSqlCollections() {
        String sqlSrc = uniqueName("p1f_idx_sql");
        try {
            // Create a SQL collection that queries a known physical table
            // Use a table that's guaranteed to exist in the test DB
            CollectionEntity sqlColl = new CollectionEntity(sqlSrc, "SQL Index Test", "sql");
            sqlColl.setSql("SELECT \"id\" FROM \"collections\"");
            sqlColl.setOptions("{\"primaryKey\": \"id\"}");
            ddlSynchronizer.createCollection(sqlColl, List.of(
                    new FieldEntity(sqlSrc, "id", "bigInt")));

            // reload() should succeed — index sync is skipped for SQL collections
            assertDoesNotThrow(() -> runtimeService.reload(sqlSrc));
            assertTrue(runtimeService.exists(sqlSrc));
        } finally {
            safeDropCollection(sqlSrc);
        }
    }

    @Test
    @DisplayName("P0-A: reload fails on invalid index metadata (fail-fast)")
    void reloadFailsOnInvalidIndexMetadata() {
        String src = uniqueName("p0a_idx_src");
        try {
            // Create a collection with valid fields first
            CollectionEntity srcColl = new CollectionEntity(src, "IndexFailTest", "physical");
            srcColl.setTableName(src);
            FieldEntity nameField = new FieldEntity(src, "name", "string");
            // Field with valid JSON options
            FieldEntity badField = new FieldEntity(src, "bad_field", "string");
            badField.setOptions("{\"index\": true}"); // valid JSON initially

            ddlSynchronizer.createCollection(srcColl, List.of(nameField, badField));

            // Now corrupt the field options to cause IndexDefinition.parse() to fail
            fieldRepository.findByCollectionNameAndName(src, "bad_field").ifPresent(f -> {
                f.setOptions("{invalid json}");
                fieldRepository.save(f);
            });

            // reload() should throw because index metadata parse fails
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("Failed to reload"),
                    "Error should indicate reload failure. Actual: " + ex.getMessage());

            // The collection should NOT be in the registry
            assertFalse(runtimeService.exists(src),
                    "Collection with invalid index metadata should NOT be in registry");

            // The collection should be in invalidCollections
            assertTrue(runtimeService.getInvalidCollections().containsKey(src),
                    "Collection should be tracked in invalidCollections");
        } finally {
            safeDropCollection(src);
        }
    }

    @Test
    @DisplayName("P0-A: loadAll skips collection with invalid index metadata")
    void loadAllSkipsCollectionWithInvalidIndexMetadata() {
        String src = uniqueName("p0a_loadall_idx");
        try {
            // Create a collection with valid field options first
            CollectionEntity srcColl = new CollectionEntity(src, "LoadAllSkipTest", "physical");
            srcColl.setTableName(src);
            FieldEntity nameField = new FieldEntity(src, "name", "string");
            FieldEntity badField = new FieldEntity(src, "bad_field", "string");
            badField.setOptions("{\"index\": true}"); // valid JSON initially

            ddlSynchronizer.createCollection(srcColl, List.of(nameField, badField));

            // Now corrupt the field options to cause IndexDefinition.parse() to fail
            fieldRepository.findByCollectionNameAndName(src, "bad_field").ifPresent(f -> {
                f.setOptions("{invalid json}");
                fieldRepository.save(f);
            });

            // loadAll() should not crash
            assertDoesNotThrow(() -> runtimeService.loadAll());

            // The collection should NOT be in the registry
            assertFalse(runtimeService.exists(src),
                    "Collection with invalid index metadata should be skipped during loadAll");

            // The collection should be in invalidCollections
            assertTrue(runtimeService.getInvalidCollections().containsKey(src),
                    "Collection with invalid index metadata should be tracked in invalidCollections");

            // Verify error message is non-null and non-empty
            String errorMsg = runtimeService.getInvalidCollections().get(src);
            assertNotNull(errorMsg);
            assertFalse(errorMsg.isEmpty());
        } finally {
            safeDropCollection(src);
        }
    }

    // ========================================================================
    // P1-E: Relation key type validation completion tests
    // ========================================================================

    @Test
    @DisplayName("P1-E: belongsTo FK string type incompatible with target id(bigInt) fails")
    void belongsToFkStringTypeIncompatibleWithTargetBigInt() {
        String src = uniqueName("p1e_bt_fk_src");
        String tgt = uniqueName("p1e_bt_fk_tgt");
        try {
            // Create target (physical, id is bigInt)
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with a string field whose name matches the FK.
            // Do NOT include the belongsTo field in createCollection — it would create
            // a duplicate column. Instead add it as metadata only.
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity fkField = new FieldEntity(src, "fk_string", "string");
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, fkField));
            runtimeService.reload(src);

            // Add belongsTo field as metadata only (after table creation)
            FieldEntity btRel = new FieldEntity(src, "bad_bt", "belongsTo");
            btRel.setTarget(tgt);
            btRel.setForeignKey("fk_string"); // matches the string field
            btRel.setSortOrder(2);
            btRel.setCreatedAt(java.time.LocalDateTime.now());
            btRel.setUpdatedAt(java.time.LocalDateTime.now());
            fieldRepository.save(btRel);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("type mismatch"),
                    "Error should mention type mismatch. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P1-E: belongsTo FK bigInt type compatible with target id(bigInt) passes")
    void belongsToFkBigIntTypeCompatibleWithTargetBigInt() {
        String src = uniqueName("p1e_bt_fk_src");
        String tgt = uniqueName("p1e_bt_fk_tgt");
        try {
            // Create target (physical, id is bigInt)
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with a bigInt field whose name matches the FK.
            // Do NOT include the belongsTo field in createCollection to avoid
            // duplicate column. Add it as metadata only.
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity fkField = new FieldEntity(src, "category_id", "bigInt");
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, fkField));
            runtimeService.reload(src);

            // Add belongsTo field as metadata only
            FieldEntity btRel = new FieldEntity(src, "category", "belongsTo");
            btRel.setTarget(tgt);
            btRel.setForeignKey("category_id"); // matches the bigInt field
            btRel.setSortOrder(2);
            btRel.setCreatedAt(java.time.LocalDateTime.now());
            btRel.setUpdatedAt(java.time.LocalDateTime.now());
            fieldRepository.save(btRel);

            assertDoesNotThrow(() -> runtimeService.reload(src));
            CollectionDefinition def = runtimeService.get(src);
            assertNotNull(def.getRelation("category"));
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }

    @Test
    @DisplayName("P1-E: belongsToMany through FK type mismatch with sourceKey fails")
    void belongsToManyThroughFkTypeMismatchFails() {
        String src = uniqueName("p1e_btm_src");
        String tgt = uniqueName("p1e_btm_tgt");
        String thr = uniqueName("p1e_btm_thr");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create through table with string FK column (mismatch with source bigInt id)
            CollectionEntity thrColl = new CollectionEntity(thr, "Through", "physical");
            thrColl.setTableName(thr);
            ddlSynchronizer.createCollection(thrColl, List.of(
                    new FieldEntity(thr, "source_id", "string"), // string type, should be bigInt
                    new FieldEntity(thr, "target_id", "bigInt")));
            runtimeService.reload(thr);

            // Create source
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity btmRel = new FieldEntity(src, "bad_btm", "belongsToMany");
            btmRel.setTarget(tgt);
            btmRel.setThrough(thr);
            btmRel.setForeignKey("source_id"); // string type in through, but sourceKey is bigInt id
            btmRel.setOtherKey("target_id");
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, btmRel));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("type mismatch"),
                    "Error should mention type mismatch. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
            safeDropCollection(thr);
        }
    }

    @Test
    @DisplayName("P1-E: belongsToMany through otherKey type mismatch with targetKey fails")
    void belongsToManyThroughOtherKeyTypeMismatchFails() {
        String src = uniqueName("p1e_btm_ok_src");
        String tgt = uniqueName("p1e_btm_ok_tgt");
        String thr = uniqueName("p1e_btm_ok_thr");
        try {
            // Create target
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target", "physical");
            tgtColl.setTableName(tgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create through table with string otherKey column (mismatch with target bigInt id)
            CollectionEntity thrColl = new CollectionEntity(thr, "Through", "physical");
            thrColl.setTableName(thr);
            ddlSynchronizer.createCollection(thrColl, List.of(
                    new FieldEntity(thr, "source_id", "bigInt"),
                    new FieldEntity(thr, "target_id", "string"))); // string type, should be bigInt
            runtimeService.reload(thr);

            // Create source
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "name", "string");
            FieldEntity btmRel = new FieldEntity(src, "bad_btm_ok", "belongsToMany");
            btmRel.setTarget(tgt);
            btmRel.setThrough(thr);
            btmRel.setForeignKey("source_id"); // bigInt, compatible with sourceKey id
            btmRel.setOtherKey("target_id"); // string type in through, but targetKey is bigInt id
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, btmRel));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("type mismatch"),
                    "Error should mention type mismatch. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
            safeDropCollection(thr);
        }
    }

    @Test
    @DisplayName("P1-E: view/sql target collection primaryKey type participates in compatibility check")
    void viewSqlTargetCollectionPrimaryKeyTypeChecked() {
        String src = uniqueName("p1e_vw_src");
        String tgt = uniqueName("p1e_vw_tgt");
        try {
            // Create a view target collection with explicit primaryKey of type string
            CollectionEntity tgtColl = new CollectionEntity(tgt, "Target View", "view");
            tgtColl.setTableName(tgt);
            tgtColl.setView(true);
            ddlSynchronizer.createCollection(tgtColl, List.of(
                    new FieldEntity(tgt, "id", "string"), // explicit PK of type string
                    new FieldEntity(tgt, "name", "string")));
            runtimeService.reload(tgt);

            // Create source with bigInt sourceKey and belongsTo to view's string PK
            CollectionEntity srcColl = new CollectionEntity(src, "Source", "physical");
            srcColl.setTableName(src);
            FieldEntity srcName = new FieldEntity(src, "count", "bigInt");
            FieldEntity relField = new FieldEntity(src, "vw_rel", "belongsTo");
            relField.setTarget(tgt);
            relField.setForeignKey("vw_fk");
            relField.setSourceKey("count"); // bigInt type
            relField.setTargetKey("id"); // string type in view - mismatch!
            ddlSynchronizer.createCollection(srcColl, List.of(srcName, relField));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(src));
            assertTrue(ex.getMessage().contains("type mismatch"),
                    "Error should mention type mismatch for view/sql target. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(src);
            safeDropCollection(tgt);
        }
    }
}