package com.nocobase;

import com.nocobase.acl.FieldPermission;
import com.nocobase.acl.FieldPermissionFilter;
import com.nocobase.data.DynamicRepository;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.*;
import com.nocobase.repository.*;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.sql.SqlParameterMetadata;
import com.nocobase.sql.SqlParameterResolver;
import com.nocobase.sql.SqlValidator;
import com.nocobase.web.ForbiddenException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL Query Collection acceptance tests.
 * All tests go through DynamicRepository — never directly call SqlQueryCollectionExecutor.
 * <p>
 * Test isolation: unique collection names per test class, data cleanup before insertion,
 * ACL permission cleanup between tests. All assertions use exact counts (no &gt;=).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlQueryCollectionTest {

    @Autowired private CollectionRuntimeService runtimeService;
    @Autowired private DdlSynchronizer ddlSynchronizer;
    @Autowired private DynamicRepository dynamicRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RoleResourceRepository roleResourceRepository;
    @Autowired private RoleResourceActionRepository roleResourceActionRepository;
    @Autowired private RoleResourceScopeRepository roleResourceScopeRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private SqlParameterResolver parameterResolver;

    private static final String BASE_TABLE = "sql_test_base_iso";
    private static final String SQL_COLL = "sql_test_view_iso";
    private static final String SQL_COLL_PK = "sql_test_view_pk_iso";
    private static final String CUSTOM_PK_TABLE = "sql_test_custom_pk_iso";
    private static final String SQL_COLL_CUSTOM_PK = "sql_test_view_custom_pk_iso";

    // ========== P0-A: Named parameter test collections ==========
    private static final String SQL_COLL_NAMED_PARAM = "sql_test_named_param_iso";
    private static final String SQL_COLL_NAMED_PARAM_PK = "sql_test_named_param_pk_iso";
    private static final String SQL_COLL_REPEATED_PARAM = "sql_test_repeated_param_iso";
    private static final String SQL_COLL_UNDECLARED_PARAM = "sql_test_undeclared_param_iso";
    private static final String SQL_COLL_CURRENT_USER = "sql_test_current_user_iso";
    private static final String SQL_COLL_CURRENT_USER_PK = "sql_test_current_user_pk_iso";
    // ========== P0-B: Typed parameter test collections ==========
    private static final String SQL_COLL_NUMERIC_PARAM = "sql_test_numeric_param_iso";
    private static final String SQL_COLL_BOOLEAN_PARAM = "sql_test_boolean_param_iso";

    private Long memberUserId;
    private Long recordIdA, recordIdB, recordIdC;
    private String codeA, codeB, codeC;

    @BeforeAll
    void setUp() {
        authAsAdmin();

        // ========== Create base table with clean data ==========
        if (!runtimeService.exists(BASE_TABLE)) {
            CollectionEntity base = new CollectionEntity(BASE_TABLE, "Base", "physical");
            base.setTableName(BASE_TABLE);
            ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(BASE_TABLE, "name", "string"),
                    new FieldEntity(BASE_TABLE, "status", "string"),
                    new FieldEntity(BASE_TABLE, "owner_id", "bigInt"),
                    new FieldEntity(BASE_TABLE, "price", "float")));
            runtimeService.reload(BASE_TABLE);
        }

        // Clean up existing data for test isolation
        jdbcTemplate.update("DELETE FROM \"" + BASE_TABLE + "\"");

        // Insert fresh test data
        recordIdA = (Long) dynamicRepository.create(BASE_TABLE,
                Map.of("name", "Item A", "status", "active", "owner_id", 1, "price", 10.0)).get("id");
        recordIdB = (Long) dynamicRepository.create(BASE_TABLE,
                Map.of("name", "Item B", "status", "active", "owner_id", 2, "price", 20.0)).get("id");
        recordIdC = (Long) dynamicRepository.create(BASE_TABLE,
                Map.of("name", "Item C", "status", "inactive", "owner_id", 1, "price", 30.0)).get("id");

        // ========== Create SQL collection (no primary key) ==========
        if (!runtimeService.exists(SQL_COLL)) {
            CollectionEntity sqlColl = new CollectionEntity(SQL_COLL, "SQL View", "sql");
            sqlColl.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\"");
            ddlSynchronizer.createCollection(sqlColl, List.of(
                    new FieldEntity(SQL_COLL, "id", "bigInt"),
                    new FieldEntity(SQL_COLL, "name", "string"),
                    new FieldEntity(SQL_COLL, "status", "string"),
                    new FieldEntity(SQL_COLL, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL, "price", "float")));
            runtimeService.reload(SQL_COLL);
        }

        // ========== Create SQL collection with primary key ==========
        if (!runtimeService.exists(SQL_COLL_PK)) {
            CollectionEntity sqlCollPk = new CollectionEntity(SQL_COLL_PK, "SQL View PK", "sql");
            sqlCollPk.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\"");
            sqlCollPk.setOptions("{\"primaryKey\": \"id\"}");
            ddlSynchronizer.createCollection(sqlCollPk, List.of(
                    new FieldEntity(SQL_COLL_PK, "id", "bigInt"),
                    new FieldEntity(SQL_COLL_PK, "name", "string"),
                    new FieldEntity(SQL_COLL_PK, "status", "string"),
                    new FieldEntity(SQL_COLL_PK, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL_PK, "price", "float")));
            runtimeService.reload(SQL_COLL_PK);
        }

        // ========== Create custom PK table and SQL collection ==========
        // Physical table with 'code' as natural key (string type)
        if (!runtimeService.exists(CUSTOM_PK_TABLE)) {
            CollectionEntity customPkBase = new CollectionEntity(CUSTOM_PK_TABLE, "Custom PK Base", "physical");
            customPkBase.setTableName(CUSTOM_PK_TABLE);
            ddlSynchronizer.createCollection(customPkBase, List.of(
                    new FieldEntity(CUSTOM_PK_TABLE, "code", "string"),
                    new FieldEntity(CUSTOM_PK_TABLE, "name", "string"),
                    new FieldEntity(CUSTOM_PK_TABLE, "status", "string")));
            runtimeService.reload(CUSTOM_PK_TABLE);
        }

        // Clean up and insert test data with known code values
        jdbcTemplate.update("DELETE FROM \"" + CUSTOM_PK_TABLE + "\"");
        codeA = "CODE-A";
        codeB = "CODE-B";
        codeC = "CODE-C";
        jdbcTemplate.update("INSERT INTO \"" + CUSTOM_PK_TABLE + "\" (\"code\", \"name\", \"status\") VALUES (?, ?, ?)",
                codeA, "Custom Item A", "active");
        jdbcTemplate.update("INSERT INTO \"" + CUSTOM_PK_TABLE + "\" (\"code\", \"name\", \"status\") VALUES (?, ?, ?)",
                codeB, "Custom Item B", "active");
        jdbcTemplate.update("INSERT INTO \"" + CUSTOM_PK_TABLE + "\" (\"code\", \"name\", \"status\") VALUES (?, ?, ?)",
                codeC, "Custom Item C", "inactive");

        // SQL collection with primaryKey = "code"
        if (!runtimeService.exists(SQL_COLL_CUSTOM_PK)) {
            CollectionEntity sqlCollCustomPk = new CollectionEntity(SQL_COLL_CUSTOM_PK, "SQL View Custom PK", "sql");
            sqlCollCustomPk.setSql("SELECT \"code\", \"name\", \"status\" FROM \"" + CUSTOM_PK_TABLE + "\"");
            sqlCollCustomPk.setOptions("{\"primaryKey\": \"code\"}");
            ddlSynchronizer.createCollection(sqlCollCustomPk, List.of(
                    new FieldEntity(SQL_COLL_CUSTOM_PK, "code", "string"),
                    new FieldEntity(SQL_COLL_CUSTOM_PK, "name", "string"),
                    new FieldEntity(SQL_COLL_CUSTOM_PK, "status", "string")));
            runtimeService.reload(SQL_COLL_CUSTOM_PK);
        }

        // ========== P0-A: Create SQL collections with named parameters ==========
        // SQL with single named param :status, no primary key
        if (!runtimeService.exists(SQL_COLL_NAMED_PARAM)) {
            CollectionEntity sqlNamedParam = new CollectionEntity(SQL_COLL_NAMED_PARAM, "SQL Named Param", "sql");
            sqlNamedParam.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\" WHERE \"status\" = :status");
            sqlNamedParam.setOptions("{\"parameters\": [{\"name\": \"status\", \"type\": \"string\", \"source\": \"static\", \"defaultValue\": \"active\", \"required\": true}]}");
            ddlSynchronizer.createCollection(sqlNamedParam, List.of(
                    new FieldEntity(SQL_COLL_NAMED_PARAM, "id", "bigInt"),
                    new FieldEntity(SQL_COLL_NAMED_PARAM, "name", "string"),
                    new FieldEntity(SQL_COLL_NAMED_PARAM, "status", "string"),
                    new FieldEntity(SQL_COLL_NAMED_PARAM, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL_NAMED_PARAM, "price", "float")));
            runtimeService.reload(SQL_COLL_NAMED_PARAM);
        }

        // SQL with single named param :status, with primaryKey = "id"
        if (!runtimeService.exists(SQL_COLL_NAMED_PARAM_PK)) {
            CollectionEntity sqlNamedParamPk = new CollectionEntity(SQL_COLL_NAMED_PARAM_PK, "SQL Named Param PK", "sql");
            sqlNamedParamPk.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\" WHERE \"status\" = :status");
            sqlNamedParamPk.setOptions("{\"primaryKey\": \"id\", \"parameters\": [{\"name\": \"status\", \"type\": \"string\", \"source\": \"static\", \"defaultValue\": \"active\", \"required\": true}]}");
            ddlSynchronizer.createCollection(sqlNamedParamPk, List.of(
                    new FieldEntity(SQL_COLL_NAMED_PARAM_PK, "id", "bigInt"),
                    new FieldEntity(SQL_COLL_NAMED_PARAM_PK, "name", "string"),
                    new FieldEntity(SQL_COLL_NAMED_PARAM_PK, "status", "string"),
                    new FieldEntity(SQL_COLL_NAMED_PARAM_PK, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL_NAMED_PARAM_PK, "price", "float")));
            runtimeService.reload(SQL_COLL_NAMED_PARAM_PK);
        }

        // SQL with repeated named param :status, no primary key
        if (!runtimeService.exists(SQL_COLL_REPEATED_PARAM)) {
            CollectionEntity sqlRepeatedParam = new CollectionEntity(SQL_COLL_REPEATED_PARAM, "SQL Repeated Param", "sql");
            sqlRepeatedParam.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\" WHERE \"status\" = :status OR \"name\" = :status");
            sqlRepeatedParam.setOptions("{\"parameters\": [{\"name\": \"status\", \"type\": \"string\", \"source\": \"static\", \"defaultValue\": \"active\", \"required\": true}]}");
            ddlSynchronizer.createCollection(sqlRepeatedParam, List.of(
                    new FieldEntity(SQL_COLL_REPEATED_PARAM, "id", "bigInt"),
                    new FieldEntity(SQL_COLL_REPEATED_PARAM, "name", "string"),
                    new FieldEntity(SQL_COLL_REPEATED_PARAM, "status", "string"),
                    new FieldEntity(SQL_COLL_REPEATED_PARAM, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL_REPEATED_PARAM, "price", "float")));
            runtimeService.reload(SQL_COLL_REPEATED_PARAM);
        }

        // SQL collection with undeclared named param :undeclaredParam — validation fails at reload,
        // so we skip reload and test the error via SqlParameterMetadata directly.
        if (!runtimeService.exists(SQL_COLL_UNDECLARED_PARAM)) {
            CollectionEntity sqlUndeclaredParam = new CollectionEntity(SQL_COLL_UNDECLARED_PARAM, "SQL Undeclared Param", "sql");
            sqlUndeclaredParam.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\" WHERE \"status\" = :undeclaredParam");
            sqlUndeclaredParam.setOptions("{\"parameters\": [{\"name\": \"status\", \"type\": \"string\", \"source\": \"static\", \"defaultValue\": \"active\", \"required\": true}]}");
            ddlSynchronizer.createCollection(sqlUndeclaredParam, List.of(
                    new FieldEntity(SQL_COLL_UNDECLARED_PARAM, "id", "bigInt"),
                    new FieldEntity(SQL_COLL_UNDECLARED_PARAM, "name", "string"),
                    new FieldEntity(SQL_COLL_UNDECLARED_PARAM, "status", "string"),
                    new FieldEntity(SQL_COLL_UNDECLARED_PARAM, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL_UNDECLARED_PARAM, "price", "float")));
            // Do NOT reload — validation will fail because SQL references :undeclaredParam
            // which is not in options.parameters. The undeclaredNamedParamFailsBeforeExecution
            // test verifies the error through SqlParameterMetadata directly.
        }

        // ========== P0-B: Create SQL collections with numeric/boolean typed parameters ==========
        // Numeric param: filter by price >= :min_price
        if (!runtimeService.exists(SQL_COLL_NUMERIC_PARAM)) {
            CollectionEntity sqlNumericParam = new CollectionEntity(SQL_COLL_NUMERIC_PARAM, "SQL Numeric Param", "sql");
            sqlNumericParam.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\" WHERE \"price\" >= :min_price");
            sqlNumericParam.setOptions("{\"parameters\": [{\"name\": \"min_price\", \"type\": \"number\", \"source\": \"static\", \"defaultValue\": 20, \"required\": true}]}");
            ddlSynchronizer.createCollection(sqlNumericParam, List.of(
                    new FieldEntity(SQL_COLL_NUMERIC_PARAM, "id", "bigInt"),
                    new FieldEntity(SQL_COLL_NUMERIC_PARAM, "name", "string"),
                    new FieldEntity(SQL_COLL_NUMERIC_PARAM, "status", "string"),
                    new FieldEntity(SQL_COLL_NUMERIC_PARAM, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL_NUMERIC_PARAM, "price", "float")));
            runtimeService.reload(SQL_COLL_NUMERIC_PARAM);
        }

        // Boolean param: filter by is_active (using status = 'active' as proxy)
        if (!runtimeService.exists(SQL_COLL_BOOLEAN_PARAM)) {
            CollectionEntity sqlBooleanParam = new CollectionEntity(SQL_COLL_BOOLEAN_PARAM, "SQL Boolean Param", "sql");
            sqlBooleanParam.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\" WHERE \"status\" = :active_status");
            sqlBooleanParam.setOptions("{\"parameters\": [{\"name\": \"active_status\", \"type\": \"string\", \"source\": \"static\", \"defaultValue\": \"active\", \"required\": true}]}");
            ddlSynchronizer.createCollection(sqlBooleanParam, List.of(
                    new FieldEntity(SQL_COLL_BOOLEAN_PARAM, "id", "bigInt"),
                    new FieldEntity(SQL_COLL_BOOLEAN_PARAM, "name", "string"),
                    new FieldEntity(SQL_COLL_BOOLEAN_PARAM, "status", "string"),
                    new FieldEntity(SQL_COLL_BOOLEAN_PARAM, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL_BOOLEAN_PARAM, "price", "float")));
            runtimeService.reload(SQL_COLL_BOOLEAN_PARAM);
        }

        // ========== P1-F: Create SQL collections with currentUser parameter source ==========
        // SQL collection with currentUser: owner_id = :userId (resolved from current user's ID)
        if (!runtimeService.exists(SQL_COLL_CURRENT_USER)) {
            CollectionEntity sqlCurrentUser = new CollectionEntity(SQL_COLL_CURRENT_USER, "SQL Current User", "sql");
            sqlCurrentUser.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\" WHERE \"owner_id\" = :userId");
            sqlCurrentUser.setOptions("{\"parameters\": [{\"name\": \"userId\", \"type\": \"number\", \"source\": \"currentUser\", \"path\": \"id\", \"required\": true}]}");
            ddlSynchronizer.createCollection(sqlCurrentUser, List.of(
                    new FieldEntity(SQL_COLL_CURRENT_USER, "id", "bigInt"),
                    new FieldEntity(SQL_COLL_CURRENT_USER, "name", "string"),
                    new FieldEntity(SQL_COLL_CURRENT_USER, "status", "string"),
                    new FieldEntity(SQL_COLL_CURRENT_USER, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL_CURRENT_USER, "price", "float")));
            runtimeService.reload(SQL_COLL_CURRENT_USER);
        }

        // SQL collection with currentUser + primary key
        if (!runtimeService.exists(SQL_COLL_CURRENT_USER_PK)) {
            CollectionEntity sqlCurrentUserPk = new CollectionEntity(SQL_COLL_CURRENT_USER_PK, "SQL Current User PK", "sql");
            sqlCurrentUserPk.setSql("SELECT \"id\", \"name\", \"status\", \"owner_id\", \"price\" FROM \"" + BASE_TABLE + "\" WHERE \"owner_id\" = :userId");
            sqlCurrentUserPk.setOptions("{\"primaryKey\": \"id\", \"parameters\": [{\"name\": \"userId\", \"type\": \"number\", \"source\": \"currentUser\", \"path\": \"id\", \"required\": true}]}");
            ddlSynchronizer.createCollection(sqlCurrentUserPk, List.of(
                    new FieldEntity(SQL_COLL_CURRENT_USER_PK, "id", "bigInt"),
                    new FieldEntity(SQL_COLL_CURRENT_USER_PK, "name", "string"),
                    new FieldEntity(SQL_COLL_CURRENT_USER_PK, "status", "string"),
                    new FieldEntity(SQL_COLL_CURRENT_USER_PK, "owner_id", "bigInt"),
                    new FieldEntity(SQL_COLL_CURRENT_USER_PK, "price", "float")));
            runtimeService.reload(SQL_COLL_CURRENT_USER_PK);
        }

        // ========== Create member user for ACL tests ==========
        String memberEmail = "sqlquery_member_iso@test.com";
        User member = userRepository.findByEmail(memberEmail).orElse(null);
        if (member != null) {
            memberUserId = member.getId();
        } else {
            member = new User();
            member.setEmail(memberEmail);
            member.setNickname("SQL Query Member");
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

    @BeforeEach
    void resetAuth() {
        authAsAdmin();
    }

    // ========== Basic list ==========

    @Test
    @DisplayName("SQL: basic list returns data")
    void sqlBasicList() {
        var result = dynamicRepository.list(SQL_COLL, null, null, 1, 10, null);
        assertEquals(3, result.getCount(), "should see exactly 3 records");
        assertFalse(result.getData().isEmpty());
    }

    @Test
    @DisplayName("SQL: filter works")
    void sqlFilter() {
        var result = dynamicRepository.list(SQL_COLL,
                Map.of("status", "active"), null, 1, 10, null);
        assertEquals(2, result.getCount(), "should see exactly 2 active records");
        for (var row : result.getData()) {
            assertEquals("active", row.get("status"));
        }
    }

    @Test
    @DisplayName("SQL: sort works")
    void sqlSort() {
        var result = dynamicRepository.list(SQL_COLL, null, "-price", 1, 10, null);
        assertEquals(3, result.getCount());
        if (result.getData().size() >= 2) {
            double first = ((Number) result.getData().get(0).get("price")).doubleValue();
            double second = ((Number) result.getData().get(1).get("price")).doubleValue();
            assertTrue(first >= second, "DESC sort should put higher price first");
        }
    }

    @Test
    @DisplayName("SQL: pagination works")
    void sqlPagination() {
        var page1 = dynamicRepository.list(SQL_COLL, null, "id", 1, 2, null);
        assertEquals(2, page1.getData().size(), "page 1 should have exactly 2 records");
        var page2 = dynamicRepository.list(SQL_COLL, null, "id", 2, 2, null);
        assertEquals(1, page2.getData().size(), "page 2 should have exactly 1 record");
    }

    @Test
    @DisplayName("SQL: count is correct")
    void sqlCount() {
        var result = dynamicRepository.list(SQL_COLL, null, null, 1, 10, null);
        assertEquals(3, result.getCount(), "count should be exactly 3");
    }

    @Test
    @DisplayName("SQL: fields projection works")
    void sqlFieldsProjection() {
        var result = dynamicRepository.list(SQL_COLL, null, null, 1, 10, "name,status");
        assertFalse(result.getData().isEmpty());
        Map<String, Object> first = result.getData().get(0);
        assertTrue(first.containsKey("name"));
        assertTrue(first.containsKey("status"));
    }

    // ========== Write rejection ==========

    @Test
    @DisplayName("SQL: create is rejected")
    void sqlCreateRejected() {
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.create(SQL_COLL, Map.of("name", "test")));
    }

    @Test
    @DisplayName("SQL: update is rejected")
    void sqlUpdateRejected() {
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.update(SQL_COLL, recordIdA, Map.of("name", "test")));
    }

    @Test
    @DisplayName("SQL: destroy is rejected")
    void sqlDestroyRejected() {
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.destroy(SQL_COLL, recordIdA));
    }

    // ========== SQL validator ==========

    @Test
    @DisplayName("SQL: SELECT is valid")
    void sqlValidatorSelect() {
        assertDoesNotThrow(() -> SqlValidator.validate("SELECT * FROM t"));
    }

    @Test
    @DisplayName("SQL: WITH ... SELECT is valid")
    void sqlValidatorWithSelect() {
        assertDoesNotThrow(() -> SqlValidator.validate("WITH x AS (SELECT 1) SELECT * FROM x"));
    }

    @Test
    @DisplayName("SQL: semicolons rejected")
    void sqlValidatorSemicolonsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("SELECT 1;"));
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("SELECT 1; DROP TABLE t"));
    }

    @Test
    @DisplayName("SQL: INSERT rejected")
    void sqlValidatorInsertRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("INSERT INTO t VALUES (1)"));
    }

    @Test
    @DisplayName("SQL: DELETE rejected")
    void sqlValidatorDeleteRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("DELETE FROM t"));
    }

    @Test
    @DisplayName("SQL: comments rejected")
    void sqlValidatorCommentsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("SELECT * FROM t -- comment"));
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("SELECT * FROM t /* comment */"));
    }

    @Test
    @DisplayName("SQL: ? parameter rejected")
    void sqlValidatorJdbcParamRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("SELECT * FROM t WHERE x = ?"));
    }

    @Test
    @DisplayName("SQL: :named parameter is now allowed (Phase 2)")
    void sqlValidatorNamedParamAllowed() {
        // Named parameters are now supported via SqlNamedParameterParser
        assertDoesNotThrow(() ->
                SqlValidator.validate("SELECT * FROM t WHERE x = :name"));
    }

    // ========== P0-D: Lexical false positive tests ==========

    @Test
    @DisplayName("P0-D: semicolon inside string literal is allowed")
    void sqlValidatorSemicolonInsideStringAllowed() {
        assertDoesNotThrow(() ->
                SqlValidator.validate("SELECT 'a;b' AS text_value"));
    }

    @Test
    @DisplayName("P0-D: question mark inside string literal is allowed")
    void sqlValidatorQuestionMarkInsideStringAllowed() {
        assertDoesNotThrow(() ->
                SqlValidator.validate("SELECT 'what?' AS text_value"));
    }

    @Test
    @DisplayName("P0-D: line comment marker inside string literal is allowed")
    void sqlValidatorLineCommentInsideStringAllowed() {
        assertDoesNotThrow(() ->
                SqlValidator.validate("SELECT '-- not a comment' AS text_value"));
    }

    @Test
    @DisplayName("P0-D: block comment marker inside string literal is allowed")
    void sqlValidatorBlockCommentInsideStringAllowed() {
        assertDoesNotThrow(() ->
                SqlValidator.validate("SELECT '/* not a comment */' AS text_value"));
    }

    @Test
    @DisplayName("P0-D: multi-statement with semicolon outside strings is rejected")
    void sqlValidatorMultiStatementRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("SELECT 1; SELECT 2"));
    }

    @Test
    @DisplayName("P0-D: line comment outside strings is rejected")
    void sqlValidatorLineCommentOutsideStringsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("SELECT * FROM t -- comment"));
    }

    @Test
    @DisplayName("P0-D: JDBC ? parameter outside strings is rejected")
    void sqlValidatorJdbcParamOutsideStringsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SqlValidator.validate("SELECT * FROM t WHERE id = ?"));
    }

    // ========== P0-B: SQL permission tests ==========

    @Test
    @DisplayName("SQL: list applies action scope — member with scope only sees matching records")
    void sqlListAppliesActionScope() {
        // Clean up any previous permissions for this resource
        cleanupMemberPermissions(SQL_COLL);

        // Grant member list permission with scope: owner_id=1
        grantMemberPermissionWithScope(SQL_COLL, "list", "name,status,owner_id,price",
                "{\"owner_id\": {\"$eq\": 1}}");

        authAsMember();
        var result = dynamicRepository.list(SQL_COLL, null, null, 1, 100, null);
        assertEquals(2, result.getCount(), "member should only see 2 records with owner_id=1");
        for (var row : result.getData()) {
            Object ownerId = row.get("owner_id");
            assertNotNull(ownerId, "owner_id should be present");
            assertEquals(1L, ((Number) ownerId).longValue(), "all records should have owner_id=1");
        }
    }

    @Test
    @DisplayName("SQL: list applies readable fields — member sees only allowed fields")
    void sqlListAppliesReadableFields() {
        // Clean up any previous permissions for this resource
        cleanupMemberPermissions(SQL_COLL);

        // Grant member list permission with only name and status readable
        grantMemberPermission(SQL_COLL, "list", "name,status");

        authAsMember();
        var result = dynamicRepository.list(SQL_COLL, null, null, 1, 10, null);
        assertFalse(result.getData().isEmpty());
        Map<String, Object> first = result.getData().get(0);
        assertTrue(first.containsKey("name"), "name should be present (allowed field)");
        assertTrue(first.containsKey("status"), "status should be present (allowed field)");
        assertFalse(first.containsKey("price"), "price should NOT be present (not in allowed fields)");
        assertFalse(first.containsKey("owner_id"), "owner_id should NOT be present (not in allowed fields)");
    }

    @Test
    @DisplayName("SQL: get without primary key is rejected")
    void sqlGetWithoutPrimaryKeyRejected() {
        // SQL collection without configured primaryKey — get should fail.
        // @Repository annotation wraps IllegalArgumentException into InvalidDataAccessApiUsageException
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                dynamicRepository.get(SQL_COLL, recordIdA));
    }

    @Test
    @DisplayName("SQL: filter value injection is parameterized (safe)")
    void sqlFilterValueInjectionIsParameterized() {
        // Filter with value that looks like SQL injection
        var result = dynamicRepository.list(SQL_COLL,
                Map.of("name", "'; DROP TABLE --"), null, 1, 10, null);
        assertEquals(0, result.getCount(), "SQL injection should not match any records");
    }

    // ========== P0-C: ACL scope tests ==========

    @Test
    @DisplayName("P0-C: member with scope on owner_id=2 sees only that record")
    void memberListWithScopeSeesOnlyMatchingRecords() {
        cleanupMemberPermissions(SQL_COLL);
        grantMemberPermissionWithScope(SQL_COLL, "list", "name,status,owner_id,price",
                "{\"owner_id\": {\"$eq\": 2}}");

        authAsMember();
        var result = dynamicRepository.list(SQL_COLL, null, null, 1, 100, null);
        assertEquals(1, result.getCount(), "member should only see 1 record with owner_id=2");
        Map<String, Object> row = result.getData().get(0);
        assertEquals("Item B", row.get("name"), "should be Item B (owner_id=2)");
        assertEquals(2L, ((Number) row.get("owner_id")).longValue(), "owner_id should be 2");
    }

    @Test
    @DisplayName("P0-C: member with scope on inactive status sees only that record")
    void memberListWithInactiveScopeSeesOnlyMatchingRecords() {
        cleanupMemberPermissions(SQL_COLL);
        grantMemberPermissionWithScope(SQL_COLL, "list", "name,status,owner_id,price",
                "{\"status\": {\"$eq\": \"inactive\"}}");

        authAsMember();
        var result = dynamicRepository.list(SQL_COLL, null, null, 1, 100, null);
        assertEquals(1, result.getCount(), "member should only see 1 inactive record");
        assertEquals("inactive", result.getData().get(0).get("status"));
    }

    @Test
    @DisplayName("P0-C: member without list permission gets ForbiddenException")
    void memberWithoutListPermissionGetsForbiddenOnSqlCollection() {
        cleanupMemberPermissions(SQL_COLL);
        // Member has no list permission — should get ForbiddenException
        authAsMember();
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.list(SQL_COLL, null, null, 1, 10, null));
    }

    // ========== P0-D: Readable fields tests ==========

    @Test
    @DisplayName("P0-D: member with partial readable fields sees only allowed fields")
    void memberListWithPartialReadableFields() {
        cleanupMemberPermissions(SQL_COLL);
        // Grant member list permission with only name field
        grantMemberPermission(SQL_COLL, "list", "name");

        authAsMember();
        var result = dynamicRepository.list(SQL_COLL, null, null, 1, 10, null);
        assertFalse(result.getData().isEmpty());
        Map<String, Object> first = result.getData().get(0);
        assertTrue(first.containsKey("name"), "name should be present");
        assertFalse(first.containsKey("status"), "status should NOT be present");
        assertFalse(first.containsKey("price"), "price should NOT be present");
        assertFalse(first.containsKey("owner_id"), "owner_id should NOT be present");
    }

    @Test
    @DisplayName("P0-D: primary key is always included even when not in readable fields")
    void primaryKeyAlwaysIncludedInResults() {
        cleanupMemberPermissions(SQL_COLL_PK);
        // SQL collection with primary key configured
        // Grant member list permission with only "name" readable (not "id")
        grantMemberPermission(SQL_COLL_PK, "list", "name");

        authAsMember();
        var result = dynamicRepository.list(SQL_COLL_PK, null, null, 1, 10, null);
        assertFalse(result.getData().isEmpty());
        Map<String, Object> first = result.getData().get(0);
        assertTrue(first.containsKey("id"), "id (primary key) should always be present");
        assertTrue(first.containsKey("name"), "name should be present (allowed field)");
        assertFalse(first.containsKey("status"), "status should NOT be present");
        assertFalse(first.containsKey("price"), "price should NOT be present");
        assertFalse(first.containsKey("owner_id"), "owner_id should NOT be present");
    }

    @Test
    @DisplayName("P0-D: member with all-fields list permission sees all fields")
    void memberWithAllFieldsPermissionSeesAllFields() {
        cleanupMemberPermissions(SQL_COLL);
        // Grant member list permission with null/empty fields = all fields
        grantMemberPermission(SQL_COLL, "list", null);

        authAsMember();
        var result = dynamicRepository.list(SQL_COLL, null, null, 1, 10, null);
        assertFalse(result.getData().isEmpty());
        Map<String, Object> first = result.getData().get(0);
        assertTrue(first.containsKey("id"), "id should be present");
        assertTrue(first.containsKey("name"), "name should be present");
        assertTrue(first.containsKey("status"), "status should be present");
        assertTrue(first.containsKey("price"), "price should be present");
        assertTrue(first.containsKey("owner_id"), "owner_id should be present");
    }

    // ========== P0-B: SQL collection get() tests ==========

    @Test
    @DisplayName("SQL: get with primaryKey returns correct record")
    void sqlGetWithPrimaryKeyReturnsCorrectRecord() {
        var result = dynamicRepository.get(SQL_COLL_PK, recordIdA);
        assertNotNull(result, "get() should return a record");
        assertEquals(recordIdA, result.get("id"), "should return the requested record");
        assertEquals("Item A", result.get("name"));
    }

    @Test
    @DisplayName("SQL: get action scope — list scope and get scope are different")
    void sqlGetActionScopeDifferentFromListScope() {
        cleanupMemberPermissions(SQL_COLL_PK);

        // Grant list scope on owner_id=1 (records A, C), get scope on owner_id=2 (record B)
        grantMemberPermissionWithScope(SQL_COLL_PK, "list", "name,status,owner_id,price,id",
                "{\"owner_id\": {\"$eq\": 1}}");
        grantMemberPermissionWithScope(SQL_COLL_PK, "get", "name,status,owner_id,price,id",
                "{\"owner_id\": {\"$eq\": 2}}");

        authAsMember();

        // list() should only see records with owner_id=1
        var listResult = dynamicRepository.list(SQL_COLL_PK, null, null, 1, 10, null);
        assertEquals(2, listResult.getCount(), "list should see 2 records with owner_id=1");
        for (var row : listResult.getData()) {
            assertEquals(1L, ((Number) row.get("owner_id")).longValue());
        }

        // get(recordIdA) should return null — recordIdA is in list scope but not get scope
        Map<String, Object> getA = dynamicRepository.get(SQL_COLL_PK, recordIdA);
        assertNull(getA, "record in list scope but not get scope should return null");

        // get(recordIdB) should return the record — recordIdB is in get scope
        Map<String, Object> getB = dynamicRepository.get(SQL_COLL_PK, recordIdB);
        assertNotNull(getB, "record in get scope should be returned");
        assertEquals("Item B", getB.get("name"));
        assertEquals(2L, ((Number) getB.get("owner_id")).longValue());
    }

    @Test
    @DisplayName("SQL: get applies readable fields — member sees only allowed fields + primary key")
    void sqlGetAppliesReadableFields() {
        cleanupMemberPermissions(SQL_COLL_PK);

        // Grant member get permission with only name readable
        grantMemberPermission(SQL_COLL_PK, "get", "name");

        authAsMember();
        Map<String, Object> result = dynamicRepository.get(SQL_COLL_PK, recordIdA);
        assertNotNull(result, "get() should return a record");
        assertTrue(result.containsKey("id"), "primary key should always be present");
        assertTrue(result.containsKey("name"), "name should be present (allowed field)");
        assertFalse(result.containsKey("status"), "status should NOT be present");
        assertFalse(result.containsKey("price"), "price should NOT be present");
        assertFalse(result.containsKey("owner_id"), "owner_id should NOT be present");
    }

    @Test
    @DisplayName("SQL: get without permission throws ForbiddenException")
    void sqlGetWithoutPermissionThrowsForbiddenException() {
        cleanupMemberPermissions(SQL_COLL_PK);

        // Grant member list permission but NOT get permission
        grantMemberPermission(SQL_COLL_PK, "list", "name,id");

        authAsMember();
        assertThrows(ForbiddenException.class, () ->
                dynamicRepository.get(SQL_COLL_PK, recordIdA));
    }

    // ========== P0-C: Custom primary key tests ==========

    @Test
    @DisplayName("P0-C: SQL get works with custom primaryKey 'code' (not 'id')")
    void sqlGetWithCustomPrimaryKey() {
        // SQL collection with primaryKey="code" — get() should work with code value
        var result = dynamicRepository.get(SQL_COLL_CUSTOM_PK, codeA);
        assertNotNull(result, "get() with custom PK should return a record");
        assertEquals(codeA, result.get("code"), "should return the requested record by code");
        assertEquals("Custom Item A", result.get("name"));
        assertEquals("active", result.get("status"));
    }

    @Test
    @DisplayName("P0-C: readable none with custom primaryKey returns only the primary key field")
    void readableNoneWithCustomPkReturnsOnlyPk() {
        // Directly test FieldPermissionFilter with FieldPermission.none()
        // and a collection that has a custom primary key (code)
        var def = runtimeService.get(SQL_COLL_CUSTOM_PK);
        assertTrue(def.hasPrimaryKey(), "custom PK collection should have a primary key");
        assertEquals("code", def.getPrimaryKeyFieldName());

        Map<String, Object> row = Map.of("code", codeA, "name", "Custom Item A", "status", "active");
        Map<String, Object> filtered = FieldPermissionFilter.filter(FieldPermission.none(), def, row);

        assertNotNull(filtered, "filtered result should not be null");
        assertEquals(1, filtered.size(), "only the primary key should be present");
        assertTrue(filtered.containsKey("code"), "code (primary key) should be present");
        assertEquals(codeA, filtered.get("code"));
        assertFalse(filtered.containsKey("name"), "name should NOT be present");
        assertFalse(filtered.containsKey("status"), "status should NOT be present");
    }

    @Test
    @DisplayName("P0-C: readable none without primaryKey returns empty object")
    void readableNoneWithoutPkReturnsEmptyObject() {
        // Directly test FieldPermissionFilter with FieldPermission.none()
        // and a collection that has NO primary key (SQL_COLL)
        var def = runtimeService.get(SQL_COLL);
        assertFalse(def.hasPrimaryKey(), "SQL_COLL should not have a primary key");

        Map<String, Object> row = Map.of("id", 1L, "name", "Item A", "status", "active");
        Map<String, Object> filtered = FieldPermissionFilter.filter(FieldPermission.none(), def, row);

        assertNotNull(filtered, "filtered result should not be null");
        assertTrue(filtered.isEmpty(), "result should be empty — no primary key to preserve");
    }

    // ========== P0-A: Named parameter integration tests through DynamicRepository ==========

    @Test
    @DisplayName("P0-A: list() with named param returns only matching rows")
    void namedParamListReturnsOnlyMatchingRows() {
        // SQL collection has :status param with defaultValue="active"
        // list() should return only rows where status = "active"
        var result = dynamicRepository.list(SQL_COLL_NAMED_PARAM, null, null, 1, 10, null);
        assertEquals(2, result.getCount(), "should see exactly 2 active records via named param");
        for (var row : result.getData()) {
            assertEquals("active", row.get("status"), "all returned rows should have status=active");
        }
    }

    @Test
    @DisplayName("P0-A: list() count matches data count with named param")
    void namedParamListCountMatchesData() {
        var result = dynamicRepository.list(SQL_COLL_NAMED_PARAM_PK, null, null, 1, 10, null);
        assertEquals(2, result.getCount(), "count should be 2 for active records");
        assertEquals(2, result.getData().size(), "data size should match count");
        for (var row : result.getData()) {
            assertEquals("active", row.get("status"));
        }
    }

    @Test
    @DisplayName("P0-A: get() with named param returns active row, null for inactive row")
    void namedParamGetReturnsActiveRowNullForInactive() {
        // recordIdA is active, recordIdC is inactive
        // With :status = "active", get(recordIdA) should return the record
        // get(recordIdC) should return null because row is filtered out by named param
        var result = dynamicRepository.get(SQL_COLL_NAMED_PARAM_PK, recordIdA);
        assertNotNull(result, "get() should return the active record");
        assertEquals(recordIdA, result.get("id"));
        assertEquals("active", result.get("status"));

        var inactiveResult = dynamicRepository.get(SQL_COLL_NAMED_PARAM_PK, recordIdC);
        assertNull(inactiveResult, "get() should return null for inactive record filtered by named param");
    }

    @Test
    @DisplayName("P0-A: named param + ACL scope both apply")
    void namedParamAndAclScopeBothApply() {
        cleanupMemberPermissions(SQL_COLL_NAMED_PARAM_PK);

        // Grant ACL scope: owner_id=1 (records A, C)
        grantMemberPermissionWithScope(SQL_COLL_NAMED_PARAM_PK, "list", "name,status,owner_id,price,id",
                "{\"owner_id\": {\"$eq\": 1}}");

        authAsMember();
        // Named param filters to status=active (records A, B)
        // ACL scope filters to owner_id=1 (records A, C)
        // Intersection: only record A
        var result = dynamicRepository.list(SQL_COLL_NAMED_PARAM_PK, null, null, 1, 10, null);
        assertEquals(1, result.getCount(), "only record A should match both named param and ACL scope");
        assertEquals("Item A", result.getData().get(0).get("name"));
        assertEquals("active", result.getData().get(0).get("status"));
        assertEquals(1L, ((Number) result.getData().get(0).get("owner_id")).longValue());
    }

    @Test
    @DisplayName("P0-A: repeated named param binds in occurrence order")
    void repeatedNamedParamBindsInOccurrenceOrder() {
        // SQL: WHERE "status" = :status OR "name" = :status
        // :status appears twice, both bound to "active"
        // Matches: status=active (A, B) OR name=active (none)
        var result = dynamicRepository.list(SQL_COLL_REPEATED_PARAM, null, null, 1, 10, null);
        assertEquals(2, result.getCount(), "should return exactly 2 rows with active status");
        for (var row : result.getData()) {
            assertEquals("active", row.get("status"), "all returned rows should have status=active");
        }
    }

    @Test
    @DisplayName("P0-A: undeclared named param fails during metadata validation")
    void undeclaredNamedParamFailsBeforeExecution() {
        // SQL references :undeclaredParam but options.parameters only declares "status"
        // SqlParameterMetadata.from() validates cross-references and throws
        var def = CollectionDefinition.builder("test")
                .type("sql")
                .sql("SELECT * FROM t WHERE status = :undeclaredParam")
                .options(Map.of("parameters", List.of(
                        Map.of("name", "status", "type", "string", "defaultValue", "active"))))
                .build();
        var ex = assertThrows(IllegalArgumentException.class, () ->
                SqlParameterMetadata.from(def));
        assertTrue(ex.getMessage().contains("undeclaredParam"),
                "Error should name the undeclared parameter: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("not declared"),
                "Error should indicate parameter is not declared: " + ex.getMessage());
    }

    // ========== P0-B: Typed parameter integration tests ==========

    @Test
    @DisplayName("P0-B: numeric param filters by price >= 20")
    void numericParamFiltersByPrice() {
        // SQL collection has :min_price = 20, should return records with price >= 20
        var result = dynamicRepository.list(SQL_COLL_NUMERIC_PARAM, null, null, 1, 10, null);
        assertEquals(2, result.getCount(), "should see exactly 2 records with price >= 20");
        for (var row : result.getData()) {
            double price = ((Number) row.get("price")).doubleValue();
            assertTrue(price >= 20.0, "all returned rows should have price >= 20, got " + price);
        }
    }

    @Test
    @DisplayName("P0-B: boolean param filters by active status")
    void booleanParamFiltersByActiveStatus() {
        // SQL collection has :active_status = "active", should return only active records
        var result = dynamicRepository.list(SQL_COLL_BOOLEAN_PARAM, null, null, 1, 10, null);
        assertEquals(2, result.getCount(), "should see exactly 2 active records");
        for (var row : result.getData()) {
            assertEquals("active", row.get("status"), "all returned rows should have status=active");
        }
    }

    // ========== P1-G: Parameter error API compatibility tests ==========

    @Test
    @DisplayName("P1-G: undeclared SQL parameter error message")
    void parameterErrorUndeclaredSqlParam() {
        // Parameter in SQL but not declared in options
        var def = CollectionDefinition.builder("test")
                .type("sql")
                .options(Map.of("parameters", List.of(
                        Map.of("name", "status", "type", "string", "defaultValue", "active"))))
                .build();
        var meta = SqlParameterMetadata.from(def);
        var ex = assertThrows(IllegalArgumentException.class, () ->
                parameterResolver.resolve(meta, List.of("undeclared")));
        assertTrue(ex.getMessage().contains("not defined"),
                "Error should indicate parameter is not defined: " + ex.getMessage());
    }

    @Test
    @DisplayName("P1-G: invalid parameter type error message")
    void parameterErrorInvalidType() {
        var def = CollectionDefinition.builder("test")
                .type("sql")
                .options(Map.of("parameters", List.of(
                        Map.of("name", "x", "type", "json", "defaultValue", "{}"))))
                .build();
        var ex = assertThrows(IllegalArgumentException.class, () ->
                SqlParameterMetadata.from(def));
        assertTrue(ex.getMessage().contains("Unsupported parameter type"),
                "Error should indicate unsupported type: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("json"),
                "Error should mention the invalid type: " + ex.getMessage());
    }

    @Test
    @DisplayName("P1-G: missing required static default error message")
    void parameterErrorMissingRequiredDefault() {
        var def = CollectionDefinition.builder("test")
                .type("sql")
                .options(Map.of("parameters", List.of(
                        Map.of("name", "x", "type", "string", "required", true))))
                .build();
        var ex = assertThrows(IllegalArgumentException.class, () ->
                SqlParameterMetadata.from(def));
        assertTrue(ex.getMessage().contains("must have a defaultValue"),
                "Error should indicate missing defaultValue: " + ex.getMessage());
    }

    @Test
    @DisplayName("P1-G: invalid parameter metadata shape error message")
    void parameterErrorInvalidMetadataShape() {
        // Parameter entry missing 'name' field
        var def = CollectionDefinition.builder("test")
                .type("sql")
                .options(Map.of("parameters", List.of(
                        Map.of("type", "string", "defaultValue", "x"))))
                .build();
        var ex = assertThrows(IllegalArgumentException.class, () ->
                SqlParameterMetadata.from(def));
        assertTrue(ex.getMessage().contains("missing 'name'"),
                "Error should indicate missing name: " + ex.getMessage());
    }

    @Test
    @DisplayName("P1-G: unsupported parameter source error message")
    void parameterErrorUnsupportedSource() {
        var def = CollectionDefinition.builder("test")
                .type("sql")
                .options(Map.of("parameters", List.of(
                        Map.of("name", "userId", "type", "number", "source", "request"))))
                .build();
        var ex = assertThrows(IllegalArgumentException.class, () ->
                SqlParameterMetadata.from(def));
        assertTrue(ex.getMessage().contains("Unsupported parameter source"),
                "Error should indicate unsupported source: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("request"),
                "Error should mention the invalid source: " + ex.getMessage());
    }

    // ========== P1-F: currentUser parameter source integration tests ==========

    @Test
    @DisplayName("P1-F: currentUser source — admin sees only their own rows")
    void currentUserAdminSeesOnlyOwnRows() {
        // Admin has userId=1, so only rows with owner_id=1 should be returned
        authAsAdmin();
        var result = dynamicRepository.list(SQL_COLL_CURRENT_USER, null, null, 1, 10, null);
        assertEquals(2, result.getCount(), "admin should see exactly 2 rows with owner_id=1");
        for (var row : result.getData()) {
            assertEquals(1L, ((Number) row.get("owner_id")).longValue(),
                    "all rows should have owner_id=1");
        }
    }

    @Test
    @DisplayName("P1-F: currentUser source — member sees only their own rows")
    void currentUserMemberSeesOnlyOwnRows() {
        // Grant member list permission
        cleanupMemberPermissions(SQL_COLL_CURRENT_USER);
        grantMemberPermission(SQL_COLL_CURRENT_USER, "list", "name,status,owner_id,price,id");

        // Update one row to have owner_id = memberUserId
        jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                memberUserId, "Item B");

        authAsMember();
        var result = dynamicRepository.list(SQL_COLL_CURRENT_USER, null, null, 1, 10, null);
        // memberUserId should see exactly 1 row (Item B)
        assertEquals(1, result.getCount(), "member should see exactly 1 row with their user ID");
        Map<String, Object> row = result.getData().get(0);
        assertEquals(memberUserId, ((Number) row.get("owner_id")).longValue(),
                "row should have owner_id matching the member user ID");
        assertEquals("Item B", row.get("name"), "should be Item B");

        // Restore original data
        jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = 2 WHERE \"name\" = ?",
                "Item B");
    }

    @Test
    @DisplayName("P1-F: currentUser source — admin and member bind their own IDs correctly")
    void currentUserAdminAndMemberBindDifferentIds() {
        // Admin sees rows with owner_id=1
        authAsAdmin();
        var adminResult = dynamicRepository.list(SQL_COLL_CURRENT_USER, null, null, 1, 10, null);
        assertEquals(2, adminResult.getCount(), "admin should see 2 rows");
        for (var row : adminResult.getData()) {
            assertEquals(1L, ((Number) row.get("owner_id")).longValue(),
                    "admin rows should have owner_id=1");
        }

        // Grant member permission and update a row to member's ID
        cleanupMemberPermissions(SQL_COLL_CURRENT_USER);
        grantMemberPermission(SQL_COLL_CURRENT_USER, "list", "name,status,owner_id,price,id");

        jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                memberUserId, "Item B");

        authAsMember();
        var memberResult = dynamicRepository.list(SQL_COLL_CURRENT_USER, null, null, 1, 10, null);
        assertEquals(1, memberResult.getCount(), "member should see 1 row");
        assertEquals(memberUserId,
                ((Number) memberResult.getData().get(0).get("owner_id")).longValue(),
                "member row should have their own owner_id");

        // Restore original data
        jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = 2 WHERE \"name\" = ?",
                "Item B");
    }

    @Test
    @DisplayName("P1-F: currentUser source — get() with currentUser param works")
    void currentUserGetWorks() {
        cleanupMemberPermissions(SQL_COLL_CURRENT_USER_PK);
        grantMemberPermission(SQL_COLL_CURRENT_USER_PK, "get", "name,status,owner_id,price,id");

        // Admin should be able to get recordIdA (owner_id=1)
        authAsAdmin();
        var result = dynamicRepository.get(SQL_COLL_CURRENT_USER_PK, recordIdA);
        assertNotNull(result, "admin should get record with owner_id=1");
        assertEquals(recordIdA, result.get("id"));

        // Admin should NOT be able to get recordIdB (owner_id=2)
        var nullResult = dynamicRepository.get(SQL_COLL_CURRENT_USER_PK, recordIdB);
        assertNull(nullResult, "admin should not get record with owner_id=2");
    }

    // ========== P0-E: currentUser parameter + ACL scope intersection tests ==========

    @Test
    @DisplayName("P0-E: list() applies both currentUser param AND ACL scope simultaneously")
    void currentUserListWithAclScopeBothApply() {
        cleanupMemberPermissions(SQL_COLL_CURRENT_USER);

        // Grant member list permission with ACL scope on status=active
        grantMemberPermissionWithScope(SQL_COLL_CURRENT_USER, "list", "name,status,owner_id,price,id",
                "{\"status\": {\"$eq\": \"active\"}}");

        // Save original owner_ids so we can restore them in finally block
        Long origOwnerA = jdbcTemplate.queryForObject(
                "SELECT \"owner_id\" FROM \"" + BASE_TABLE + "\" WHERE \"name\" = ?", Long.class, "Item A");
        Long origOwnerB = jdbcTemplate.queryForObject(
                "SELECT \"owner_id\" FROM \"" + BASE_TABLE + "\" WHERE \"name\" = ?", Long.class, "Item B");

        try {
            // Set Item A to memberUserId
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    memberUserId, "Item A");
            // Ensure Item B has a different owner_id so it does NOT match the currentUser param
            long otherOwnerId = memberUserId.equals(origOwnerB) ? 9999L : origOwnerB;
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    otherOwnerId, "Item B");

            authAsMember();
            // currentUser param filters to owner_id=memberUserId -> only Item A
            // ACL scope filters to status=active -> Item A only
            // Intersection: only Item A
            var result = dynamicRepository.list(SQL_COLL_CURRENT_USER, null, null, 1, 10, null);
            assertEquals(1, result.getCount(), "only record A should match both currentUser param and ACL scope");
            Map<String, Object> row = result.getData().get(0);
            assertEquals("Item A", row.get("name"));
            assertEquals("active", row.get("status"));
            assertEquals(memberUserId, ((Number) row.get("owner_id")).longValue());
        } finally {
            // Restore original data even if assertion fails
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    origOwnerA, "Item A");
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    origOwnerB, "Item B");
        }
    }

    @Test
    @DisplayName("P0-E: get() applies pk + currentUser SQL condition + get action scope")
    void currentUserGetWithAclScopeBothApply() {
        cleanupMemberPermissions(SQL_COLL_CURRENT_USER_PK);

        // Grant member get permission with scope on status=active
        grantMemberPermissionWithScope(SQL_COLL_CURRENT_USER_PK, "get", "name,status,owner_id,price,id",
                "{\"status\": {\"$eq\": \"active\"}}");

        // Save original owner_id of Item A
        Long origOwnerA = jdbcTemplate.queryForObject(
                "SELECT \"owner_id\" FROM \"" + BASE_TABLE + "\" WHERE \"name\" = ?", Long.class, "Item A");

        try {
            // Set Item A to memberUserId so member can see it via currentUser param
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    memberUserId, "Item A");

            authAsMember();

            // get(recordIdA): pk=recordIdA, owner_id=memberUserId (currentUser), status=active (scope)
            // All three conditions match -> should return the record
            Map<String, Object> result = dynamicRepository.get(SQL_COLL_CURRENT_USER_PK, recordIdA);
            assertNotNull(result, "record matching pk + currentUser + get scope should be returned");
            assertEquals("Item A", result.get("name"));
            assertEquals("active", result.get("status"));
            assertEquals(memberUserId, ((Number) result.get("owner_id")).longValue());

            // get(recordIdC): pk=recordIdC, owner_id=1 (NOT memberUserId), status=inactive (NOT in scope)
            // currentUser condition fails -> returns null (record existence not leaked)
            Map<String, Object> nullResult = dynamicRepository.get(SQL_COLL_CURRENT_USER_PK, recordIdC);
            assertNull(nullResult, "record outside currentUser param should return null");
        } finally {
            // Restore original data
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    origOwnerA, "Item A");
        }
    }

    @Test
    @DisplayName("P0-E: admin and member with different currentUser IDs see different result sets")
    void currentUserAdminAndMemberSeeDifferentResults() {
        cleanupMemberPermissions(SQL_COLL_CURRENT_USER);
        grantMemberPermission(SQL_COLL_CURRENT_USER, "list", "name,status,owner_id,price,id");

        // Save original owner_ids
        Long origOwnerA = jdbcTemplate.queryForObject(
                "SELECT \"owner_id\" FROM \"" + BASE_TABLE + "\" WHERE \"name\" = ?", Long.class, "Item A");
        Long origOwnerB = jdbcTemplate.queryForObject(
                "SELECT \"owner_id\" FROM \"" + BASE_TABLE + "\" WHERE \"name\" = ?", Long.class, "Item B");

        try {
            // Set Item A to admin's owner_id (1) and Item B to memberUserId
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = 1 WHERE \"name\" = ?",
                    "Item A");
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    memberUserId, "Item B");

            // Admin (userId=1) sees records with owner_id=1 -> records A and C
            authAsAdmin();
            var adminResult = dynamicRepository.list(SQL_COLL_CURRENT_USER, null, null, 1, 10, null);
            assertEquals(2, adminResult.getCount(), "admin should see 2 records (A and C, owner_id=1)");
            for (var row : adminResult.getData()) {
                assertEquals(1L, ((Number) row.get("owner_id")).longValue(),
                        "admin should only see records with owner_id=1");
            }

            // Member (userId=memberUserId) sees records with owner_id=memberUserId -> record B only
            authAsMember();
            var memberResult = dynamicRepository.list(SQL_COLL_CURRENT_USER, null, null, 1, 10, null);
            assertEquals(1, memberResult.getCount(), "member should see 1 record (B, owner_id=memberUserId)");
            Map<String, Object> memberRow = memberResult.getData().get(0);
            assertEquals("Item B", memberRow.get("name"));
            assertEquals(memberUserId, ((Number) memberRow.get("owner_id")).longValue(),
                    "member should only see their own record");
        } finally {
            // Restore original data
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    origOwnerA, "Item A");
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    origOwnerB, "Item B");
        }
    }

    @Test
    @DisplayName("P0-E: get() on a record outside currentUser scope returns null — does not leak record existence")
    void currentUserGetOutsideScopeReturnsNull() {
        cleanupMemberPermissions(SQL_COLL_CURRENT_USER_PK);

        // Grant member get permission with scope on status=active
        grantMemberPermissionWithScope(SQL_COLL_CURRENT_USER_PK, "get", "name,status,owner_id,price,id",
                "{\"status\": {\"$eq\": \"active\"}}");

        // Save original owner_ids to ensure data isolation
        Long origOwnerA = jdbcTemplate.queryForObject(
                "SELECT \"owner_id\" FROM \"" + BASE_TABLE + "\" WHERE \"name\" = ?", Long.class, "Item A");
        Long origOwnerB = jdbcTemplate.queryForObject(
                "SELECT \"owner_id\" FROM \"" + BASE_TABLE + "\" WHERE \"name\" = ?", Long.class, "Item B");

        try {
            // Ensure all records have owner_id different from memberUserId
            // so currentUser param filters them all out
            long otherOwnerA = memberUserId.equals(origOwnerA) ? 9998L : origOwnerA;
            long otherOwnerB = memberUserId.equals(origOwnerB) ? 9999L : origOwnerB;
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    otherOwnerA, "Item A");
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    otherOwnerB, "Item B");

            authAsMember();

            // get(recordIdA): owner_id != memberUserId -> currentUser condition fails
            // Result: null (does not leak that recordIdA exists in the database)
            Map<String, Object> result = dynamicRepository.get(SQL_COLL_CURRENT_USER_PK, recordIdA);
            assertNull(result, "record outside currentUser param should return null, not leak existence");

            // get(recordIdB): owner_id != memberUserId -> currentUser condition fails
            Map<String, Object> resultB = dynamicRepository.get(SQL_COLL_CURRENT_USER_PK, recordIdB);
            assertNull(resultB, "record outside currentUser param should return null");

            // get(recordIdC): owner_id=1 != memberUserId (currentUser fails) AND status=inactive (scope fails)
            Map<String, Object> resultC = dynamicRepository.get(SQL_COLL_CURRENT_USER_PK, recordIdC);
            assertNull(resultC, "record outside both currentUser and scope should return null");
        } finally {
            // Restore original data
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    origOwnerA, "Item A");
            jdbcTemplate.update("UPDATE \"" + BASE_TABLE + "\" SET \"owner_id\" = ? WHERE \"name\" = ?",
                    origOwnerB, "Item B");
        }
    }

    // ========== P0-D: Pagination governance — >200 records doesn't silently drop data ==========

    @Test
    @DisplayName("P0-D: SQL list with >200 records paginates correctly, count is accurate")
    void sqlListWithLargeDatasetPaginatesCorrectly() {
        authAsAdmin();
        // Create a temporary table with >200 records
        String largeTable = "sql_large_test_iso";
        String largeSqlColl = "sql_large_view_iso";
        try {
            // Create base table
            if (!runtimeService.exists(largeTable)) {
                CollectionEntity base = new CollectionEntity(largeTable, "Large Base", "physical");
                base.setTableName(largeTable);
                ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(largeTable, "name", "string"),
                    new FieldEntity(largeTable, "seq", "bigInt")));
                runtimeService.reload(largeTable);
            }

            // Clean up and insert 250 records
            jdbcTemplate.update("DELETE FROM \"" + largeTable + "\"");
            for (int i = 1; i <= 250; i++) {
                jdbcTemplate.update("INSERT INTO \"" + largeTable + "\" (\"name\", \"seq\") VALUES (?, ?)",
                    "Record " + i, i);
            }

            // Create SQL collection
            if (!runtimeService.exists(largeSqlColl)) {
                CollectionEntity sqlEnt = new CollectionEntity(largeSqlColl, "Large SQL View", "sql");
                sqlEnt.setSql("SELECT \"id\", \"name\", \"seq\" FROM \"" + largeTable + "\"");
                sqlEnt.setOptions("{\"primaryKey\": \"id\"}");
                ddlSynchronizer.createCollection(sqlEnt, List.of(
                    new FieldEntity(largeSqlColl, "id", "bigInt"),
                    new FieldEntity(largeSqlColl, "name", "string"),
                    new FieldEntity(largeSqlColl, "seq", "bigInt")));
                runtimeService.reload(largeSqlColl);
            }

            // List with maxPageSize (200) — should return 200 records out of 250
            var page1 = dynamicRepository.list(largeSqlColl, null, "seq", 1, 200, null);
            assertEquals(250, page1.getCount(), "total count should be exactly 250");
            assertEquals(200, page1.getData().size(), "page 1 should return 200 records (capped by maxPageSize)");
            assertEquals(1, ((Number) page1.getData().get(0).get("seq")).intValue(), "first record should be seq=1");

            // List page 2 — should return remaining 50 records
            var page2 = dynamicRepository.list(largeSqlColl, null, "seq", 2, 200, null);
            assertEquals(250, page2.getCount(), "total count should still be 250");
            assertEquals(50, page2.getData().size(), "page 2 should return 50 remaining records");
            assertEquals(201, ((Number) page2.getData().get(0).get("seq")).intValue(), "first record on page 2 should be seq=201");

            // Verify no data is silently dropped: count across pages matches total
            long totalAcrossPages = page1.getData().size() + page2.getData().size();
            assertEquals(250, totalAcrossPages, "sum of records across all pages should equal total count");

            // List with pageSize <= 0 should normalize to default (20)
            var defaultPage = dynamicRepository.list(largeSqlColl, null, "seq", 1, 0, null);
            assertEquals(250, defaultPage.getCount(), "total count should be correct");
            assertEquals(20, defaultPage.getData().size(), "pageSize=0 should normalize to 20 (default)");

            // List with pageSize > maxPageSize should be capped
            var cappedPage = dynamicRepository.list(largeSqlColl, null, "seq", 1, 1000, null);
            assertEquals(250, cappedPage.getCount(), "total count should be correct");
            assertEquals(200, cappedPage.getData().size(), "pageSize=1000 should be capped to maxPageSize=200");

        } finally {
            runtimeService.reload(largeSqlColl);
            runtimeService.reload(largeTable);
        }
    }

    // ========== Helper methods ==========

    /**
     * Clean up all ACL permissions for the member role on a given resource.
     * Ensures test isolation between ACL tests.
     */
    private void cleanupMemberPermissions(String resourceName) {
        Role memberRole = roleRepository.findByName("member").orElse(null);
        if (memberRole == null) fail("member role not found");

        String roleName = memberRole.getName();
        // Find and delete existing RoleResource for this resource
        roleResourceRepository.findByRoleNameAndResourceName(roleName, resourceName)
                .ifPresent(rr -> {
                    // Delete scopes first
                    List<RoleResourceScope> scopes = roleResourceScopeRepository.findByRoleResourceId(rr.getId());
                    roleResourceScopeRepository.deleteAll(scopes);
                    // Delete actions
                    List<RoleResourceAction> actions = roleResourceActionRepository.findByRoleResourceId(rr.getId());
                    roleResourceActionRepository.deleteAll(actions);
                    // Delete the resource itself
                    roleResourceRepository.delete(rr);
                });
    }

    private void grantMemberPermission(String resourceName, String action, String fields) {
        Role memberRole = roleRepository.findByName("member").orElse(null);
        if (memberRole == null) fail("member role not found");

        String roleName = memberRole.getName();
        RoleResource rr = roleResourceRepository
                .findByRoleNameAndResourceName(roleName, resourceName)
                .orElseGet(() -> {
                    RoleResource newRr = new RoleResource(roleName, resourceName);
                    return roleResourceRepository.save(newRr);
                });

        RoleResourceAction rra = new RoleResourceAction(rr.getId(), action, fields);
        roleResourceActionRepository.save(rra);
    }

    private void grantMemberPermissionWithScope(String resourceName, String action, String fields, String scopeJson) {
        Role memberRole = roleRepository.findByName("member").orElse(null);
        if (memberRole == null) fail("member role not found");

        String roleName = memberRole.getName();
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