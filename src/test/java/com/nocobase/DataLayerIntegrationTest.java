package com.nocobase;

import com.nocobase.data.FilterCompiler;
import com.nocobase.data.DynamicRepository;
import com.nocobase.ddl.DdlPlan;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.ddl.H2DialectAdapter;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.field.FieldTypeMapper;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.CollectionRuntimeService;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for P0 rework: FilterCompiler, Capability, Write Rules.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataLayerIntegrationTest {

    @Autowired
    private CollectionRuntimeService runtimeService;

    @Autowired
    private DdlSynchronizer ddlSynchronizer;

    @Autowired
    private DynamicRepository dynamicRepository;

    @Autowired
    private H2DialectAdapter dialectAdapter;

    private static final String TEST_COLLECTION = "test_products";

    @BeforeAll
    void setUpCollection() {
        try {
            CollectionEntity collection = new CollectionEntity(TEST_COLLECTION, "Test Products", "physical");
            collection.setTableName(TEST_COLLECTION);
            FieldEntity nameField = new FieldEntity(TEST_COLLECTION, "name", "string");
            FieldEntity priceField = new FieldEntity(TEST_COLLECTION, "price", "float");
            ddlSynchronizer.createCollection(collection, List.of(nameField, priceField));
            runtimeService.reload(TEST_COLLECTION);
        } catch (Exception e) {
            runtimeService.reload(TEST_COLLECTION);
        }
    }

    @BeforeEach
    void setUpAuth() {
        // Authenticate as admin user (userId=1, role=admin from seed data)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    // --- C1: Runtime ---

    @Test
    @DisplayName("C1: Runtime registry should load collections")
    void runtimeRegistryLoads() {
        assertTrue(runtimeService.exists("users"));
        CollectionDefinition usersDef = runtimeService.get("users");
        assertNotNull(usersDef);
        assertEquals("users", usersDef.getTableName());
    }

    @Test
    @DisplayName("C2: Collection capability for physical collections")
    void physicalCollectionCapability() {
        CollectionDefinition usersDef = runtimeService.get("users");
        assertTrue(usersDef.isPhysical());
    }

    // --- E1: DDL ---

    @Test
    @DisplayName("E1: Should create collection with DDL synchronizer")
    void createCollectionWithDdl() {
        assertTrue(runtimeService.exists(TEST_COLLECTION));
        CollectionDefinition def = runtimeService.get(TEST_COLLECTION);
        assertTrue(def.hasField("name"));
        assertTrue(def.hasField("price"));
    }

    // --- D1: CRUD ---

    @Test
    @DisplayName("D1: DynamicRepository create returns actual record with id")
    void dynamicRepositoryCreate() {
        Map<String, Object> data = Map.of("name", "Test Product 1", "price", 99.99);
        Map<String, Object> result = dynamicRepository.create(TEST_COLLECTION, data);
        assertNotNull(result);
        assertNotNull(result.get("id"), "Create should return the actual record with id");
        assertEquals("Test Product 1", result.get("name"));
    }

    @Test
    @DisplayName("D1: DynamicRepository list with pagination")
    void dynamicRepositoryList() {
        dynamicRepository.create(TEST_COLLECTION, Map.of("name", "Product A", "price", 10.0));
        dynamicRepository.create(TEST_COLLECTION, Map.of("name", "Product B", "price", 20.0));

        DynamicRepository.ListResult result = dynamicRepository.list(
                TEST_COLLECTION, null, "-id", 1, 10, null);
        assertNotNull(result.getData());
        assertTrue(result.getCount() >= 2);
    }

    // --- T2: FilterCompiler metadata-ized ---

    @Test
    @DisplayName("T2: FilterCompiler validates field names against metadata")
    void filterCompilerValidatesFields() {
        CollectionDefinition def = runtimeService.get(TEST_COLLECTION);

        // Valid field should work
        assertDoesNotThrow(() ->
                FilterCompiler.compile(Map.of("name", "test"), def));

        // Unknown field should throw
        assertThrows(IllegalArgumentException.class, () ->
                FilterCompiler.compile(Map.of("nonexistent", "value"), def));
    }

    @Test
    @DisplayName("T2: FilterCompiler supports all operators")
    void filterCompilerAllOperators() {
        CollectionDefinition def = runtimeService.get(TEST_COLLECTION);

        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("name", Map.of("$eq", "x")), def));
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("name", Map.of("$ne", "x")), def));
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("price", Map.of("$gt", 10)), def));
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("price", Map.of("$gte", 10)), def));
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("price", Map.of("$lt", 10)), def));
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("price", Map.of("$lte", 10)), def));
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("name", Map.of("$in", List.of("A", "B"))), def));
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("name", Map.of("$null", true)), def));
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("name", Map.of("$notNull", true)), def));
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("name", Map.of("$includes", "test")), def));
    }

    @Test
    @DisplayName("T2: FilterCompiler supports nested $and/$or")
    void filterCompilerNestedLogic() {
        CollectionDefinition def = runtimeService.get(TEST_COLLECTION);

        var andFilter = FilterCompiler.compile(Map.of("$and", List.of(
                Map.of("name", "X"),
                Map.of("price", Map.of("$gt", 5)))), def);
        assertFalse(andFilter.isEmpty());

        var orFilter = FilterCompiler.compile(Map.of("$or", List.of(
                Map.of("name", "X"), Map.of("name", "Y"))), def);
        assertFalse(orFilter.isEmpty());
    }

    @Test
    @DisplayName("T2: FilterCompiler rejects unsupported operators")
    void filterCompilerUnsupportedOperator() {
        CollectionDefinition def = runtimeService.get(TEST_COLLECTION);
        assertThrows(IllegalArgumentException.class, () ->
                FilterCompiler.compile(Map.of("name", Map.of("$unknown", "value")), def));
    }

    // --- T3: Capability enforcement ---

    @Test
    @DisplayName("T3: Physical collection allows CRUD")
    void physicalCollectionAllowsCrud() {
        CollectionDefinition def = runtimeService.get(TEST_COLLECTION);
        assertTrue(def.isPhysical());

        // Should not throw
        assertDoesNotThrow(() -> dynamicRepository.list(TEST_COLLECTION, null, null, 1, 10, null));
        assertDoesNotThrow(() -> dynamicRepository.create(TEST_COLLECTION, Map.of("name", "cap-test", "price", 5.0)));
    }

    @Test
    @DisplayName("T3: View collection rejects writes")
    void viewCollectionRejectsWrites() {
        try {
            // Map view to existing physical table so list works
            CollectionEntity viewColl = new CollectionEntity("test_view_cap", "Test View", "view");
            viewColl.setView(true);
            viewColl.setTableName(TEST_COLLECTION); // map to existing table
            ddlSynchronizer.createCollection(viewColl, List.of());
            runtimeService.reload("test_view_cap");

            // List should work (reading from the mapped table)
            assertDoesNotThrow(() -> dynamicRepository.list("test_view_cap", null, null, 1, 10, null));

            // Create should fail
            assertThrows(Exception.class, () ->
                    dynamicRepository.create("test_view_cap", Map.of("name", "test")));
        } finally {
            runtimeService.reload("test_view_cap");
        }
    }

    // --- T6: Write rules hardening ---

    @Test
    @DisplayName("T6: Unknown fields are rejected")
    void unknownFieldsRejected() {
        assertThrows(Exception.class, () ->
                dynamicRepository.create(TEST_COLLECTION, Map.of("unknown_field", "value")));
    }

    @Test
    @DisplayName("T6: System fields are rejected on write")
    void systemFieldsRejected() {
        assertThrows(Exception.class, () ->
                dynamicRepository.create(TEST_COLLECTION, Map.of("id", 999, "name", "test")));
        assertThrows(Exception.class, () ->
                dynamicRepository.create(TEST_COLLECTION, Map.of("created_at", "now", "name", "test")));
    }

    @Test
    @DisplayName("T6: Create returns actual record with id")
    void createReturnsActualRecord() {
        Map<String, Object> result = dynamicRepository.create(TEST_COLLECTION,
                Map.of("name", "Return Test", "price", 42.0));
        assertNotNull(result.get("id"));
        assertEquals("Return Test", result.get("name"));
        assertEquals(42.0, result.get("price"));
    }

    @Test
    @DisplayName("T6: Update returns actual updated record")
    void updateReturnsActualRecord() {
        Map<String, Object> created = dynamicRepository.create(TEST_COLLECTION,
                Map.of("name", "Update Test", "price", 10.0));
        Object id = created.get("id");

        Map<String, Object> updated = dynamicRepository.update(TEST_COLLECTION, id,
                Map.of("name", "Updated Name"));
        assertNotNull(updated);
        assertEquals("Updated Name", updated.get("name"));
    }

    // --- E2: Field type mapper ---

    @Test
    @DisplayName("E2: Field type mapper should return correct types")
    void fieldTypeMapper() {
        assertEquals("VARCHAR(255)", FieldTypeMapper.getSqlType("string"));
        assertEquals("INTEGER", FieldTypeMapper.getSqlType("integer"));
        assertEquals("BOOLEAN", FieldTypeMapper.getSqlType("boolean"));
        assertTrue(FieldTypeMapper.isScalar("string"));
        assertTrue(FieldTypeMapper.isRelation("belongsTo"));
        assertFalse(FieldTypeMapper.isScalar("belongsTo"));
    }

    @Test
    @DisplayName("E1: H2 dialect adapter should generate correct SQL")
    void h2DialectSql() {
        String sql = dialectAdapter.buildCreateTable("test_table", List.of(
                new DdlPlan.ColumnDef("id", "BIGINT", false, null, true, true),
                new DdlPlan.ColumnDef("name", "VARCHAR(255)")));
        assertTrue(sql.contains("CREATE TABLE"));
        assertTrue(sql.contains("\"test_table\""));
        assertTrue(sql.contains("GENERATED BY DEFAULT AS IDENTITY"));
    }

    @Test
    @DisplayName("C1: Runtime reload should work")
    void runtimeReload() {
        // Reload should preserve at least the system collections
        runtimeService.reloadAll();
        assertTrue(runtimeService.exists("users"),
                "users collection should still exist after reload");
        assertTrue(runtimeService.exists("roles"),
                "roles collection should still exist after reload");
        assertTrue(runtimeService.exists("collections"),
                "collections collection should still exist after reload");
    }
}