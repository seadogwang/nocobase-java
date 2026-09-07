package com.nocobase.data;

import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.sql.H2SqlDialect;
import com.nocobase.sql.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PhysicalSqlBuilder} — all plan types with H2 dialect.
 */
class PhysicalSqlBuilderTest {

    private SqlDialect dialect;
    private PhysicalSqlBuilder builder;
    private CollectionDefinition def;

    @BeforeEach
    void setUp() {
        dialect = new H2SqlDialect();
        builder = new PhysicalSqlBuilder(dialect);
        def = CollectionDefinition.builder("users")
                .tableName("users")
                .build();
    }

    // ── buildListPlan ─────────────────────────────────────────────────────

    @Test
    @DisplayName("buildListPlan with all clauses and pagination")
    void buildListPlanWithAllClauses() {
        String selectClause = "*";
        String whereClause = "\"status\" = ?";
        List<Object> params = List.of("active");
        String sortClause = "\"created_at\" DESC";

        SqlPlan plan = builder.buildListPlan(def, selectClause, whereClause, params, sortClause, 2, 10);

        assertEquals("SELECT * FROM \"users\" WHERE \"status\" = ? ORDER BY \"created_at\" DESC LIMIT ? OFFSET ?",
                plan.getSql());
        assertEquals(List.of("active", 10, 10), plan.getParameters());
        assertEquals("SELECT COUNT(*) FROM \"users\" WHERE \"status\" = ?", plan.getCountSql());
        assertEquals(List.of("active"), plan.getCountParameters());
        assertEquals("list", plan.getOperation());
        assertEquals("users", plan.getCollectionName());
    }

    @Test
    @DisplayName("buildListPlan without WHERE or sort")
    void buildListPlanWithoutWhereOrSort() {
        SqlPlan plan = builder.buildListPlan(def, "*", "", List.of(), "", 1, 20);

        assertTrue(plan.getSql().startsWith("SELECT * FROM \"users\""));
        assertEquals("SELECT COUNT(*) FROM \"users\"", plan.getCountSql());
        assertEquals(0, plan.getCountParameters().size());
        // params: pageSize=20, offset=0
        assertEquals(List.of(20, 0), plan.getParameters());
    }

    @Test
    @DisplayName("buildListPlan with page 1 offset 0")
    void buildListPlanPage1() {
        SqlPlan plan = builder.buildListPlan(def, "\"id\", \"name\"", "", List.of(), "", 1, 5);

        assertEquals(List.of(5, 0), plan.getParameters());
    }

    @Test
    @DisplayName("buildListPlan with page 3 offset calculated correctly")
    void buildListPlanPage3() {
        SqlPlan plan = builder.buildListPlan(def, "*", "", List.of(), "", 3, 25);

        // pageSize=25, offset=(3-1)*25=50
        assertEquals(List.of(25, 50), plan.getParameters());
    }

    // ── buildCountPlan ────────────────────────────────────────────────────

    @Test
    @DisplayName("buildCountPlan with WHERE clause")
    void buildCountPlanWithWhere() {
        String whereClause = "\"age\" > ?";
        List<Object> params = List.of(18);

        SqlPlan plan = builder.buildCountPlan(def, whereClause, params);

        assertEquals("SELECT COUNT(*) FROM \"users\" WHERE \"age\" > ?", plan.getSql());
        assertEquals(List.of(18), plan.getParameters());
        assertNull(plan.getCountSql());
        assertEquals("count", plan.getOperation());
    }

    @Test
    @DisplayName("buildCountPlan without WHERE")
    void buildCountPlanWithoutWhere() {
        SqlPlan plan = builder.buildCountPlan(def, "", List.of());

        assertEquals("SELECT COUNT(*) FROM \"users\"", plan.getSql());
        assertTrue(plan.getParameters().isEmpty());
    }

    @Test
    @DisplayName("buildCountPlan with null WHERE")
    void buildCountPlanNullWhere() {
        SqlPlan plan = builder.buildCountPlan(def, null, List.of());

        assertEquals("SELECT COUNT(*) FROM \"users\"", plan.getSql());
    }

    // ── buildGetPlan ──────────────────────────────────────────────────────

