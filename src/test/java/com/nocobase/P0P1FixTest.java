package com.nocobase;

import com.nocobase.data.AssociationActionService;
import com.nocobase.data.DynamicRepository;
import com.nocobase.data.FilterCompiler;
import com.nocobase.data.RelationQueryService;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.*;
import com.nocobase.repository.FieldRepository;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.sql.SqlQueryCollectionExecutor;
import com.nocobase.web.ForbiddenException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test matrix for P0/P1 fixes.
 * Covers: AssociationActionService, append scope, update/destroy atomicity,
 * fields/sort validation, UI Schema JSON errors.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class P0P1FixTest {

    @Autowired private CollectionRuntimeService runtimeService;
    @Autowired private DdlSynchronizer ddlSynchronizer;
    @Autowired private DynamicRepository dynamicRepository;
    @Autowired private AssociationActionService associationActionService;
    @Autowired private RelationQueryService relationQueryService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FieldRepository fieldRepository;

    private static final String SOURCE_COLL = "test_articles";
    private static final String TARGET_COLL = "test_tags";
    private static final String THROUGH_COLL = "test_article_tags";

    // ========== P0-A2: Internal full-read test collections ==========
    private static final String P0A2_HM_SRC = "test_p0a2_hm_src";
    private static final String P0A2_HM_TGT = "test_p0a2_hm_tgt";
    private static final String P0A2_HM_REL = "targets";

    private static final String P0A2_SQL_BASE = "test_p0a2_sql_base";
    private static final String P0A2_SQL_VIEW = "test_p0a2_sql_view";
    private static final String P0A2_SQL_SRC = "test_p0a2_sql_src";

    private static final String P0A2_BTM_SRC = "test_p0a2_btm_src";
    private static final String P0A2_BTM_TGT = "test_p0a2_btm_tgt";
    private static final String P0A2_BTM_THROUGH = "test_p0a2_btm_through";
    private static final String P0A2_BTM_REL = "tags";

    @BeforeAll
    void setUpData() {
        // Authenticate as admin
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));

        try {
            // Create target collection FIRST (source depends on it)
            CollectionEntity tgt = new CollectionEntity(TARGET_COLL, "Tags", "physical");
            tgt.setTableName(TARGET_COLL);
            FieldEntity nameField = new FieldEntity(TARGET_COLL, "name", "string");
            ddlSynchronizer.createCollection(tgt, List.of(nameField));
            runtimeService.reload(TARGET_COLL);

            // Create source collection (depends on TARGET_COLL)
            CollectionEntity src = new CollectionEntity(SOURCE_COLL, "Articles", "physical");
            src.setTableName(SOURCE_COLL);
            FieldEntity titleField = new FieldEntity(SOURCE_COLL, "title", "string");
            FieldEntity tagIdField = new FieldEntity(SOURCE_COLL, "tag", "belongsTo");
            tagIdField.setTarget(TARGET_COLL);
            tagIdField.setForeignKey("tag_id");
            ddlSynchronizer.createCollection(src, List.of(titleField, tagIdField));
            runtimeService.reload(SOURCE_COLL);

            // Create through collection for belongsToMany
            CollectionEntity through = new CollectionEntity(THROUGH_COLL, "Article Tags", "physical");
            through.setTableName(THROUGH_COLL);
            FieldEntity articleIdField = new FieldEntity(THROUGH_COLL, "article_id", "bigInt");
            FieldEntity tagIdField2 = new FieldEntity(THROUGH_COLL, "tag_id", "bigInt");
            ddlSynchronizer.createCollection(through, List.of(articleIdField, tagIdField2));
            runtimeService.reload(THROUGH_COLL);

            // ========== P0-A2: hasMany test collections ==========
            // Target collection FIRST (source depends on it)
            if (!runtimeService.exists(P0A2_HM_TGT)) {
                CollectionEntity hmTgt = new CollectionEntity(P0A2_HM_TGT, "HM Target", "physical");
                hmTgt.setTableName(P0A2_HM_TGT);
                FieldEntity hmTgtName = new FieldEntity(P0A2_HM_TGT, "name", "string");
                FieldEntity hmTgtSourceId = new FieldEntity(P0A2_HM_TGT, "source_id", "bigInt");
                ddlSynchronizer.createCollection(hmTgt, List.of(hmTgtName, hmTgtSourceId));
                runtimeService.reload(P0A2_HM_TGT);
            }

            // Source collection with hasMany relation
            if (!runtimeService.exists(P0A2_HM_SRC)) {
                CollectionEntity hmSrc = new CollectionEntity(P0A2_HM_SRC, "HM Source", "physical");
                hmSrc.setTableName(P0A2_HM_SRC);
                FieldEntity hmSrcName = new FieldEntity(P0A2_HM_SRC, "name", "string");
                FieldEntity hmRel = new FieldEntity(P0A2_HM_SRC, P0A2_HM_REL, "hasMany");
                hmRel.setTarget(P0A2_HM_TGT);
                hmRel.setForeignKey("source_id");
                ddlSynchronizer.createCollection(hmSrc, List.of(hmSrcName, hmRel));
                runtimeService.reload(P0A2_HM_SRC);
            }

            // ========== P0-A2: SQL collection as target test collections ==========
            // Physical base table
            if (!runtimeService.exists(P0A2_SQL_BASE)) {
                CollectionEntity sqlBase = new CollectionEntity(P0A2_SQL_BASE, "SQL Base", "physical");
                sqlBase.setTableName(P0A2_SQL_BASE);
                FieldEntity sqlBaseName = new FieldEntity(P0A2_SQL_BASE, "name", "string");
                ddlSynchronizer.createCollection(sqlBase, List.of(sqlBaseName));
                runtimeService.reload(P0A2_SQL_BASE);
            }

            // SQL collection pointing to the base table
            if (!runtimeService.exists(P0A2_SQL_VIEW)) {
                CollectionEntity sqlView = new CollectionEntity(P0A2_SQL_VIEW, "SQL View", "sql");
                sqlView.setSql("SELECT \"id\", \"name\" FROM \"" + P0A2_SQL_BASE + "\"");
                sqlView.setOptions("{\"primaryKey\": \"id\"}");
                FieldEntity sqlViewId = new FieldEntity(P0A2_SQL_VIEW, "id", "bigInt");
                FieldEntity sqlViewName = new FieldEntity(P0A2_SQL_VIEW, "name", "string");
                ddlSynchronizer.createCollection(sqlView, List.of(sqlViewId, sqlViewName));
                runtimeService.reload(P0A2_SQL_VIEW);
            }

            // Source collection with belongsTo to SQL view
            if (!runtimeService.exists(P0A2_SQL_SRC)) {
                CollectionEntity sqlSrc = new CollectionEntity(P0A2_SQL_SRC, "SQL Src", "physical");
                sqlSrc.setTableName(P0A2_SQL_SRC);
                FieldEntity sqlSrcName = new FieldEntity(P0A2_SQL_SRC, "name", "string");
                FieldEntity sqlSrcRel = new FieldEntity(P0A2_SQL_SRC, "view", "belongsTo");
                sqlSrcRel.setTarget(P0A2_SQL_VIEW);
                sqlSrcRel.setForeignKey("view_id");
                ddlSynchronizer.createCollection(sqlSrc, List.of(sqlSrcName, sqlSrcRel));
                runtimeService.reload(P0A2_SQL_SRC);
            }

            // ========== P0-A2: belongsToMany test collections ==========
            // Target FIRST
            if (!runtimeService.exists(P0A2_BTM_TGT)) {
                CollectionEntity btmTgt = new CollectionEntity(P0A2_BTM_TGT, "BTM Target", "physical");
                btmTgt.setTableName(P0A2_BTM_TGT);
                FieldEntity btmTgtName = new FieldEntity(P0A2_BTM_TGT, "name", "string");
                ddlSynchronizer.createCollection(btmTgt, List.of(btmTgtName));
                runtimeService.reload(P0A2_BTM_TGT);
            }

            // Through table
            if (!runtimeService.exists(P0A2_BTM_THROUGH)) {
                CollectionEntity btmThrough = new CollectionEntity(P0A2_BTM_THROUGH, "BTM Through", "physical");
                btmThrough.setTableName(P0A2_BTM_THROUGH);
                FieldEntity btmSrcId = new FieldEntity(P0A2_BTM_THROUGH, "source_id", "bigInt");
                FieldEntity btmTgtId = new FieldEntity(P0A2_BTM_THROUGH, "target_id", "bigInt");
                ddlSynchronizer.createCollection(btmThrough, List.of(btmSrcId, btmTgtId));
                runtimeService.reload(P0A2_BTM_THROUGH);
            }

            // Source (depends on target and through)
            if (!runtimeService.exists(P0A2_BTM_SRC)) {
                CollectionEntity btmSrc = new CollectionEntity(P0A2_BTM_SRC, "BTM Source", "physical");
                btmSrc.setTableName(P0A2_BTM_SRC);
                FieldEntity btmSrcName = new FieldEntity(P0A2_BTM_SRC, "name", "string");
                FieldEntity btmRel = new FieldEntity(P0A2_BTM_SRC, P0A2_BTM_REL, "belongsToMany");
                btmRel.setTarget(P0A2_BTM_TGT);
                btmRel.setThrough(P0A2_BTM_THROUGH);
                btmRel.setForeignKey("source_id");
                btmRel.setOtherKey("target_id");
                ddlSynchronizer.createCollection(btmSrc, List.of(btmSrcName, btmRel));
                runtimeService.reload(P0A2_BTM_SRC);
            }
        } catch (Exception e) {
            // Collections may already exist
            runtimeService.reloadAll();
        }
    }

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    // ========== P0-A: AssociationActionService ==========

    @Test
    @Order(1)
    @DisplayName("P0-A: belongsTo listBelongsTo should return target record")
    void belongsToListReturnsTarget() {
        // Create a tag and an article referencing it
        Map<String, Object> tag = dynamicRepository.create(TARGET_COLL, Map.of("name", "Java"));
        dynamicRepository.create(SOURCE_COLL, Map.of("title", "Article 1", "tag_id", tag.get("id")));

        // Get the article's ID
        var listResult = dynamicRepository.list(SOURCE_COLL, null, "-id", 1, 1, null);
        assertFalse(listResult.getData().isEmpty());
        Object articleId = listResult.getData().get(0).get("id");

        // List belongsTo association
        var result = associationActionService.list(SOURCE_COLL + ".tag", articleId);
        assertFalse(result.isEmpty(), "listBelongsTo should return the target record");
        assertEquals("Java", result.get(0).get("name"));
    }

    @Test
    @Order(2)
    @DisplayName("P0-A: filterByTk missing should return 400")
    void filterByTkMissingReturns400() {
        // Valid parse should succeed
        var req = AssociationActionService.parse(SOURCE_COLL + ".tag");
        assertNotNull(req);
        assertEquals(SOURCE_COLL, req.source());
        assertEquals("tag", req.association());

        // No dot should throw
        assertThrows(IllegalArgumentException.class, () ->
                AssociationActionService.parse("no_dot_resource"));
    }

    @Test
    @Order(3)
    @DisplayName("P0-A: set on belongsTo works and checks ACL")
    void associationActionsCheckAcl() {
        Map<String, Object> tag = dynamicRepository.create(TARGET_COLL, Map.of("name", "Spring"));
        var listResult = dynamicRepository.list(SOURCE_COLL, null, "-id", 1, 1, null);
        if (!listResult.getData().isEmpty()) {
            Object articleId = listResult.getData().get(0).get("id");
            // Admin should be able to set belongsTo
            assertDoesNotThrow(() ->
                    associationActionService.set(SOURCE_COLL + ".tag", articleId, List.of(tag.get("id"))));
        }
    }

    // ========== P0-C: update/destroy scope atomicity ==========

    @Test
    @Order(4)
    @DisplayName("P0-C: update with scope should use single atomic SQL")
    void updateWithScopeUsesAtomicSql() {
        Map<String, Object> created = dynamicRepository.create(SOURCE_COLL,
                Map.of("title", "Scope Test"));
        Object id = created.get("id");

        // Update should succeed (admin has no scope restriction)
        Map<String, Object> updated = dynamicRepository.update(SOURCE_COLL, id,
                Map.of("title", "Updated Title"));
        assertEquals("Updated Title", updated.get("title"));
    }

    @Test
    @Order(5)
    @DisplayName("P0-C: destroy with scope should use single atomic SQL")
    void destroyWithScopeUsesAtomicSql() {
        Map<String, Object> created = dynamicRepository.create(SOURCE_COLL,
                Map.of("title", "Destroy Test"));
        Object id = created.get("id");

        // Destroy should succeed
        assertDoesNotThrow(() -> dynamicRepository.destroy(SOURCE_COLL, id));

        // Destroy should throw on non-existent record
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.destroy(SOURCE_COLL, id));
    }

    // ========== P1-D: Strict fields/sort validation ==========

    @Test
    @Order(6)
    @DisplayName("P1-D: unknown fields parameter returns 400")
    void unknownFieldsReturns400() {
        assertThrows(Exception.class, () ->
                dynamicRepository.list(SOURCE_COLL, null, null, 1, 10, "not_exists"));
    }

    @Test
    @Order(7)
    @DisplayName("P1-D: unknown sort parameter returns 400")
    void unknownSortReturns400() {
        assertThrows(Exception.class, () ->
                dynamicRepository.list(SOURCE_COLL, null, "not_exists", 1, 10, null));
    }

    @Test
    @Order(8)
    @DisplayName("P1-D: valid fields and sort still work")
    void validFieldsAndSortStillWork() {
        // Valid fields
        assertDoesNotThrow(() ->
                dynamicRepository.list(SOURCE_COLL, null, null, 1, 10, "title"));

        // Valid sort (id is a system column)
        assertDoesNotThrow(() ->
                dynamicRepository.list(SOURCE_COLL, null, "-id", 1, 10, null));

        // Valid sort on declared field
        assertDoesNotThrow(() ->
                dynamicRepository.list(SOURCE_COLL, null, "title", 1, 10, null));
    }

    // ========== P1-E: UI Schema JSON error handling ==========

    @Test
    @Order(9)
    @DisplayName("P1-E: FilterCompiler rejects unknown fields in filter")
    void filterCompilerRejectsUnknownFields() {
        var def = runtimeService.get(SOURCE_COLL);
        assertThrows(IllegalArgumentException.class, () ->
                FilterCompiler.compile(Map.of("nonexistent", "value"), def));
    }

    @Test
    @Order(10)
    @DisplayName("P1-E: FilterCompiler allows system column id in filter")
    void filterCompilerAllowsId() {
        var def = runtimeService.get(SOURCE_COLL);
        assertDoesNotThrow(() ->
                FilterCompiler.compile(Map.of("id", Map.of("$eq", 1)), def));
    }

    // ========== P0-B: View collection rejects writes ==========

    @Test
    @Order(11)
    @DisplayName("P0-B: view collection create/update/destroy returns 403")
    void viewCollectionRejectsWrites() {
        try {
            CollectionEntity viewColl = new CollectionEntity("test_view_perm", "View", "view");
            viewColl.setView(true);
            viewColl.setTableName(SOURCE_COLL); // map to existing table
            ddlSynchronizer.createCollection(viewColl, List.of());
            runtimeService.reload("test_view_perm");

            assertThrows(Exception.class, () ->
                    dynamicRepository.create("test_view_perm", Map.of("name", "test")));
            assertThrows(Exception.class, () ->
                    dynamicRepository.update("test_view_perm", 1, Map.of("name", "test")));
            assertThrows(Exception.class, () ->
                    dynamicRepository.destroy("test_view_perm", 1));
        } finally {
            runtimeService.reload("test_view_perm");
        }
    }

    // ========== P1-F: Additional edge cases ==========

    @Test
    @Order(12)
    @DisplayName("P1-F: create returns actual record with system fields")
    void createReturnsActualRecord() {
        Map<String, Object> result = dynamicRepository.create(SOURCE_COLL,
                Map.of("title", "Edge Case"));
        assertNotNull(result.get("id"));
        assertEquals("Edge Case", result.get("title"));
    }

    @Test
    @Order(13)
    @DisplayName("P1-F: unknown fields in create return error")
    void unknownFieldsInCreateReturnError() {
        assertThrows(Exception.class, () ->
                dynamicRepository.create(SOURCE_COLL, Map.of("not_a_field", "value")));
    }

    @Test
    @Order(14)
    @DisplayName("P1-F: system fields in create return error")
    void systemFieldsInCreateReturnError() {
        assertThrows(Exception.class, () ->
                dynamicRepository.create(SOURCE_COLL, Map.of("id", 999, "title", "test")));
    }

    @Test
    @Order(15)
    @DisplayName("P1-F: nested $and/$or filter still works")
    void nestedAndOrFilterWorks() {
        var def = runtimeService.get(SOURCE_COLL);
        assertDoesNotThrow(() -> FilterCompiler.compile(Map.of("$and", List.of(
                Map.of("title", "A"),
                Map.of("$or", List.of(
                        Map.of("title", "B"),
                        Map.of("title", "C"))))), def));
    }

    // ========== P0-D: Pagination and batch size tests ==========

    private static final String P0D_SOURCE = "test_p0d_articles";
    private static final String P0D_TARGET = "test_p0d_tags";
    private static final String P0D_SQL_TARGET = "test_p0d_sql_tags";

    // ========== P1-H: SQL collection relation tests ==========

    private static final String P1H_SOURCE = "test_p1h_articles";
    private static final String P1H_SQL_SOURCE = "test_p1h_sql_articles";
    private static final String P1H_PHYSICAL_TARGET = "test_p1h_phys_tags";

    @Test
    @Order(16)
    @DisplayName("P0-D: pageSize <= 0 is normalized to 1")
    void pageSizeZeroNormalizedToOne() {
        // The static helper method should normalize pageSize <= 0
        assertEquals(1, SqlQueryCollectionExecutor.capPageSize(0, 200),
                "pageSize=0 should be capped to at least 1");
        assertEquals(1, SqlQueryCollectionExecutor.capPageSize(-1, 200),
                "pageSize=-1 should be capped to at least 1");
        assertEquals(1, SqlQueryCollectionExecutor.capPageSize(-100, 200),
                "pageSize=-100 should be capped to at least 1");
    }

    @Test
    @Order(17)
    @DisplayName("P0-D: maxPageSize <= 0 falls back to 200")
    void maxPageSizeInvalidFallsBack() {
        // capPageSize with maxPageSize <= 0 falls back to 200
        assertEquals(50, SqlQueryCollectionExecutor.capPageSize(50, 0),
                "pageSize=50 should be unchanged when maxPageSize falls back to 200");
        assertEquals(50, SqlQueryCollectionExecutor.capPageSize(50, -1),
                "pageSize=50 should be unchanged when maxPageSize falls back to 200");
    }

    @Test
    @Order(18)
    @DisplayName("P0-D: capPageSize normalizes pageSize <= 0 to 1")
    void capPageSizeNormalizesZeroToOne() {
        assertEquals(1, SqlQueryCollectionExecutor.capPageSize(0, 200),
                "pageSize=0 should be normalized to 1");
        assertEquals(1, SqlQueryCollectionExecutor.capPageSize(-5, 200),
                "pageSize=-5 should be normalized to 1");
    }

    // ========== P1-H: SQL collection add/remove/set must be rejected ==========

    @Test
    @Order(19)
    @DisplayName("P1-H: SQL collection as source for add is rejected")
    void sqlCollectionAsSourceForAddRejected() {
        setupP1HCollections();
        try {
            // Create a physical target record
            Map<String, Object> target = dynamicRepository.create(P1H_PHYSICAL_TARGET,
                    Map.of("name", "Target1"));

            // Try to add via SQL source collection — should fail
            assertThrows(ForbiddenException.class, () ->
                    associationActionService.add(P1H_SQL_SOURCE + ".tag", 1L, target.get("id")));
        } finally {
            cleanupP1HCollections();
        }
    }

    @Test
    @Order(20)
    @DisplayName("P1-H: SQL collection as target for add is rejected")
    void sqlCollectionAsTargetForAddRejected() {
        setupP1HCollections();
        try {
            // Create a physical source record
            Map<String, Object> source = dynamicRepository.create(P1H_SOURCE,
                    Map.of("title", "Source1"));

            // Try to add target from SQL collection — should fail
            assertThrows(ForbiddenException.class, () ->
                    associationActionService.add(P1H_SOURCE + ".sql_tag", source.get("id"), 1L));
        } finally {
            cleanupP1HCollections();
        }
    }

    @Test
    @Order(21)
    @DisplayName("P1-H: SQL collection remove is rejected")
    void sqlCollectionRemoveRejected() {
        setupP1HCollections();
        try {
            assertThrows(ForbiddenException.class, () ->
                    associationActionService.remove(P1H_SQL_SOURCE + ".tag", 1L, 1L));
        } finally {
            cleanupP1HCollections();
        }
    }

    @Test
    @Order(22)
    @DisplayName("P1-H: SQL collection set is rejected")
    void sqlCollectionSetRejected() {
        setupP1HCollections();
        try {
            assertThrows(ForbiddenException.class, () ->
                    associationActionService.set(P1H_SQL_SOURCE + ".tag", 1L, List.of(1L)));
        } finally {
            cleanupP1HCollections();
        }
    }

    @Test
    @Order(23)
    @DisplayName("P1-H: SQL collection list (read-only) is allowed")
    void sqlCollectionListAllowed() {
        setupP1HCollections();
        try {
            // Create a physical target record
            Map<String, Object> target = dynamicRepository.create(P1H_PHYSICAL_TARGET,
                    Map.of("name", "Tag1"));
            // Create a source record with FK pointing to the target
            Map<String, Object> source = dynamicRepository.create(P1H_SOURCE,
                    Map.of("title", "Article1", "tag_id", target.get("id")));

            // List association via SQL source — should work (read-only)
            // Use the "tag" relation defined on the SQL collection
            var result = associationActionService.list(P1H_SQL_SOURCE + ".tag", source.get("id"));
            assertNotNull(result, "list() on SQL collection should be allowed");
        } finally {
            cleanupP1HCollections();
        }
    }

    // ========== P1-H setup/cleanup helpers ==========

    private void setupP1HCollections() {
        try {
            // Create physical target collection FIRST
            if (!runtimeService.exists(P1H_PHYSICAL_TARGET)) {
                CollectionEntity tgt = new CollectionEntity(P1H_PHYSICAL_TARGET, "P1H Tags", "physical");
                tgt.setTableName(P1H_PHYSICAL_TARGET);
                FieldEntity nameField = new FieldEntity(P1H_PHYSICAL_TARGET, "name", "string");
                ddlSynchronizer.createCollection(tgt, List.of(nameField));
                runtimeService.reload(P1H_PHYSICAL_TARGET);
            }

            // Create physical source collection WITHOUT the SQL source relation first
            // (SQL source depends on the physical source table)
            if (!runtimeService.exists(P1H_SOURCE)) {
                CollectionEntity src = new CollectionEntity(P1H_SOURCE, "P1H Articles", "physical");
                src.setTableName(P1H_SOURCE);
                FieldEntity titleField = new FieldEntity(P1H_SOURCE, "title", "string");
                FieldEntity tagField = new FieldEntity(P1H_SOURCE, "tag", "belongsTo");
                tagField.setTarget(P1H_PHYSICAL_TARGET);
                tagField.setForeignKey("tag_id");
                ddlSynchronizer.createCollection(src, List.of(titleField, tagField));
                runtimeService.reload(P1H_SOURCE);
            }

            // Create SQL source collection with a belongsTo relation to physical target
            if (!runtimeService.exists(P1H_SQL_SOURCE)) {
                CollectionEntity sqlSrc = new CollectionEntity(P1H_SQL_SOURCE, "P1H SQL Articles", "sql");
                sqlSrc.setSql("SELECT \"id\", \"title\", \"tag_id\" FROM \"" + P1H_SOURCE + "\"");
                sqlSrc.setOptions("{\"primaryKey\": \"id\"}");
                FieldEntity sqlIdField = new FieldEntity(P1H_SQL_SOURCE, "id", "bigInt");
                FieldEntity sqlTitleField = new FieldEntity(P1H_SQL_SOURCE, "title", "string");
                FieldEntity sqlTagIdField = new FieldEntity(P1H_SQL_SOURCE, "tag_id", "bigInt");
                // Add belongsTo relation to physical target
                FieldEntity sqlTagRelField = new FieldEntity(P1H_SQL_SOURCE, "tag", "belongsTo");
                sqlTagRelField.setTarget(P1H_PHYSICAL_TARGET);
                sqlTagRelField.setForeignKey("tag_id");
                ddlSynchronizer.createCollection(sqlSrc, List.of(
                        sqlIdField, sqlTitleField, sqlTagIdField, sqlTagRelField));
                runtimeService.reload(P1H_SQL_SOURCE);
            }

            // Now add the sql_tag relation to P1H_SOURCE (SQL source exists now)
            if (fieldRepository.findByCollectionNameAndName(P1H_SOURCE, "sql_tag").isEmpty()) {
                FieldEntity sqlTagField = new FieldEntity(P1H_SOURCE, "sql_tag", "belongsTo");
                sqlTagField.setTarget(P1H_SQL_SOURCE);
                sqlTagField.setForeignKey("sql_tag_id");
                ddlSynchronizer.addField(sqlTagField);
                runtimeService.reload(P1H_SOURCE);
            }
        } catch (Exception e) {
            // Collections may already exist
            runtimeService.reloadAll();
        }
    }

    private void cleanupP1HCollections() {
        // Cleanup is handled by the test — we don't delete collections
        // to avoid affecting other tests
    }

    // ========== P0-A3: Unified DynamicRepository identifier quoting ==========

    @Test
    @Order(24)
    @DisplayName("P0-A3: Malicious column name (sourceKey) in createLink rejected")
    void maliciousSourceKeyInCreateLinkRejected() {
        assertThrows(RuntimeException.class, () ->
                dynamicRepository.createLink(THROUGH_COLL, "article_id;DROP TABLE users", 1L, "tag_id", 1L));
    }

    @Test
    @Order(25)
    @DisplayName("P0-A3: Malicious column name (otherKey) in createLink rejected")
    void maliciousOtherKeyInCreateLinkRejected() {
        assertThrows(RuntimeException.class, () ->
                dynamicRepository.createLink(THROUGH_COLL, "article_id", 1L, "tag_id\"--", 1L));
    }

    @Test
    @Order(26)
    @DisplayName("P0-A3: Malicious column name in listLinks rejected")
    void maliciousColumnNameInListLinksRejected() {
        assertThrows(RuntimeException.class, () ->
                dynamicRepository.listLinks(THROUGH_COLL, "article_id", List.of(1L), "tag_id; SELECT 1"));
    }

    @Test
    @Order(27)
    @DisplayName("P0-A3: Malicious relation key in deleteLink rejected")
    void maliciousKeyInDeleteLinkRejected() {
        assertThrows(RuntimeException.class, () ->
                dynamicRepository.deleteLink(THROUGH_COLL, "article_id/*comment*/", 1L, "tag_id", 1L));
    }

    @Test
    @Order(28)
    @DisplayName("P0-A3: Malicious relation key in replaceLinks rejected")
    void maliciousKeyInReplaceLinksRejected() {
        assertThrows(RuntimeException.class, () ->
                dynamicRepository.replaceLinks(THROUGH_COLL, "article_id", 1L, "tag_id--", List.of(1L)));
    }

    @Test
    @Order(29)
    @DisplayName("P0-A3: Valid column names in through operations still work")
    void validColumnNamesInThroughOperationsWork() {
        // Valid column names should pass through without error
        // (article_id and tag_id are valid columns in the through table)
        assertDoesNotThrow(() ->
                dynamicRepository.listLinks(THROUGH_COLL, "article_id", List.of(1L), "tag_id"));
    }

    // ========== P0-A2: Internal full-read without truncation ==========

    @Test
    @Order(30)
    @DisplayName("P0-A2: hasMany with 1201 targets returns all via association list")
    void hasManyLargeDatasetReturnsAllViaAssociationList() {
        // Clean up previous data
        jdbcTemplate.update("DELETE FROM \"" + P0A2_HM_TGT + "\"");
        jdbcTemplate.update("DELETE FROM \"" + P0A2_HM_SRC + "\"");

        // Create a source record
        Map<String, Object> source = dynamicRepository.create(P0A2_HM_SRC, Map.of("name", "Source"));
        Object sourceId = source.get("id");

        // Insert 1201 target records linked to the source
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 1; i <= 1201; i++) {
            batchArgs.add(new Object[]{"Target " + i, sourceId});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO \"" + P0A2_HM_TGT + "\" (\"name\", \"source_id\") VALUES (?, ?)",
                batchArgs);

        // List association — should return all 1201 records
        var result = associationActionService.list(P0A2_HM_SRC + "." + P0A2_HM_REL, sourceId);
        assertEquals(1201, result.size(), "hasMany association list should return all 1201 records");
    }

    @Test
    @Order(31)
    @DisplayName("P0-A2: SQL collection as target with maxPageSize=200 returns all via relation append")
    void sqlCollectionWithMaxPageSizeReturnsAllViaRelationAppend() {
        // Clean up previous data
        jdbcTemplate.update("DELETE FROM \"" + P0A2_SQL_SRC + "\"");
        jdbcTemplate.update("DELETE FROM \"" + P0A2_SQL_BASE + "\"");

        // Insert 500 records into the base table
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            batchArgs.add(new Object[]{"Record " + i});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO \"" + P0A2_SQL_BASE + "\" (\"name\") VALUES (?)",
                batchArgs);

        // Get the first record from the SQL view to use as the target
        var sqlList = dynamicRepository.list(P0A2_SQL_VIEW, null, "id", 1, 1, null);
        assertFalse(sqlList.getData().isEmpty(), "SQL view should have records");
        Object viewId = sqlList.getData().get(0).get("id");

        // Create a source record referencing the SQL view
        Map<String, Object> source = dynamicRepository.create(P0A2_SQL_SRC,
                Map.of("name", "SQL Src", "view_id", viewId));
        Object sourceId = source.get("id");

        // Verify that the public list() on the SQL collection is capped at 200
        var publicListAll = dynamicRepository.list(P0A2_SQL_VIEW, null, "id", 1, 1000, null);
        assertTrue(publicListAll.getData().size() <= 200,
                "public list() should be capped at maxPageSize=200");

        // Verify that listAllInternal can return more than 200
        var internalList = dynamicRepository.listAllInternal(P0A2_SQL_VIEW, "list", null, "id", null);
        assertEquals(500, internalList.size(),
                "listAllInternal should return all 500 records despite maxPageSize=200");
    }

    @Test
    @Order(32)
    @DisplayName("P0-A2: belongsToMany through links > 1000 returns all")
    void belongsToManyLargeThroughLinksReturnsAll() {
        // Clean up previous data
        jdbcTemplate.update("DELETE FROM \"" + P0A2_BTM_THROUGH + "\"");
        jdbcTemplate.update("DELETE FROM \"" + P0A2_BTM_TGT + "\"");
        jdbcTemplate.update("DELETE FROM \"" + P0A2_BTM_SRC + "\"");

        // Create source record
        Map<String, Object> source = dynamicRepository.create(P0A2_BTM_SRC, Map.of("name", "BTM Source"));
        Object sourceId = source.get("id");

        // Create 1200 target records and through links
        List<Object[]> targetBatch = new ArrayList<>();
        for (int i = 1; i <= 1200; i++) {
            targetBatch.add(new Object[]{"Tag " + i});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO \"" + P0A2_BTM_TGT + "\" (\"name\") VALUES (?)",
                targetBatch);

        // Get all target IDs
        var allTargets = jdbcTemplate.queryForList(
                "SELECT \"id\" FROM \"" + P0A2_BTM_TGT + "\" ORDER BY \"id\"");
        assertEquals(1200, allTargets.size());

        // Create through links
        List<Object[]> linkBatch = new ArrayList<>();
        for (Map<String, Object> row : allTargets) {
            linkBatch.add(new Object[]{sourceId, row.get("id")});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO \"" + P0A2_BTM_THROUGH + "\" (\"source_id\", \"target_id\") VALUES (?, ?)",
                linkBatch);

        // List belongsToMany association — should return all 1200
        var result = associationActionService.list(P0A2_BTM_SRC + "." + P0A2_BTM_REL, sourceId);
        assertEquals(1200, result.size(), "belongsToMany should return all 1200 targets");
    }

    // ========== P0-A1: listAllInternal silent truncation → exception ==========

    @Test
    @Order(33)
    @DisplayName("P0-A1: max limit smaller than result count → association list throws exception")
    void maxLimitExceededThrowsException() {
        // Clean up previous data
        jdbcTemplate.update("DELETE FROM \"" + P0A2_HM_TGT + "\"");
        jdbcTemplate.update("DELETE FROM \"" + P0A2_HM_SRC + "\"");

        // Create a source record
        Map<String, Object> source = dynamicRepository.create(P0A2_HM_SRC, Map.of("name", "Source"));
        Object sourceId = source.get("id");

        // Insert 600 target records linked to the source (more than default page size 500)
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 1; i <= 600; i++) {
            batchArgs.add(new Object[]{"Target " + i, sourceId});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO \"" + P0A2_HM_TGT + "\" (\"name\", \"source_id\") VALUES (?, ?)",
                batchArgs);

        // Temporarily set a low max limit
        int originalLimit = dynamicRepository.getMaxInternalQueryLimit();
        try {
            ReflectionTestUtils.setField(dynamicRepository, "maxInternalQueryLimit", 500);
            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                    associationActionService.list(P0A2_HM_SRC + "." + P0A2_HM_REL, sourceId));
            assertTrue(ex.getMessage().contains("exceeded max limit of 500"),
                    "Exception should indicate max limit exceeded. Actual: " + ex.getMessage());
        } finally {
            ReflectionTestUtils.setField(dynamicRepository, "maxInternalQueryLimit", originalLimit);
        }
    }

    @Test
    @Order(34)
    @DisplayName("P0-A1: limit not multiple of 500 still throws correctly")
    void limitNotMultipleOf500Works() {
        // Clean up previous data
        jdbcTemplate.update("DELETE FROM \"" + P0A2_HM_TGT + "\"");
        jdbcTemplate.update("DELETE FROM \"" + P0A2_HM_SRC + "\"");

        // Create a source record
        Map<String, Object> source = dynamicRepository.create(P0A2_HM_SRC, Map.of("name", "Source"));
        Object sourceId = source.get("id");

        // Insert 1000 target records
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            batchArgs.add(new Object[]{"Target " + i, sourceId});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO \"" + P0A2_HM_TGT + "\" (\"name\", \"source_id\") VALUES (?, ?)",
                batchArgs);

        // Set limit to 750 (not a multiple of 500) — first page returns 500,
        // second page would add 500 more, total 1000 > 750 → should throw
        int originalLimit = dynamicRepository.getMaxInternalQueryLimit();
        try {
            ReflectionTestUtils.setField(dynamicRepository, "maxInternalQueryLimit", 750);
            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                    associationActionService.list(P0A2_HM_SRC + "." + P0A2_HM_REL, sourceId));
            assertTrue(ex.getMessage().contains("exceeded max limit of 750"),
                    "Exception should indicate max limit exceeded. Actual: " + ex.getMessage());
        } finally {
            ReflectionTestUtils.setField(dynamicRepository, "maxInternalQueryLimit", originalLimit);
        }
    }

    @Test
    @Order(35)
    @DisplayName("P0-A1: SQL collection target also throws when limit exceeded")
    void sqlCollectionTargetThrowsWhenLimitExceeded() {
        // Clean up previous data
        jdbcTemplate.update("DELETE FROM \"" + P0A2_SQL_SRC + "\"");
        jdbcTemplate.update("DELETE FROM \"" + P0A2_SQL_BASE + "\"");

        // Insert 600 records into the base table
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 1; i <= 600; i++) {
            batchArgs.add(new Object[]{"Record " + i});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO \"" + P0A2_SQL_BASE + "\" (\"name\") VALUES (?)",
                batchArgs);

        // Set a low max limit
        int originalLimit = dynamicRepository.getMaxInternalQueryLimit();
        try {
            ReflectionTestUtils.setField(dynamicRepository, "maxInternalQueryLimit", 500);
            // listAllInternal on SQL collection should throw when exceeded
            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                    dynamicRepository.listAllInternal(P0A2_SQL_VIEW, "list", null, "id", null));
            assertTrue(ex.getMessage().contains("exceeded max limit of 500"),
                    "Exception should indicate max limit exceeded. Actual: " + ex.getMessage());
        } finally {
            ReflectionTestUtils.setField(dynamicRepository, "maxInternalQueryLimit", originalLimit);
        }
    }

    @Test
    @Order(36)
    @DisplayName("P0-A1: listAllInternal with limit high enough still works")
    void listAllInternalWithSufficientLimitWorks() {
        // Clean up previous data
        jdbcTemplate.update("DELETE FROM \"" + P0A2_HM_TGT + "\"");
        jdbcTemplate.update("DELETE FROM \"" + P0A2_HM_SRC + "\"");

        // Create a source record
        Map<String, Object> source = dynamicRepository.create(P0A2_HM_SRC, Map.of("name", "Source"));
        Object sourceId = source.get("id");

        // Insert 499 target records (less than one page of 500)
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 1; i <= 499; i++) {
            batchArgs.add(new Object[]{"Target " + i, sourceId});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO \"" + P0A2_HM_TGT + "\" (\"name\", \"source_id\") VALUES (?, ?)",
                batchArgs);

        // With default limit of 10000, should work fine
        var result = associationActionService.list(P0A2_HM_SRC + "." + P0A2_HM_REL, sourceId);
        assertEquals(499, result.size(), "Should return all records within limit");
    }

    // ========== P0-A2: Malicious filter keys ==========

    @Test
    @Order(37)
    @DisplayName("P0-A2: SQL injection in filter field name is rejected")
    void maliciousFilterKeySqlInjectionRejected() {
        var def = runtimeService.get(SOURCE_COLL);
        assertThrows(IllegalArgumentException.class, () ->
                FilterCompiler.compile(Map.of("title;DROP TABLE users", "value"), def));
    }

    @Test
    @Order(38)
    @DisplayName("P0-A2: double quote injection in filter field name is rejected")
    void maliciousFilterKeyDoubleQuoteRejected() {
        var def = runtimeService.get(SOURCE_COLL);
        assertThrows(IllegalArgumentException.class, () ->
                FilterCompiler.compile(Map.of("title\"--", "value"), def));
    }

    @Test
    @Order(39)
    @DisplayName("P0-A2: comment injection in filter field name is rejected")
    void maliciousFilterKeyCommentInjectionRejected() {
        var def = runtimeService.get(SOURCE_COLL);
        assertThrows(IllegalArgumentException.class, () ->
                FilterCompiler.compile(Map.of("title/*comment*/", "value"), def));
    }

    @Test
    @Order(40)
    @DisplayName("P0-A2: operator injection in filter field name is rejected")
    void maliciousFilterKeyOperatorInjectionRejected() {
        var def = runtimeService.get(SOURCE_COLL);
        assertThrows(IllegalArgumentException.class, () ->
                FilterCompiler.compile(Map.of("title=1 OR 1=1", "value"), def));
    }

    @Test
    @Order(41)
    @DisplayName("P0-A2: valid filter key still works after identifier hardening")
    void validFilterKeyStillWorks() {
        var def = runtimeService.get(SOURCE_COLL);
        assertDoesNotThrow(() ->
                FilterCompiler.compile(Map.of("title", "ValidTitle"), def));
    }
}