package com.nocobase;

import com.nocobase.acl.AclService;
import com.nocobase.data.AssociationActionService;
import com.nocobase.data.DynamicRepository;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.*;
import com.nocobase.repository.*;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.runtime.RelationDefinition;
import com.nocobase.web.ForbiddenException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for action scope review fixes: fail-closed scope, strong assertions,
 * real belongsToMany through, SQL identifier validation, no-id field safety.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionScopeRelationReviewTest {

    @Autowired private CollectionRuntimeService runtimeService;
    @Autowired private DdlSynchronizer ddlSynchronizer;
    @Autowired private DynamicRepository dynamicRepository;
    @Autowired private AclService aclService;
    @Autowired private AssociationActionService associationActionService;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RoleResourceRepository roleResourceRepository;
    @Autowired private RoleResourceActionRepository roleResourceActionRepository;
    @Autowired private RoleResourceScopeRepository roleResourceScopeRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final String TEST_COLL = "asr_test_items_v2";
    private Long memberUserId;

    @BeforeAll
    void setUpData() {
        authAsAdmin();
        try {
            CollectionEntity coll = new CollectionEntity(TEST_COLL, "ASR Items", "physical");
            coll.setTableName(TEST_COLL);
            ddlSynchronizer.createCollection(coll, List.of(
                    new FieldEntity(TEST_COLL, "name", "string"),
                    new FieldEntity(TEST_COLL, "owner_id", "bigInt"),
                    new FieldEntity(TEST_COLL, "category", "string")));
            runtimeService.reload(TEST_COLL);
        } catch (Exception e) {
            runtimeService.reload(TEST_COLL);
        }

        String memberEmail = "asr_member@test.com";
        User member = userRepository.findByEmail(memberEmail).orElse(null);
        if (member != null) {
            memberUserId = member.getId();
        } else {
            member = new User();
            member.setEmail(memberEmail);
            member.setNickname("ASR Member");
            member.setPassword("encoded");
            member = userRepository.save(member);
            memberUserId = member.getId();
        }

        // Ensure member role binding exists (avoid duplicate)
        Role memberRole = roleRepository.findByName("member").orElse(null);
        if (memberRole == null) fail("member role not found");
        boolean alreadyBound = userRoleRepository.findByUserId(memberUserId).stream()
                .anyMatch(ur -> ur.getRoleId().equals(memberRole.getId()));
        if (!alreadyBound) {
            UserRole ur = new UserRole();
            ur.setUserId(memberUserId);
            ur.setRoleId(memberRole.getId());
            userRoleRepository.save(ur);
        }
    }

    private void authAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    private void authAsMember() {
        if (memberUserId == null) fail("member user not created");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(memberUserId, null, List.of()));
    }

    // ========== P0-B: Strong action scope assertions ==========

    @Test
    @DisplayName("P0-B: list scope and get scope are different and enforced")
    void listAndGetScopesAreDifferentAndEnforced() {
        authAsAdmin();
        String coll = "asr_list_get_scope_v2";
        createPhysicalCollection(coll);

        // Create records with distinct owner_ids
        Map<String, Object> recordA = dynamicRepository.create(coll,
                Map.of("name", "ListOnly", "owner_id", 1001, "category", "A"));
        Map<String, Object> recordB = dynamicRepository.create(coll,
                Map.of("name", "GetOnly", "owner_id", 2002, "category", "B"));
        Object idA = recordA.get("id");
        Object idB = recordB.get("id");

        // Grant member: list scope on owner_id=1001, get scope on owner_id=2002
        grantMemberAction(coll, "list", "name,owner_id,id");
        grantMemberAction(coll, "get", "name,owner_id,id");
        grantScope(coll, "list", "{\"owner_id\":{\"$eq\":1001}}");
        grantScope(coll, "get", "{\"owner_id\":{\"$eq\":2002}}");

        authAsMember();

        // list: should only see owner_id=1001 records
        var listResult = dynamicRepository.list(coll, null, null, 1, 10, "name,owner_id,id");
        assertFalse(listResult.getData().isEmpty(), "list should return records in list scope");
        boolean hasRecordA = listResult.getData().stream()
                .anyMatch(r -> idA.equals(r.get("id")));
        boolean hasRecordB = listResult.getData().stream()
                .anyMatch(r -> idB.equals(r.get("id")));
        assertTrue(hasRecordA, "list should return record A (in list scope)");
        assertFalse(hasRecordB, "list should NOT return record B (not in list scope)");

        // get: record A should NOT be reachable via get (in get scope only)
        Map<String, Object> getResultA = dynamicRepository.get(coll, idA);
        assertNull(getResultA, "record in list scope but not get scope should be null via get()");

        // get: record B should be reachable via get
        Map<String, Object> getResultB = dynamicRepository.get(coll, idB);
        assertNotNull(getResultB, "record in get scope should be reachable via get()");
        assertEquals("2002", String.valueOf(getResultB.get("owner_id")));
    }

    @Test
    @DisplayName("P0-B: existsInScope with different actions returns different results")
    void existsInScopeWrongActionReturnsFalse() {
        authAsAdmin();
        String coll = "asr_exists_scope_v2";
        createPhysicalCollection(coll);

        Map<String, Object> inListScope = dynamicRepository.create(coll,
                Map.of("name", "InList", "owner_id", 4004));
        Map<String, Object> inUpdateScope = dynamicRepository.create(coll,
                Map.of("name", "InUpdate", "owner_id", 5005));

        // Grant list scope on owner_id=4004, update scope on owner_id=5005
        grantMemberAction(coll, "list", "name,owner_id");
        grantMemberAction(coll, "update", "name,owner_id");
        grantScope(coll, "list", "{\"owner_id\":{\"$eq\":4004}}");
        grantScope(coll, "update", "{\"owner_id\":{\"$eq\":5005}}");

        authAsMember();
        // Record in list scope should pass list existsInScope
        assertTrue(dynamicRepository.existsInScope(coll, "list", inListScope.get("id")),
                "record in list scope should pass list existsInScope");
        // Record NOT in update scope should fail update existsInScope
        assertFalse(dynamicRepository.existsInScope(coll, "update", inListScope.get("id")),
                "record not in update scope should fail update existsInScope");
        // Record in update scope should pass update existsInScope
        assertTrue(dynamicRepository.existsInScope(coll, "update", inUpdateScope.get("id")),
                "record in update scope should pass update existsInScope");
    }

    // ========== P0-C: Real belongsToMany through permission test ==========

    @Test
    @DisplayName("P0-C: belongsToMany add/list/remove/set without through permission")
    void belongsToManyThroughInternalOps() {
        authAsAdmin();
        // Create source, target, through collections
        String srcColl = "asr_articles";
        String tgtColl = "asr_tags";
        String thrColl = "asr_article_tags";
        String assocField = "tags";

        try {
            // Source collection
            CollectionEntity src = new CollectionEntity(srcColl, "Articles", "physical");
            src.setTableName(srcColl);
            FieldEntity srcTitle = new FieldEntity(srcColl, "title", "string");
            ddlSynchronizer.createCollection(src, List.of(srcTitle));
            runtimeService.reload(srcColl);

            // Target collection
            CollectionEntity tgt = new CollectionEntity(tgtColl, "Tags", "physical");
            tgt.setTableName(tgtColl);
            FieldEntity tgtName = new FieldEntity(tgtColl, "name", "string");
            ddlSynchronizer.createCollection(tgt, List.of(tgtName));
            runtimeService.reload(tgtColl);

            // Through collection
            CollectionEntity thr = new CollectionEntity(thrColl, "Article Tags", "physical");
            thr.setTableName(thrColl);
            FieldEntity articleFkField = new FieldEntity(thrColl, "article_id", "bigInt");
            FieldEntity tagFkField = new FieldEntity(thrColl, "tag_id", "bigInt");
            ddlSynchronizer.createCollection(thr, List.of(articleFkField, tagFkField));
            runtimeService.reload(thrColl);

            // Create belongsToMany field metadata on the source collection
            FieldEntity btmField = new FieldEntity(srcColl, assocField, "belongsToMany");
            btmField.setTarget(tgtColl);
            btmField.setThrough(thrColl);
            btmField.setForeignKey("article_id");
            btmField.setOtherKey("tag_id");
            btmField.setSourceKey("id");
            btmField.setTargetKey("id");
            ddlSynchronizer.addField(btmField);

            // Reload so the relation metadata is picked up
            runtimeService.reload(srcColl);

            // Verify relation metadata is correct
            CollectionDefinition srcDef = runtimeService.get(srcColl);
            RelationDefinition rel = srcDef.getRelation(assocField);
            assertNotNull(rel, "belongsToMany relation '" + assocField + "' should be registered");
            assertEquals("belongsToMany", rel.getType());
            assertEquals(tgtColl, rel.getTargetCollection());
            assertEquals(thrColl, rel.getThrough());
            assertEquals("article_id", rel.getForeignKey());
            assertEquals("tag_id", rel.getOtherKey());
            assertEquals("id", rel.getSourceKey());
            assertEquals("id", rel.getTargetKey());

            // Create test data
            Map<String, Object> article = dynamicRepository.create(srcColl, Map.of("title", "Test Article"));
            Map<String, Object> tag1 = dynamicRepository.create(tgtColl, Map.of("name", "Java"));
            Map<String, Object> tag2 = dynamicRepository.create(tgtColl, Map.of("name", "Spring"));
            Map<String, Object> tag3 = dynamicRepository.create(tgtColl, Map.of("name", "Docker"));
            Object articleId = article.get("id");
            Object tag1Id = tag1.get("id");
            Object tag2Id = tag2.get("id");
            Object tag3Id = tag3.get("id");

            // Grant member update on source and target, but NOT on through
            grantMemberAction(srcColl, "update", "title");
            grantMemberAction(tgtColl, "update", "name");
            // Also grant get on source and list on target for the list operation
            grantMemberAction(srcColl, "get", "title,id");
            grantMemberAction(tgtColl, "list", "name,id");

            String assocResource = srcColl + "." + assocField;

            authAsMember();

            // --- add ---
            assertDoesNotThrow(() ->
                    associationActionService.add(assocResource, articleId, tag1Id));
            assertDoesNotThrow(() ->
                    associationActionService.add(assocResource, articleId, tag2Id));

            // --- list ---
            List<Map<String, Object>> associated = associationActionService.list(assocResource, articleId);
            assertNotNull(associated);
            assertEquals(2, associated.size(), "should list 2 associated tags");
            List<String> tagNames = associated.stream()
                    .map(r -> (String) r.get("name"))
                    .sorted()
                    .toList();
            assertEquals(List.of("Java", "Spring"), tagNames);

            // --- remove ---
            assertDoesNotThrow(() ->
                    associationActionService.remove(assocResource, articleId, tag2Id));

            // Verify after remove
            List<Map<String, Object>> afterRemove = associationActionService.list(assocResource, articleId);
            assertEquals(1, afterRemove.size(), "should have 1 tag after remove");
            assertEquals("Java", afterRemove.get(0).get("name"));

            // --- set ---
            assertDoesNotThrow(() ->
                    associationActionService.set(assocResource, articleId, List.of(tag2Id, tag3Id)));

            // Verify after set
            List<Map<String, Object>> afterSet = associationActionService.list(assocResource, articleId);
            assertEquals(2, afterSet.size(), "should have 2 tags after set");
            List<String> afterSetNames = afterSet.stream()
                    .map(r -> (String) r.get("name"))
                    .sorted()
                    .toList();
            assertEquals(List.of("Docker", "Spring"), afterSetNames);

            // Verify that through table operations work without through table permissions
            // (the member has no permissions on the through table at all)
            assertTrue(true, "All association operations succeeded without through table permissions");

        } finally {
            runtimeService.reloadAll();
        }
    }

    // ========== P0-D: Through SQL identifier validation ==========

    @Test
    @DisplayName("P0-D: empty sourceIds returns empty list")
    void emptySourceIdsReturnsEmpty() {
        var result = dynamicRepository.listLinks(TEST_COLL, "owner_id", List.of(), "name");
        assertEquals(0, result.getCount());
    }

    @Test
    @DisplayName("P0-D: invalid sourceKey/otherKey rejected")
    void invalidThroughColumnsRejected() {
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                dynamicRepository.listLinks(TEST_COLL, "nonexistent_col", List.of(1), "name"));
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                dynamicRepository.listLinks(TEST_COLL, "owner_id", List.of(1), "nonexistent_col"));
    }

    @Test
    @DisplayName("P0-D: invalid column name with SQL injection chars rejected")
    void sqlInjectionColumnNameRejected() {
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                dynamicRepository.listLinks(TEST_COLL, "col;DROP TABLE", List.of(1), "name"));
    }

    // ========== P1-E: No-id field permission safety ==========

    @Test
    @DisplayName("P1-E: FieldPermission.none() on row without id does not NPE")
    void fieldPermissionNoneWithoutIdDoesNotNpe() {
        authAsAdmin();
        // Create a fresh collection with no member permissions
        String noPermColl = "asr_no_perm_field";
        try {
            CollectionEntity coll = new CollectionEntity(noPermColl, "No Perm", "physical");
            coll.setTableName(noPermColl);
            ddlSynchronizer.createCollection(coll, List.of(new FieldEntity(noPermColl, "name", "string")));
            runtimeService.reload(noPermColl);

            // Create a record
            Map<String, Object> created = dynamicRepository.create(noPermColl, Map.of("name", "test"));
            assertNotNull(created.get("id"));

            // Switch to member (no list/get permissions on this collection)
            authAsMember();
            // Create a row without id to test filterReadableFields
            Map<String, Object> noIdRow = Map.of("name", "test", "value", 123);
            Map<String, Object> result = aclService.filterReadableFields(noPermColl, noIdRow);
            // Should not NPE, should return empty or id-only map
            assertNotNull(result);
        } finally {
            runtimeService.reload(noPermColl);
        }
    }

    // ========== P1-G: Strong typed assertions ==========

    @Test
    @DisplayName("P1-G: member without list gets ForbiddenException (not Exception)")
    void memberWithoutListGetsForbiddenException() {
        authAsAdmin();
        // Use a fresh collection with no permissions granted
        String freshColl = "asr_no_perm";
        try {
            CollectionEntity coll = new CollectionEntity(freshColl, "No Perm", "physical");
            coll.setTableName(freshColl);
            ddlSynchronizer.createCollection(coll, List.of(new FieldEntity(freshColl, "name", "string")));
            runtimeService.reload(freshColl);

            authAsMember();
            assertThrows(ForbiddenException.class, () ->
                    dynamicRepository.list(freshColl, null, null, 1, 10, null));
        } finally {
            runtimeService.reload(freshColl);
        }
    }

    @Test
    @DisplayName("P1-G: member without create gets ForbiddenException")
    void memberWithoutCreateGetsForbiddenException() {
        authAsMember();
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.create(TEST_COLL, Map.of("name", "test")));
    }

    // ========== P1-H: SQL collection relation/appends contract ==========

    @Test
    @DisplayName("P1-H: SQL collection add/remove/set is rejected via AssociationActionService")
    void sqlCollectionAssociationMutationRejected() {
        authAsAdmin();
        String srcColl = "p1h_src";
        String sqlTargetColl = "p1h_sql_tgt";
        String baseTable = "p1h_base";
        try {
            // Create base table for SQL collection
            if (!runtimeService.exists(baseTable)) {
                CollectionEntity base = new CollectionEntity(baseTable, "P1H Base", "physical");
                base.setTableName(baseTable);
                ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(baseTable, "name", "string"),
                    new FieldEntity(baseTable, "source_id", "bigInt")));
                runtimeService.reload(baseTable);
            }

            // Create SQL collection reading from base table
            if (!runtimeService.exists(sqlTargetColl)) {
                CollectionEntity sqlEnt = new CollectionEntity(sqlTargetColl, "P1H SQL Target", "sql");
                sqlEnt.setSql("SELECT \"id\", \"name\", \"source_id\" FROM \"" + baseTable + "\"");
                sqlEnt.setOptions("{\"primaryKey\": \"id\"}");
                ddlSynchronizer.createCollection(sqlEnt, List.of(
                    new FieldEntity(sqlTargetColl, "id", "bigInt"),
                    new FieldEntity(sqlTargetColl, "name", "string"),
                    new FieldEntity(sqlTargetColl, "source_id", "bigInt")));
                runtimeService.reload(sqlTargetColl);
            }

            // Create source physical collection
            createPhysicalCollection(srcColl);

            // Add hasMany relation field from source to SQL target
            FieldEntity hasManyFld = new FieldEntity(srcColl, "items", "hasMany");
            hasManyFld.setTarget(sqlTargetColl);
            hasManyFld.setForeignKey("source_id");
            hasManyFld.setSourceKey("id");
            hasManyFld.setTargetKey("id");
            ddlSynchronizer.addField(hasManyFld);
            runtimeService.reload(srcColl);

            // Create a source record
            Map<String, Object> source = dynamicRepository.create(srcColl, Map.of("name", "source record"));
            Object sourceId = source.get("id");

            // Grant member update permissions on source and target
            grantMemberAction(srcColl, "update", "name");
            grantMemberAction(sqlTargetColl, "update", "name");
            // Also grant get on source and list on target for the list operation
            grantMemberAction(srcColl, "get", "name,id");
            grantMemberAction(sqlTargetColl, "list", "name,id");

            String assocResource = srcColl + ".items";

            authAsMember();

            // add should be rejected because target is SQL collection
            assertThrows(ForbiddenException.class, () ->
                associationActionService.add(assocResource, sourceId, 1));

            // remove should be rejected because target is SQL collection
            assertThrows(ForbiddenException.class, () ->
                associationActionService.remove(assocResource, sourceId, 1));

            // set should be rejected because target is SQL collection
            assertThrows(ForbiddenException.class, () ->
                associationActionService.set(assocResource, sourceId, List.of(1)));

        } finally {
            runtimeService.reloadAll();
        }
    }

    // ========== Helper methods ==========

    private void grantScope(String resourceName, String action, String scopeJson) {
        Role memberRole = roleRepository.findByName("member").orElse(null);
        if (memberRole == null) fail("member role not found");
        String roleName = memberRole.getName();

        RoleResource rr = roleResourceRepository
                .findByRoleNameAndResourceName(roleName, resourceName)
                .orElseGet(() -> roleResourceRepository.save(new RoleResource(roleName, resourceName)));

        RoleResourceScope scope = new RoleResourceScope(rr.getId(), scopeJson, action);
        roleResourceScopeRepository.save(scope);
    }

    private void grantMemberAction(String resourceName, String action, String fields) {
        Role memberRole = roleRepository.findByName("member").orElse(null);
        if (memberRole == null) fail("member role not found");
        String roleName = memberRole.getName();

        RoleResource rr = roleResourceRepository
                .findByRoleNameAndResourceName(roleName, resourceName)
                .orElseGet(() -> roleResourceRepository.save(new RoleResource(roleName, resourceName)));

        roleResourceActionRepository.save(new RoleResourceAction(rr.getId(), action, fields));
    }

    private void createPhysicalCollection(String name) {
        try {
            if (runtimeService.exists(name)) return;
            CollectionEntity coll = new CollectionEntity(name, name, "physical");
            coll.setTableName(name);
            ddlSynchronizer.createCollection(coll, List.of(
                    new FieldEntity(name, "name", "string"),
                    new FieldEntity(name, "owner_id", "bigInt"),
                    new FieldEntity(name, "category", "string")));
            runtimeService.reload(name);
        } catch (Exception e) {
            runtimeService.reload(name);
        }
    }
}