    @Test
    @DisplayName("buildGetPlan with WHERE clause")
    void buildGetPlanWithWhere() {
        String whereClause = "\"id\" = ?";
        List<Object> params = List.of(42);

        SqlPlan plan = builder.buildGetPlan(def, whereClause, params);

        assertEquals("SELECT * FROM \"users\" WHERE \"id\" = ? LIMIT ? OFFSET ?", plan.getSql());
        assertEquals(List.of(42, 1, 0), plan.getParameters());
        assertEquals("get", plan.getOperation());
    }

    @Test
    @DisplayName("buildGetPlan without WHERE")
    void buildGetPlanWithoutWhere() {
        SqlPlan plan = builder.buildGetPlan(def, "", List.of());

        assertEquals("SELECT * FROM \"users\" LIMIT ? OFFSET ?", plan.getSql());
        assertEquals(List.of(1, 0), plan.getParameters());
    }

    // ── buildInsertPlan ───────────────────────────────────────────────────

    @Test
    @DisplayName("buildInsertPlan with multiple columns")
    void buildInsertPlanMultipleColumns() {
        Map<String, String> columnMapping = new LinkedHashMap<>();
        columnMapping.put("name", "name");
        columnMapping.put("email", "email");
        List<Object> params = List.of("Alice", "alice@example.com");

        SqlPlan plan = builder.buildInsertPlan(def, columnMapping, params);

        assertEquals("INSERT INTO \"users\" (\"name\", \"email\") VALUES (?, ?)", plan.getSql());
        assertEquals(List.of("Alice", "alice@example.com"), plan.getParameters());
        assertEquals("insert", plan.getOperation());
    }

    @Test
    @DisplayName("buildInsertPlan with field-to-column mapping")
    void buildInsertPlanWithFieldMapping() {
        Map<String, String> columnMapping = new LinkedHashMap<>();
        columnMapping.put("userName", "user_name");
        columnMapping.put("createdAt", "created_at");
        List<Object> params = List.of("Bob", "2024-01-01");

        SqlPlan plan = builder.buildInsertPlan(def, columnMapping, params);

        assertEquals("INSERT INTO \"users\" (\"user_name\", \"created_at\") VALUES (?, ?)", plan.getSql());
    }

    @Test
    @DisplayName("buildInsertPlan with single column")
    void buildInsertPlanSingleColumn() {
        Map<String, String> columnMapping = new LinkedHashMap<>();
        columnMapping.put("name", "name");
        List<Object> params = List.of("Charlie");

        SqlPlan plan = builder.buildInsertPlan(def, columnMapping, params);

        assertEquals("INSERT INTO \"users\" (\"name\") VALUES (?)", plan.getSql());
    }

    // ── buildUpdatePlan ───────────────────────────────────────────────────

    @Test
    @DisplayName("buildUpdatePlan with SET and WHERE")
    void buildUpdatePlanWithSetAndWhere() {
        Map<String, String> columnMapping = new LinkedHashMap<>();
        columnMapping.put("name", "name");
        columnMapping.put("status", "status");
        List<Object> setParams = List.of("NewName", "inactive");
        String whereClause = "\"id\" = ?";
        List<Object> whereParams = List.of(1);

        SqlPlan plan = builder.buildUpdatePlan(def, columnMapping, setParams, whereClause, whereParams);

        assertEquals("UPDATE \"users\" SET \"name\" = ?, \"status\" = ? WHERE \"id\" = ?", plan.getSql());
        assertEquals(List.of("NewName", "inactive", 1), plan.getParameters());
        assertEquals("update", plan.getOperation());
    }

    @Test
    @DisplayName("buildUpdatePlan with complex WHERE")
    void buildUpdatePlanComplexWhere() {
        Map<String, String> columnMapping = new LinkedHashMap<>();
        columnMapping.put("deleted", "deleted");
        List<Object> setParams = List.of(true);
        String whereClause = "(\"org_id\" = ?) AND \"id\" = ?";
        List<Object> whereParams = List.of(10, 5);

        SqlPlan plan = builder.buildUpdatePlan(def, columnMapping, setParams, whereClause, whereParams);

        assertEquals("UPDATE \"users\" SET \"deleted\" = ? WHERE (\"org_id\" = ?) AND \"id\" = ?", plan.getSql());
        assertEquals(List.of(true, 10, 5), plan.getParameters());
    }

