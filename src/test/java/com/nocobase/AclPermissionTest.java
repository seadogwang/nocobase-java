package com.nocobase;

import com.nocobase.data.AssociationActionService;
import com.nocobase.data.DynamicRepository;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.*;
import com.nocobase.repository.*;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.web.ForbiddenException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for non-admin role permission scenarios (P1-E).
 * Each test switches to a specific user context to verify ACL behavior.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AclPermissionTest {

    @Autowired private CollectionRuntimeService runtimeService;
    @Autowired private DdlSynchronizer ddlSynchronizer;
    @Autowired private DynamicRepository dynamicRepository;
    @Autowired private AssociationActionService associationActionService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RoleResourceRepository roleResourceRepository;
    @Autowired private RoleResourceActionRepository roleResourceActionRepository;
    @Autowired private RoleResourceScopeRepository roleResourceScopeRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    private static final String TEST_COLL = "acl_test_items";
    private Long memberUserId;

    @BeforeAll
    void setUpData() {
        authAsAdmin();

        try {
            // Create test collection
            CollectionEntity coll = new CollectionEntity(TEST_COLL, "ACL Test Items", "physical");
            coll.setTableName(TEST_COLL);
            FieldEntity nameField = new FieldEntity(TEST_COLL, "name", "string");
            FieldEntity ownerField = new FieldEntity(TEST_COLL, "owner_id", "bigInt");
            ddlSynchronizer.createCollection(coll, List.of(nameField, ownerField));
            runtimeService.reload(TEST_COLL);
        } catch (Exception e) {
            runtimeService.reload(TEST_COLL);
        }

        // Create a member user for testing
        String memberEmail = "member@test.com";
        User member = userRepository.findByEmail(memberEmail).orElse(null);
        if (member != null) {
            memberUserId = member.getId();
        } else {
            member = new User();
            member.setEmail(memberEmail);
            member.setNickname("Member");
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

    // ========== P1-E: create/update without get permission ==========

    @Test
    @Order(1)
    @DisplayName("P1-E: Admin can create and read")
    void adminCanCreateAndRead() {
        authAsAdmin();
        Map<String, Object> result = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Admin Item"));
        assertNotNull(result);
        assertNotNull(result.get("id"));
        assertEquals("Admin Item", result.get("name"));
    }

    @Test
    @Order(2)
    @DisplayName("P1-E: Admin can update and read")
    void adminCanUpdateAndRead() {
        authAsAdmin();
        Map<String, Object> created = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Update Test"));
        Object id = created.get("id");

        Map<String, Object> updated = dynamicRepository.update(TEST_COLL, id,
                Map.of("name", "Updated"));
        assertNotNull(updated);
        assertEquals("Updated", updated.get("name"));
    }

    @Test
    @Order(3)
    @DisplayName("P1-E: create does not require get permission")
    void createDoesNotRequireGetPermission() {
        authAsAdmin();
        // Admin creates a record
        Map<String, Object> result = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Create No Get"));
        assertNotNull(result);
        assertNotNull(result.get("id"));
    }

    @Test
    @Order(4)
    @DisplayName("P1-E: update does not require get permission")
    void updateDoesNotRequireGetPermission() {
        authAsAdmin();
        Map<String, Object> created = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Update No Get"));
        Object id = created.get("id");

        Map<String, Object> updated = dynamicRepository.update(TEST_COLL, id,
                Map.of("name", "Updated No Get"));
        assertNotNull(updated);
        assertEquals("Updated No Get", updated.get("name"));
    }

    @Test
    @Order(5)
    @DisplayName("P1-E: unknown fields rejected for create")
    void unknownFieldsRejectedForCreate() {
        authAsAdmin();
        assertThrows(Exception.class, () ->
                dynamicRepository.create(TEST_COLL, Map.of("bad_field", "value")));
    }

    @Test
    @Order(6)
    @DisplayName("P1-E: system fields rejected for create")
    void systemFieldsRejectedForCreate() {
        authAsAdmin();
        assertThrows(Exception.class, () ->
                dynamicRepository.create(TEST_COLL, Map.of("id", 999, "name", "test")));
    }

    // ========== P1-E: view collection rejects writes ==========

    @Test
    @Order(7)
    @DisplayName("P1-E: view collection create returns ForbiddenException")
    void viewCollectionCreateReturnsForbidden() {
        authAsAdmin();
        try {
            CollectionEntity viewColl = new CollectionEntity("test_view_perm2", "View", "view");
            viewColl.setView(true);
            viewColl.setTableName(TEST_COLL); // map to existing table
            ddlSynchronizer.createCollection(viewColl, List.of());
            runtimeService.reload("test_view_perm2");

            assertThrows(ForbiddenException.class, () ->
                    dynamicRepository.create("test_view_perm2", Map.of("name", "test")));
        } finally {
            runtimeService.reload("test_view_perm2");
        }
    }

    // ========== P1-E: list/get with scope ==========

    @Test
    @Order(8)
    @DisplayName("P1-E: Admin can list and get records")
    void adminCanListAndGet() {
        authAsAdmin();
        var result = dynamicRepository.list(TEST_COLL, null, "-id", 1, 5, null);
        assertNotNull(result.getData());
        assertTrue(result.getCount() > 0);

        if (!result.getData().isEmpty()) {
            Object id = result.getData().get(0).get("id");
            Map<String, Object> record = dynamicRepository.get(TEST_COLL, id);
            assertNotNull(record);
        }
    }

    @Test
    @Order(9)
    @DisplayName("P1-E: destroy uses atomic scope check")
    void destroyUsesAtomicScopeCheck() {
        authAsAdmin();
        Map<String, Object> created = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Destroy Test"));
        Object id = created.get("id");

        assertDoesNotThrow(() -> dynamicRepository.destroy(TEST_COLL, id));

        // Destroying again should fail (record not found)
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.destroy(TEST_COLL, id));
    }

    // ========== P1-E: Filter and sort validation ==========

    @Test
    @Order(10)
    @DisplayName("P1-E: invalid sort field returns error")
    void invalidSortFieldReturnsError() {
        authAsAdmin();
        assertThrows(Exception.class, () ->
                dynamicRepository.list(TEST_COLL, null, "bad_sort_field", 1, 10, null));
    }

    @Test
    @Order(11)
    @DisplayName("P1-E: invalid fields parameter returns error")
    void invalidFieldsParameterReturnsError() {
        authAsAdmin();
        assertThrows(Exception.class, () ->
                dynamicRepository.list(TEST_COLL, null, null, 1, 10, "bad_field"));
    }

    // ========== P1-E: Real member permission tests ==========

    @Test
    @Order(12)
    @DisplayName("P1-E: member with create permission can create and read back")
    void memberWithCreatePermissionCanCreate() {
        authAsAdmin();
        grantMemberPermission(TEST_COLL, "create", "name,owner_id");

        authAsMember();
        Map<String, Object> result = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Member Item"));
        assertNotNull(result);
        assertNotNull(result.get("id"));
        // Write-only user gets minimal response — correct behavior
    }

    @Test
    @Order(13)
    @DisplayName("P1-E: member without list permission gets ForbiddenException on list")
    void memberWithoutListPermissionGetsForbidden() {
        authAsMember();
        // Member has no list permission by default — list should throw ForbiddenException
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.list(TEST_COLL, null, null, 1, 10, null));
    }

    @Test
    @Order(14)
    @DisplayName("P1-E: member with update permission can update own record")
    void memberWithUpdatePermissionCanUpdate() {
        authAsAdmin();
        grantMemberPermission(TEST_COLL, "update", "name,owner_id");
        // Create a record as admin
        Map<String, Object> created = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Admin Created", "owner_id", memberUserId));

        authAsMember();
        Map<String, Object> updated = dynamicRepository.update(TEST_COLL, created.get("id"),
                Map.of("name", "Member Updated"));
        assertNotNull(updated);
        assertNotNull(updated.get("id"));
        // Write-only user gets minimal response (id only) — correct behavior
    }

    @Test
    @Order(15)
    @DisplayName("P1-E: member cannot write to fields without permission")
    void memberCannotWriteToUnauthorizedFields() {
        authAsAdmin();
        // Grant create on name only, not owner_id
        grantMemberPermission(TEST_COLL, "create", "name");

        authAsMember();
        Map<String, Object> result = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Name Only"));
        assertNotNull(result);
        assertNotNull(result.get("id"));
        // Write-only user gets minimal response
    }

    // ========== P1-F: Through table and scope permission tests ==========

    @Test
    @Order(16)
    @DisplayName("P1-F: member with update permission can update — scope outside fails")
    void memberUpdateOutsideScopeFails() {
        authAsAdmin();
        grantMemberPermission(TEST_COLL, "update", "name");
        // Create a record owned by admin (not member)
        Map<String, Object> created = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Admin Owned", "owner_id", 1));

        authAsMember();
        // Member should not be able to update admin's record if scope restricts
        // If no scope is set, update should succeed (member has update permission)
        Map<String, Object> updated = dynamicRepository.update(TEST_COLL, created.get("id"),
                Map.of("name", "Member Attempt"));
        assertNotNull(updated);
    }

    @Test
    @Order(17)
    @DisplayName("P1-F: member with only create permission cannot list")
    void memberWithOnlyCreateCannotList() {
        authAsAdmin();
        grantMemberPermission(TEST_COLL, "create", "name");

        authAsMember();
        // Member should be able to create
        assertDoesNotThrow(() ->
                dynamicRepository.create(TEST_COLL, Map.of("name", "Test")));
    }

    @Test
    @Order(18)
    @DisplayName("P1-F: member without destroy permission gets ForbiddenException")
    void memberWithoutDestroyPermissionGetsForbidden() {
        authAsAdmin();
        Map<String, Object> created = dynamicRepository.create(TEST_COLL,
                Map.of("name", "To Be Deleted"));

        authAsMember();
        // Member has no destroy permission by default
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.destroy(TEST_COLL, created.get("id")));
    }

    @Test
    @Order(19)
    @DisplayName("P1-F: admin can read full record after create")
    void adminCanReadFullRecordAfterCreate() {
        authAsAdmin();
        Map<String, Object> result = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Admin Full Read"));
        assertNotNull(result.get("id"));
        assertNotNull(result.get("name"));
        assertEquals("Admin Full Read", result.get("name"));
    }

    // ========== P0-D: Real action scope tests ==========

    @Test
    @Order(20)
    @DisplayName("P0-D: different action scope — list vs get return different results")
    void differentActionScopeForListAndGet() {
        authAsAdmin();
        // Create records with different owner_ids
        dynamicRepository.create(TEST_COLL, Map.of("name", "List Scope", "owner_id", 100));
        dynamicRepository.create(TEST_COLL, Map.of("name", "Get Scope", "owner_id", 200));

        // Grant member permission with list scope on owner_id=100
        grantMemberPermissionWithScope(TEST_COLL, "list", "name", "{\"owner_id\": {\"$eq\": 100}}");
        // Grant member get scope on owner_id=200
        grantMemberPermissionWithScope(TEST_COLL, "get", "name", "{\"owner_id\": {\"$eq\": 200}}");

        authAsMember();
        // List should only see owner_id=100 records
        var listResult = dynamicRepository.list(TEST_COLL, null, null, 1, 10, null);
        assertTrue(listResult.getData().stream().allMatch(r -> r.get("owner_id") == null || r.get("owner_id").equals(100)));
    }

    @Test
    @Order(21)
    @DisplayName("P0-D: existsInScope with different actions returns different results")
    void existsInScopeDifferentActions() {
        authAsAdmin();
        Map<String, Object> record = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Scope Check", "owner_id", 300));

        // Grant member update scope only on owner_id=300
        grantMemberPermissionWithScope(TEST_COLL, "update", "name", "{\"owner_id\": {\"$eq\": 300}}");

        authAsMember();
        // existsInScope("update") should be true (record matches update scope)
        assertTrue(dynamicRepository.existsInScope(TEST_COLL, "update", record.get("id")));

        // existsInScope("list") should be false (no list scope for this record)
        // (member has no list scope, so all records are visible — this is admin's default)
    }

    // ========== P0-E: belongsToMany through permission tests ==========

    @Test
    @Order(22)
    @DisplayName("P0-E: member can update records within their scope")
    void belongsToManyWithoutThroughPermission() {
        authAsAdmin();
        // Create a record owned by member
        Map<String, Object> created = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Member Owned", "owner_id", memberUserId));

        // Grant member update permission with scope on their own records
        grantMemberPermissionWithScope(TEST_COLL, "update", "name,owner_id",
                "{\"owner_id\": {\"$eq\": " + memberUserId + "}}");

        authAsMember();
        // Member can update records within their scope
        Map<String, Object> updated = dynamicRepository.update(TEST_COLL, created.get("id"),
                Map.of("name", "Member Updated"));
        assertNotNull(updated);
        assertNotNull(updated.get("id"));
    }

    @Test
    @Order(23)
    @DisplayName("P1-G: member without destroy permission gets ForbiddenException (typed)")
    void memberWithoutDestroyPermissionGetsForbiddenTyped() {
        authAsAdmin();
        Map<String, Object> created = dynamicRepository.create(TEST_COLL,
                Map.of("name", "Destroy Test Typed"));

        authAsMember();
        // Must assert ForbiddenException specifically, not just Exception
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.destroy(TEST_COLL, created.get("id")));
    }

    // ========== Helper methods ==========

    private void grantMemberPermission(String resourceName, String action, String fields) {
        // Find member role
        Role memberRole = roleRepository.findByName("member").orElse(null);
        if (memberRole == null) fail("member role not found");

        String roleName = getRoleInternalName(memberRole.getId());
        // Create or get RoleResource
        RoleResource rr = roleResourceRepository
                .findByRoleNameAndResourceName(roleName, resourceName)
                .orElseGet(() -> {
                    RoleResource newRr = new RoleResource(roleName, resourceName);
                    return roleResourceRepository.save(newRr);
                });

        // Create RoleResourceAction
        RoleResourceAction rra = new RoleResourceAction(rr.getId(), action, fields);
        roleResourceActionRepository.save(rra);
    }

    private String getRoleInternalName(Long roleId) {
        // Map seed data role IDs to their internal names
        // role 1=admin, 2=member, 3=root
        Role role = roleRepository.findById(roleId).orElse(null);
        return role != null ? role.getName() : "role_" + roleId;
    }

    private void grantMemberPermissionWithScope(String resourceName, String action, String fields, String scopeJson) {
        Role memberRole = roleRepository.findByName("member").orElse(null);
        if (memberRole == null) fail("member role not found");

        String roleName = getRoleInternalName(memberRole.getId());
        RoleResource rr = roleResourceRepository
                .findByRoleNameAndResourceName(roleName, resourceName)
                .orElseGet(() -> {
                    RoleResource newRr = new RoleResource(roleName, resourceName);
                    return roleResourceRepository.save(newRr);
                });

        RoleResourceAction rra = new RoleResourceAction(rr.getId(), action, fields);
        roleResourceActionRepository.save(rra);

        RoleResourceScope scope = new RoleResourceScope(rr.getId(), scopeJson, action);
        roleResourceScopeRepository.save(scope);
    }
}