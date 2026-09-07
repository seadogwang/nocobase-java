package com.nocobase;

import com.nocobase.data.DynamicRepository;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.repository.CollectionRepository;
import com.nocobase.repository.FieldRepository;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.runtime.IndexDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-E: DDL boundary regression tests.
 * Verifies that view/sql collections do NOT execute physical DDL operations
 * (ALTER TABLE, DROP TABLE) while physical collections still work normally.
 * <p>
 * P1-F: primaryKey fail-fast regression tests.
 * Verifies validation of custom primary keys during reload.
 * <p>
 * P0-C: Test isolation — each test generates unique collection names,
 * sets up its own data, and cleans up after itself. No shared static state.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DdlBoundaryTest {

    @Autowired private CollectionRuntimeService runtimeService;
    @Autowired private DdlSynchronizer ddlSynchronizer;
    @Autowired private DynamicRepository dynamicRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CollectionRepository collectionRepository;
    @Autowired private FieldRepository fieldRepository;
    @Autowired private ObjectMapper objectMapper;

    private final AtomicInteger counter = new AtomicInteger(0);

    /** Generate a unique collection name within this test class. */
    private String uniqueName(String prefix) {
        return prefix + "_" + counter.incrementAndGet();
    }

    private void authAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @BeforeEach
    void resetAuth() {
        authAsAdmin();
    }

    // ========================================================================
    // P1-E: DDL boundary tests — SQL collections
    // ========================================================================

    @Test
    @DisplayName("P1-E: SQL collection addField only saves metadata, does NOT execute ALTER TABLE ADD COLUMN")
    void sqlAddFieldOnlySavesMetadata() {
        String phyBase = uniqueName("ddl_test_phy_add");
        String sqlColl = uniqueName("ddl_test_sql_add");

        try {
            // Create physical base table
            CollectionEntity base = new CollectionEntity(phyBase, "Physical Base", "physical");
            base.setTableName(phyBase);
            ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(phyBase, "name", "string"),
                    new FieldEntity(phyBase, "value", "string")));
            runtimeService.reload(phyBase);

            // Create SQL collection
            CollectionEntity sqlCollEntity = new CollectionEntity(sqlColl, "SQL Add", "sql");
            sqlCollEntity.setSql("SELECT \"id\", \"name\", \"value\" FROM \"" + phyBase + "\"");
            ddlSynchronizer.createCollection(sqlCollEntity, List.of(
                    new FieldEntity(sqlColl, "id", "bigInt"),
                    new FieldEntity(sqlColl, "name", "string"),
                    new FieldEntity(sqlColl, "value", "string")));
            runtimeService.reload(sqlColl);

            // Verify the physical table does NOT have the "extra_field" column before
            long colCountBefore = countColumns(phyBase, "extra_field");
            assertEquals(0, colCountBefore, "extra_field should not exist in physical table before test");

            // Add a field to the SQL collection
            FieldEntity newField = new FieldEntity(sqlColl, "extra_field", "string");
            FieldEntity saved = ddlSynchronizer.addField(newField);
            assertNotNull(saved.getId(), "Field metadata should be saved");
            assertEquals("extra_field", saved.getName());

            // Verify the field metadata exists in the fields table
            assertTrue(fieldRepository.findByCollectionNameAndName(sqlColl, "extra_field").isPresent(),
                    "Field metadata should exist in fields table");

            // Verify the physical table does NOT have the column (no ALTER TABLE was executed)
            long colCountAfter = countColumns(phyBase, "extra_field");
            assertEquals(0, colCountAfter,
                    "Physical table should NOT have the extra_field column — no ALTER TABLE ADD COLUMN should be executed");

            // Add the extra_field column to the physical table and update the SQL
            // so that field validation passes during reload
            jdbcTemplate.execute("ALTER TABLE \"" + phyBase + "\" ADD COLUMN \"extra_field\" VARCHAR(255)");
            collectionRepository.findByName(sqlColl).ifPresent(c -> {
                c.setSql("SELECT \"id\", \"name\", \"value\", \"extra_field\" FROM \"" + phyBase + "\"");
                collectionRepository.save(c);
            });

            // Reload to verify the runtime registry sees the new field
            runtimeService.reload(sqlColl);
            var def = runtimeService.get(sqlColl);
            assertTrue(def.hasField("extra_field"), "Runtime registry should see the new field");
        } finally {
            safeDropCollection(sqlColl);
            safeDropCollection(phyBase);
        }
    }

    @Test
    @DisplayName("P1-E: SQL collection dropField only deletes metadata, does NOT execute ALTER TABLE DROP COLUMN")
    void sqlDropFieldOnlyDeletesMetadata() {
        String phyBase = uniqueName("ddl_test_phy_drop");
        String sqlColl = uniqueName("ddl_test_sql_drop");

        try {
            // Create physical base table
            CollectionEntity base = new CollectionEntity(phyBase, "Physical Base", "physical");
            base.setTableName(phyBase);
            ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(phyBase, "name", "string"),
                    new FieldEntity(phyBase, "value", "string")));
            runtimeService.reload(phyBase);

            // Create SQL collection with a 'to_drop' field
            // Add to_drop to the physical table and include it in the SQL
            jdbcTemplate.execute("ALTER TABLE \"" + phyBase + "\" ADD COLUMN \"to_drop\" VARCHAR(255)");
            CollectionEntity sqlCollEntity = new CollectionEntity(sqlColl, "SQL Drop", "sql");
            sqlCollEntity.setSql("SELECT \"id\", \"name\", \"value\", \"to_drop\" FROM \"" + phyBase + "\"");
            ddlSynchronizer.createCollection(sqlCollEntity, List.of(
                    new FieldEntity(sqlColl, "id", "bigInt"),
                    new FieldEntity(sqlColl, "name", "string"),
                    new FieldEntity(sqlColl, "to_drop", "string")));
            runtimeService.reload(sqlColl);

            // Verify the field metadata exists before drop
            assertTrue(fieldRepository.findByCollectionNameAndName(sqlColl, "to_drop").isPresent(),
                    "Field metadata should exist before drop");

            // Drop the field from the SQL collection
            ddlSynchronizer.dropField(sqlColl, "to_drop");

            // Verify the field metadata is deleted
            assertTrue(fieldRepository.findByCollectionNameAndName(sqlColl, "to_drop").isEmpty(),
                    "Field metadata should be deleted after drop");

            // Reload to verify the runtime registry no longer sees the field
            runtimeService.reload(sqlColl);
            var def = runtimeService.get(sqlColl);
            assertFalse(def.hasField("to_drop"), "Runtime registry should not see the dropped field");
        } finally {
            safeDropCollection(sqlColl);
            safeDropCollection(phyBase);
        }
    }

    @Test
    @DisplayName("P1-E: SQL collection dropCollection only deletes metadata, does NOT drop the underlying table")
    void sqlDropCollectionOnlyDeletesMetadata() {
        String phyBase = uniqueName("ddl_test_phy_dropcoll");
        String sqlColl = uniqueName("ddl_test_sql_dropcoll");

        // Create physical base table
        CollectionEntity base = new CollectionEntity(phyBase, "Physical Base", "physical");
        base.setTableName(phyBase);
        ddlSynchronizer.createCollection(base, List.of(
                new FieldEntity(phyBase, "name", "string"),
                new FieldEntity(phyBase, "value", "string")));
        runtimeService.reload(phyBase);

        // Create SQL collection
        CollectionEntity sqlCollEntity = new CollectionEntity(sqlColl, "SQL DropColl", "sql");
        sqlCollEntity.setSql("SELECT \"id\", \"name\", \"value\" FROM \"" + phyBase + "\"");
        ddlSynchronizer.createCollection(sqlCollEntity, List.of(
                new FieldEntity(sqlColl, "id", "bigInt"),
                new FieldEntity(sqlColl, "name", "string")));
        runtimeService.reload(sqlColl);

        try {
            // Verify the physical table exists before drop
            assertTrue(tableExists(phyBase), "Physical table should exist before dropping SQL collection");

            // Verify the SQL collection exists
            assertTrue(runtimeService.exists(sqlColl), "SQL collection should exist before drop");

            // Drop the SQL collection
            ddlSynchronizer.dropCollection(sqlColl);

            // Verify the collection metadata is deleted
            assertTrue(collectionRepository.findByName(sqlColl).isEmpty(),
                    "Collection metadata should be deleted after drop");

            // Verify the physical table still exists (was NOT dropped)
            assertTrue(tableExists(phyBase),
                    "Physical table should still exist — dropCollection on SQL collection must NOT drop the underlying table");

            // Verify the SQL collection is removed from runtime registry
            runtimeService.reload(sqlColl);
            assertFalse(runtimeService.exists(sqlColl),
                    "SQL collection should be removed from runtime registry");
        } finally {
            safeDropCollection(phyBase);
        }
    }

    // ========================================================================
    // P1-E: DDL boundary tests — physical collections still work
    // ========================================================================

    @Test
    @DisplayName("P1-E: Physical collection create/add/drop/dropCollection still works normally")
    void physicalCollectionOperationsWorkNormally() {
        String phyWork = uniqueName("ddl_test_phy_work");

        // 1. Create physical collection
        CollectionEntity phyColl = new CollectionEntity(phyWork, "Physical Work", "physical");
        phyColl.setTableName(phyWork);
        FieldEntity nameField = new FieldEntity(phyWork, "title", "string");
        ddlSynchronizer.createCollection(phyColl, List.of(nameField));
        runtimeService.reload(phyWork);

        try {
            // Verify table was created
            assertTrue(tableExists(phyWork), "Physical table should be created");
            assertTrue(columnExists(phyWork, "title"), "Column 'title' should exist in physical table");

            // Verify data operations work
            Map<String, Object> created = dynamicRepository.create(phyWork, Map.of("title", "Hello"));
            assertNotNull(created.get("id"));
            assertEquals("Hello", created.get("title"));

            // 2. Add field to physical collection
            FieldEntity descField = new FieldEntity(phyWork, "description", "string");
            ddlSynchronizer.addField(descField);
            runtimeService.reload(phyWork);

            // Verify column was added to physical table
            assertTrue(columnExists(phyWork, "description"),
                    "Column 'description' should be added to physical table via ALTER TABLE");

            // 3. Drop field from physical collection
            // First add a field to drop
            FieldEntity tempField = new FieldEntity(phyWork, "temp_col", "string");
            ddlSynchronizer.addField(tempField);
            assertTrue(columnExists(phyWork, "temp_col"), "temp_col should exist before drop");

            ddlSynchronizer.dropField(phyWork, "temp_col");
            runtimeService.reload(phyWork);
            assertFalse(columnExists(phyWork, "temp_col"),
                    "Column 'temp_col' should be removed from physical table via ALTER TABLE DROP COLUMN");

            // 4. Drop physical collection
            ddlSynchronizer.dropCollection(phyWork);
            runtimeService.reload(phyWork);

            // Verify table was dropped
            assertFalse(tableExists(phyWork), "Physical table should be dropped");
            assertFalse(runtimeService.exists(phyWork), "Collection should be removed from runtime registry");
        } finally {
            safeDropCollection(phyWork);
        }
    }

    // ========================================================================
    // P1-F: primaryKey fail-fast tests
    // ========================================================================

    @Test
    @DisplayName("P1-F: Legal custom primaryKey passes validation")
    void legalCustomPrimaryKeyPassesValidation() {
        String pkLegal = uniqueName("ddl_test_pk_legal");

        try {
            CollectionEntity coll = new CollectionEntity(pkLegal, "PK Legal", "physical");
            coll.setTableName(pkLegal);
            coll.setOptions("{\"primaryKey\": \"code\"}");
            ddlSynchronizer.createCollection(coll, List.of(
                    new FieldEntity(pkLegal, "code", "string"),
                    new FieldEntity(pkLegal, "label", "string")));

            // Reload should succeed without exception
            assertDoesNotThrow(() -> runtimeService.reload(pkLegal),
                    "Legal custom primaryKey 'code' should pass validation");

            // Verify the primary key is set correctly
            var def = runtimeService.get(pkLegal);
            assertTrue(def.hasPrimaryKey(), "Collection should have a primary key");
            assertEquals("code", def.getPrimaryKeyFieldName(), "Primary key should be 'code'");
        } finally {
            safeDropCollection(pkLegal);
        }
    }

    @Test
    @DisplayName("P1-F: Missing primaryKey field fails reload")
    void missingPrimaryKeyFieldFailsReload() {
        String pkMissing = uniqueName("ddl_test_pk_missing");

        try {
            CollectionEntity coll = new CollectionEntity(pkMissing, "PK Missing", "physical");
            coll.setTableName(pkMissing);
            ddlSynchronizer.createCollection(coll, List.of(
                    new FieldEntity(pkMissing, "name", "string")));
            runtimeService.reload(pkMissing);

            // Update the collection's options to point to a non-existent primary key field
            CollectionEntity entity = collectionRepository.findByName(pkMissing).orElseThrow();
            entity.setOptions("{\"primaryKey\": \"nonexistent_field\"}");
            collectionRepository.save(entity);

            // Reload should fail with RuntimeException wrapping IllegalArgumentException
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(pkMissing),
                    "Reload with missing primaryKey field should throw RuntimeException");
            assertTrue(ex.getCause() instanceof IllegalArgumentException,
                    "Cause should be IllegalArgumentException. Actual: " + ex.getCause());
            assertTrue(ex.getMessage().contains("not found in fields metadata"),
                    "Error message should mention field not found. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(pkMissing);
        }
    }

    @Test
    @DisplayName("P1-F: Relation field as primaryKey fails reload")
    void relationFieldAsPrimaryKeyFailsReload() {
        String pkRelation = uniqueName("ddl_test_pk_relation");
        String pkRelationTgt = uniqueName("ddl_test_pk_rel_tgt");

        try {
            // Create a temporary target collection so the relation validation passes
            CollectionEntity tgtColl = new CollectionEntity(pkRelationTgt, "PK Relation Target", "physical");
            tgtColl.setTableName(pkRelationTgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(
                    new FieldEntity(pkRelationTgt, "name", "string")));
            runtimeService.reload(pkRelationTgt);

            CollectionEntity coll = new CollectionEntity(pkRelation, "PK Relation", "physical");
            coll.setTableName(pkRelation);
            FieldEntity nameField = new FieldEntity(pkRelation, "name", "string");
            FieldEntity belongsToField = new FieldEntity(pkRelation, "category", "belongsTo");
            belongsToField.setTarget(pkRelationTgt);
            belongsToField.setForeignKey("category_id");
            ddlSynchronizer.createCollection(coll, List.of(nameField, belongsToField));
            runtimeService.reload(pkRelation);

            // Update the collection's options to use the relation field as primary key
            CollectionEntity entity = collectionRepository.findByName(pkRelation).orElseThrow();
            entity.setOptions("{\"primaryKey\": \"category\"}");
            collectionRepository.save(entity);

            // Reload should fail — relation field cannot be primary key
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(pkRelation),
                    "Reload with relation field as primaryKey should throw RuntimeException");
            assertTrue(ex.getCause() instanceof IllegalArgumentException,
                    "Cause should be IllegalArgumentException. Actual: " + ex.getCause());
            assertTrue(ex.getMessage().contains("cannot be a relation field"),
                    "Error message should mention relation field. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(pkRelation);
            safeDropCollection(pkRelationTgt);
        }
    }

    @Test
    @DisplayName("P1-F: Non-physical field as primaryKey fails reload")
    void nonPhysicalFieldAsPrimaryKeyFailsReload() {
        String pkNonPhysical = uniqueName("ddl_test_pk_nonphysical");
        String pkNonPhysicalTgt = uniqueName("ddl_test_pk_np_tgt");

        try {
            // Create a temporary target collection so the relation validation passes
            CollectionEntity tgtColl = new CollectionEntity(pkNonPhysicalTgt, "PK NP Target", "physical");
            tgtColl.setTableName(pkNonPhysicalTgt);
            ddlSynchronizer.createCollection(tgtColl, List.of(
                    new FieldEntity(pkNonPhysicalTgt, "name", "string"),
                    new FieldEntity(pkNonPhysicalTgt, "source_id", "bigInt")));
            runtimeService.reload(pkNonPhysicalTgt);

            CollectionEntity coll = new CollectionEntity(pkNonPhysical, "PK NonPhysical", "physical");
            coll.setTableName(pkNonPhysical);
            FieldEntity nameField = new FieldEntity(pkNonPhysical, "name", "string");
            FieldEntity hasManyField = new FieldEntity(pkNonPhysical, "items", "hasMany");
            hasManyField.setTarget(pkNonPhysicalTgt);
            hasManyField.setForeignKey("source_id");
            ddlSynchronizer.createCollection(coll, List.of(nameField, hasManyField));
            runtimeService.reload(pkNonPhysical);

            // Update the collection's options to use the non-physical field as primary key
            CollectionEntity entity = collectionRepository.findByName(pkNonPhysical).orElseThrow();
            entity.setOptions("{\"primaryKey\": \"items\"}");
            collectionRepository.save(entity);

            // Reload should fail — non-physical field cannot be primary key
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> runtimeService.reload(pkNonPhysical),
                    "Reload with non-physical field as primaryKey should throw RuntimeException");
            assertTrue(ex.getCause() instanceof IllegalArgumentException,
                    "Cause should be IllegalArgumentException. Actual: " + ex.getCause());
            assertTrue(ex.getMessage().contains("must be a physical field"),
                    "Error message should mention physical field. Actual: " + ex.getMessage());
        } finally {
            safeDropCollection(pkNonPhysical);
            safeDropCollection(pkNonPhysicalTgt);
        }
    }

    @Test
    @DisplayName("P1-F: SQL/view collection without primaryKey has hasPrimaryKey=false")
    void sqlViewCollectionWithoutPrimaryKeyHasNoPrimaryKey() {
        String phyBase = uniqueName("ddl_test_phy_pk_none");
        String sqlColl = uniqueName("ddl_test_sql_pk_none");

        try {
            // Create physical base table
            CollectionEntity base = new CollectionEntity(phyBase, "Physical Base", "physical");
            base.setTableName(phyBase);
            ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(phyBase, "name", "string"),
                    new FieldEntity(phyBase, "value", "string")));
            runtimeService.reload(phyBase);

            // Create a SQL collection without options.primaryKey
            CollectionEntity coll = new CollectionEntity(sqlColl, "SQL No PK", "sql");
            coll.setSql("SELECT \"id\", \"name\", \"value\" FROM \"" + phyBase + "\"");
            ddlSynchronizer.createCollection(coll, List.of(
                    new FieldEntity(sqlColl, "id", "bigInt"),
                    new FieldEntity(sqlColl, "name", "string")));

            // Reload and verify hasPrimaryKey = false
            runtimeService.reload(sqlColl);
            var def = runtimeService.get(sqlColl);
            assertFalse(def.hasPrimaryKey(),
                    "SQL collection without primaryKey config should have hasPrimaryKey=false");
        } finally {
            safeDropCollection(sqlColl);
            safeDropCollection(phyBase);
        }
    }

    @Test
    @DisplayName("P1-F: SQL collection with valid primaryKey can be used for get/filter")
    void sqlCollectionWithValidPrimaryKeyWorksForGetAndFilter() {
        String phyBase = uniqueName("ddl_test_phy_pk_yes");
        String sqlColl = uniqueName("ddl_test_sql_pk_yes");

        try {
            // Create physical base table
            CollectionEntity base = new CollectionEntity(phyBase, "Physical Base", "physical");
            base.setTableName(phyBase);
            ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(phyBase, "name", "string"),
                    new FieldEntity(phyBase, "value", "string")));
            runtimeService.reload(phyBase);

            // Insert test data
            Map<String, Object> created = dynamicRepository.create(phyBase,
                    Map.of("name", "pk-test", "value", "100"));
            Object pkId = created.get("id");

            // Create a SQL collection with primaryKey = "id"
            CollectionEntity coll = new CollectionEntity(sqlColl, "SQL With PK", "sql");
            coll.setSql("SELECT \"id\", \"name\", \"value\" FROM \"" + phyBase + "\"");
            coll.setOptions("{\"primaryKey\": \"id\"}");
            ddlSynchronizer.createCollection(coll, List.of(
                    new FieldEntity(sqlColl, "id", "bigInt"),
                    new FieldEntity(sqlColl, "name", "string"),
                    new FieldEntity(sqlColl, "value", "string")));

            // Reload and verify primary key
            runtimeService.reload(sqlColl);
            var def = runtimeService.get(sqlColl);
            assertTrue(def.hasPrimaryKey(), "SQL collection with primaryKey config should have hasPrimaryKey=true");
            assertEquals("id", def.getPrimaryKeyFieldName(), "Primary key should be 'id'");

            // Test filter works
            var filterResult = dynamicRepository.list(sqlColl,
                    Map.of("name", "pk-test"), null, 1, 10, null);
            assertEquals(1, filterResult.getCount(), "Filter should return exactly 1 matching record");
            assertEquals("pk-test", filterResult.getData().get(0).get("name"));

            // Test get works (select by primary key)
            Map<String, Object> got = dynamicRepository.get(sqlColl, pkId);
            assertNotNull(got, "get() should return a record for a SQL collection with primary key");
            assertEquals("pk-test", got.get("name"));
        } finally {
            safeDropCollection(sqlColl);
            safeDropCollection(phyBase);
        }
    }

    // ========================================================================
    // P0-C2: Idempotent index sync tests
    // ========================================================================

    @Test
    @DisplayName("P0-C2: syncIndexes creates index on physical collection")
    void syncIndexesCreatesIndexOnPhysicalCollection() {
        String idx1 = uniqueName("ddl_test_idx1");

        try {
            // Create physical collection
            CollectionEntity coll = new CollectionEntity(idx1, "Idx Test 1", "physical");
            coll.setTableName(idx1);
            FieldEntity emailField = new FieldEntity(idx1, "email", "string");
            ddlSynchronizer.createCollection(coll, List.of(emailField));
            runtimeService.reload(idx1);

            // Verify index does not exist yet
            assertFalse(indexExists(idx1, "idx_" + idx1 + "_email"),
                    "Index should not exist before sync");

            // Parse index definitions from field options
            collectionRepository.findByName(idx1).ifPresent(c -> {
                List<FieldEntity> fields = fieldRepository.findByCollectionName(idx1);
                List<IndexDefinition> defs = IndexDefinition.parse(fields, c, objectMapper);
                // No index options set, so no definitions
                assertTrue(defs.isEmpty());
            });

            // Create index definition manually
            IndexDefinition idxDef = IndexDefinition.builder()
                    .name("idx_" + idx1 + "_email")
                    .tableName(idx1)
                    .addColumnName("email")
                    .unique(false)
                    .collectionName(idx1)
                    .build();

            CollectionEntity entity = collectionRepository.findByName(idx1).orElseThrow();
            ddlSynchronizer.syncIndexes(entity, List.of(idxDef));

            // Verify index was created
            assertTrue(indexExists(idx1, "idx_" + idx1 + "_email"),
                    "Index should exist after sync");
        } finally {
            safeDropCollection(idx1);
        }
    }

    @Test
    @DisplayName("P0-C2: syncIndexes is idempotent — existing index skipped")
    void syncIndexesIsIdempotent() {
        String idx2 = uniqueName("ddl_test_idx2");

        try {
            // Create physical collection
            CollectionEntity coll = new CollectionEntity(idx2, "Idx Test 2", "physical");
            coll.setTableName(idx2);
            FieldEntity nameField = new FieldEntity(idx2, "name", "string");
            ddlSynchronizer.createCollection(coll, List.of(nameField));
            runtimeService.reload(idx2);

            IndexDefinition idxDef = IndexDefinition.builder()
                    .name("idx_" + idx2 + "_name")
                    .tableName(idx2)
                    .addColumnName("name")
                    .unique(false)
                    .collectionName(idx2)
                    .build();

            CollectionEntity entity = collectionRepository.findByName(idx2).orElseThrow();

            // First sync — creates the index
            ddlSynchronizer.syncIndexes(entity, List.of(idxDef));
            assertTrue(indexExists(idx2, "idx_" + idx2 + "_name"),
                    "Index should exist after first sync");

            // Second sync — should be idempotent (no error)
            assertDoesNotThrow(() -> ddlSynchronizer.syncIndexes(entity, List.of(idxDef)),
                    "Second sync should be idempotent and not throw");

            // Index should still exist
            assertTrue(indexExists(idx2, "idx_" + idx2 + "_name"),
                    "Index should still exist after second sync");
        } finally {
            safeDropCollection(idx2);
        }
    }

    @Test
    @DisplayName("P0-C2: syncIndexes creates unique index")
    void syncIndexesCreatesUniqueIndex() {
        String idx3 = uniqueName("ddl_test_idx3");

        try {
            // Create physical collection
            CollectionEntity coll = new CollectionEntity(idx3, "Idx Test 3", "physical");
            coll.setTableName(idx3);
            FieldEntity codeField = new FieldEntity(idx3, "code", "string");
            ddlSynchronizer.createCollection(coll, List.of(codeField));
            runtimeService.reload(idx3);

            IndexDefinition udxDef = IndexDefinition.builder()
                    .name("udx_" + idx3 + "_code")
                    .tableName(idx3)
                    .addColumnName("code")
                    .unique(true)
                    .collectionName(idx3)
                    .build();

            CollectionEntity entity = collectionRepository.findByName(idx3).orElseThrow();
            ddlSynchronizer.syncIndexes(entity, List.of(udxDef));

            assertTrue(indexExists(idx3, "udx_" + idx3 + "_code"),
                    "Unique index should exist after sync");
        } finally {
            safeDropCollection(idx3);
        }
    }

    @Test
    @DisplayName("P0-C2: syncIndexes creates multi-column index")
    void syncIndexesCreatesMultiColumnIndex() {
        String idx4 = uniqueName("ddl_test_idx4");

        try {
            // Create physical collection
            CollectionEntity coll = new CollectionEntity(idx4, "Idx Test 4", "physical");
            coll.setTableName(idx4);
            FieldEntity f1 = new FieldEntity(idx4, "first_name", "string");
            FieldEntity f2 = new FieldEntity(idx4, "last_name", "string");
            ddlSynchronizer.createCollection(coll, List.of(f1, f2));
            runtimeService.reload(idx4);

            IndexDefinition multiDef = IndexDefinition.builder()
                    .name("idx_" + idx4 + "_name")
                    .tableName(idx4)
                    .columnNames(List.of("first_name", "last_name"))
                    .unique(false)
                    .collectionName(idx4)
                    .build();

            CollectionEntity entity = collectionRepository.findByName(idx4).orElseThrow();
            ddlSynchronizer.syncIndexes(entity, List.of(multiDef));

            assertTrue(indexExists(idx4, "idx_" + idx4 + "_name"),
                    "Multi-column index should exist after sync");
        } finally {
            safeDropCollection(idx4);
        }
    }

    @Test
    @DisplayName("P0-C2: syncIndexes skips view collections")
    void syncIndexesSkipsViewCollections() {
        String phyBase = uniqueName("ddl_test_idx_view_base");
        String viewColl = uniqueName("ddl_test_idx_view");

        try {
            // Create physical base table
            CollectionEntity base = new CollectionEntity(phyBase, "Physical Base", "physical");
            base.setTableName(phyBase);
            ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(phyBase, "name", "string")));
            runtimeService.reload(phyBase);

            // Create view collection
            CollectionEntity view = new CollectionEntity(viewColl, "View Coll", "view");
            view.setTableName(viewColl);
            ddlSynchronizer.createCollection(view, List.of(
                    new FieldEntity(viewColl, "id", "bigInt"),
                    new FieldEntity(viewColl, "name", "string")));
            runtimeService.reload(viewColl);

            IndexDefinition idxDef = IndexDefinition.builder()
                    .name("idx_" + viewColl + "_name")
                    .tableName(viewColl)
                    .addColumnName("name")
                    .unique(false)
                    .collectionName(viewColl)
                    .build();

            // Sync should not throw, but also not create any index on a view
            CollectionEntity viewEntity = collectionRepository.findByName(viewColl).orElseThrow();
            assertDoesNotThrow(() -> ddlSynchronizer.syncIndexes(viewEntity, List.of(idxDef)),
                    "syncIndexes should not throw for view collections");
        } finally {
            safeDropCollection(viewColl);
            safeDropCollection(phyBase);
        }
    }

    @Test
    @DisplayName("P0-C2: syncIndexes skips SQL collections")
    void syncIndexesSkipsSqlCollections() {
        String phyBase = uniqueName("ddl_test_idx_sql_base");
        String sqlColl = uniqueName("ddl_test_idx_sql");

        try {
            // Create physical base table
            CollectionEntity base = new CollectionEntity(phyBase, "Physical Base", "physical");
            base.setTableName(phyBase);
            ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(phyBase, "name", "string")));
            runtimeService.reload(phyBase);

            // Create SQL collection
            CollectionEntity sql = new CollectionEntity(sqlColl, "SQL Coll", "sql");
            sql.setSql("SELECT \"id\", \"name\" FROM \"" + phyBase + "\"");
            ddlSynchronizer.createCollection(sql, List.of(
                    new FieldEntity(sqlColl, "id", "bigInt"),
                    new FieldEntity(sqlColl, "name", "string")));
            runtimeService.reload(sqlColl);

            IndexDefinition idxDef = IndexDefinition.builder()
                    .name("idx_" + sqlColl + "_name")
                    .tableName(sqlColl)
                    .addColumnName("name")
                    .unique(false)
                    .collectionName(sqlColl)
                    .build();

            // Sync should not throw, but also not create any index on a SQL collection
            CollectionEntity sqlEntity = collectionRepository.findByName(sqlColl).orElseThrow();
            assertDoesNotThrow(() -> ddlSynchronizer.syncIndexes(sqlEntity, List.of(idxDef)),
                    "syncIndexes should not throw for SQL collections");
        } finally {
            safeDropCollection(sqlColl);
            safeDropCollection(phyBase);
        }
    }

    @Test
    @DisplayName("P0-C2: syncIndexes with empty list does nothing")
    void syncIndexesWithEmptyListDoesNothing() {
        String idx5 = uniqueName("ddl_test_idx5");

        try {
            CollectionEntity coll = new CollectionEntity(idx5, "Idx Test 5", "physical");
            coll.setTableName(idx5);
            ddlSynchronizer.createCollection(coll, List.of(
                    new FieldEntity(idx5, "name", "string")));
            runtimeService.reload(idx5);

            CollectionEntity entity = collectionRepository.findByName(idx5).orElseThrow();
            assertDoesNotThrow(() -> ddlSynchronizer.syncIndexes(entity, List.of()),
                    "syncIndexes with empty list should not throw");
        } finally {
            safeDropCollection(idx5);
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private void safeDropCollection(String name) {
        try {
            if (runtimeService.exists(name)) {
                ddlSynchronizer.dropCollection(name);
                runtimeService.reload(name);
            }
        } catch (Exception ignored) {
            // Best-effort cleanup — collection may already be gone
        }
    }

    private boolean tableExists(String tableName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = ?",
                Long.class, tableName.toUpperCase());
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                Long.class, tableName.toUpperCase(), columnName.toUpperCase());
        return count != null && count > 0;
    }

    private long countColumns(String tableName, String columnName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                Long.class, tableName.toUpperCase(), columnName.toUpperCase());
        return count != null ? count : 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME) = ? AND UPPER(INDEX_NAME) = ?",
                Long.class, tableName.toUpperCase(), indexName.toUpperCase());
        return count != null && count > 0;
    }
}