    @Test
    @DisplayName("buildUpdatePlan with field-to-column mapping")
    void buildUpdatePlanFieldMapping() {
        Map<String, String> columnMapping = new LinkedHashMap<>();
        columnMapping.put("displayName", "display_name");
        columnMapping.put("emailAddr", "email_addr");
        List<Object> setParams = List.of("Dave", "dave@test.com");
        String whereClause = "\"id\" = ?";
        List<Object> whereParams = List.of(99);

        SqlPlan plan = builder.buildUpdatePlan(def, columnMapping, setParams, whereClause, whereParams);

        assertEquals("UPDATE \"users\" SET \"display_name\" = ?, \"email_addr\" = ? WHERE \"id\" = ?", plan.getSql());
    }

    // ── buildDeletePlan ───────────────────────────────────────────────────

    @Test
    @DisplayName("buildDeletePlan with WHERE clause")
    void buildDeletePlanWithWhere() {
        String whereClause = "\"id\" = ?";
        List<Object> params = List.of(7);

        SqlPlan plan = builder.buildDeletePlan(def, whereClause, params);

        assertEquals("DELETE FROM \"users\" WHERE \"id\" = ?", plan.getSql());
        assertEquals(List.of(7), plan.getParameters());
        assertEquals("delete", plan.getOperation());
    }

    @Test
    @DisplayName("buildDeletePlan with composite WHERE")
    void buildDeletePlanCompositeWhere() {
        String whereClause = "\"source_key\" = ? AND \"other_key\" = ?";
        List<Object> params = List.of(1, 2);

        SqlPlan plan = builder.buildDeletePlan(def, whereClause, params);

        assertEquals("DELETE FROM \"users\" WHERE \"source_key\" = ? AND \"other_key\" = ?", plan.getSql());
        assertEquals(List.of(1, 2), plan.getParameters());
    }

    @Test
    @DisplayName("buildDeletePlan without WHERE (delete all)")
    void buildDeletePlanWithoutWhere() {
        SqlPlan plan = builder.buildDeletePlan(def, "", List.of());

        assertEquals("DELETE FROM \"users\"", plan.getSql());
        assertTrue(plan.getParameters().isEmpty());
    }

    // ── table name quoting ────────────────────────────────────────────────

    @Test
    @DisplayName("table name is properly quoted")
    void tableNameQuoted() {
        CollectionDefinition def2 = CollectionDefinition.builder("userProfiles")
                .tableName("user_profiles")
                .build();

        SqlPlan plan = builder.buildGetPlan(def2, "\"id\" = ?", List.of(1));

        assertTrue(plan.getSql().contains("\"user_profiles\""));
    }

    // ── SqlPlan POJO ──────────────────────────────────────────────────────

    @Test
    @DisplayName("SqlPlan getParametersArray returns object array")
    void sqlPlanParametersArray() {
        SqlPlan plan = builder.buildGetPlan(def, "\"id\" = ?", List.of(1));

        Object[] arr = plan.getParametersArray();
        assertEquals(3, arr.length);
        assertEquals(1, arr[0]);
    }

    @Test
    @DisplayName("SqlPlan count fields are null for non-list plans")
    void sqlPlanCountFieldsNullForNonList() {
        SqlPlan plan = builder.buildGetPlan(def, "\"id\" = ?", List.of(1));

        assertNull(plan.getCountSql());
        assertNull(plan.getCountParameters());
        assertNull(plan.getCountParametersArray());
    }

    @Test
    @DisplayName("SqlPlan toString is useful")
    void sqlPlanToString() {
        SqlPlan plan = builder.buildGetPlan(def, "\"id\" = ?", List.of(1));

        String str = plan.toString();
        assertTrue(str.contains("get"));
        assertTrue(str.contains("users"));
        assertTrue(str.contains("parameterCount=3"));
        assertTrue(str.contains("hasCountSql=false"));
        // SQL text must NOT appear in toString
        assertFalse(str.contains("SELECT"));
    }
}