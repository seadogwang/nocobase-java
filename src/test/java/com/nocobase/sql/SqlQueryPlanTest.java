package com.nocobase.sql;

import com.nocobase.data.CompiledFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SqlQueryPlan generation via SqlQueryCollectionExecutor.
 * Tests the plan-building methods (buildListPlan, buildGetPlan) to verify
 * correct SQL structure without executing against a database.
 */
@SpringBootTest
@ActiveProfiles("test")
class SqlQueryPlanTest {

    @Autowired
    private SqlQueryCollectionExecutor executor;

    private static final String CONFIGURED_SQL = "SELECT \"id\", \"name\", \"status\" FROM \"users\"";
    private static final SqlDialect H2_DIALECT = new H2SqlDialect();

    // ========== buildListPlan tests ==========

    @Test
    @DisplayName("buildListPlan: configured SQL is wrapped in subquery with select clause")
    void buildListPlanWrapsConfiguredSqlInSubquery() {
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                CompiledFilter.empty(), "", 1, 10, List.of(), H2_DIALECT);

        String sql = plan.getSql();
        assertTrue(sql.startsWith("SELECT * FROM ("),
                "SQL should start with SELECT * FROM (");
        assertTrue(sql.contains(CONFIGURED_SQL),
                "SQL should contain the configured SQL verbatim");
        assertTrue(sql.contains(") _nocobase_sub"),
                "SQL should wrap in subquery aliased as _nocobase_sub");
    }

    @Test
    @DisplayName("buildListPlan: empty filter produces no WHERE clause")
    void buildListPlanEmptyFilterNoWhere() {
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                CompiledFilter.empty(), "", 1, 10, List.of(), H2_DIALECT);

        String sql = plan.getSql();
        assertFalse(sql.toUpperCase().contains("WHERE"),
                "SQL should not contain WHERE when filter is empty");
        assertEquals(2, plan.getParameters().size(),
                "Parameters should have 2 entries (pageSize and offset) when filter is empty");
    }

    @Test
    @DisplayName("buildListPlan: non-empty filter produces WHERE clause with parameters")
    void buildListPlanNonEmptyFilterProducesWhere() {
        CompiledFilter filter = new CompiledFilter("\"status\" = ?", List.of("active"));
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                filter, "", 1, 10, List.of(), H2_DIALECT);

        String sql = plan.getSql();
        assertTrue(sql.contains("WHERE \"status\" = ?"),
                "SQL should contain WHERE with the filter clause");
        assertEquals(3, plan.getParameters().size(),
                "Should have 3 parameters (1 filter + 2 pagination) when filter is non-empty");
        assertEquals("active", plan.getParameters().get(0),
                "Parameter should be 'active'");
    }

    @Test
    @DisplayName("buildListPlan: sort clause produces ORDER BY")
    void buildListPlanSortProducesOrderBy() {
        String sortClause = "\"name\" ASC";
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                CompiledFilter.empty(), sortClause, 1, 10, List.of(), H2_DIALECT);

        String sql = plan.getSql();
        assertTrue(sql.contains("ORDER BY \"name\" ASC"),
                "SQL should contain ORDER BY with the sort clause");
    }

    @Test
    @DisplayName("buildListPlan: pagination produces dialect-specific LIMIT and OFFSET")
    void buildListPlanPaginationProducesLimitOffset() {
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                CompiledFilter.empty(), "", 2, 5, List.of(), H2_DIALECT);

        String sql = plan.getSql();
        assertTrue(sql.contains("LIMIT ? OFFSET ?"),
                "SQL should contain LIMIT and OFFSET placeholders via dialect");
        // page=2, pageSize=5 => offset=5, limit=5
        assertEquals(2, plan.getParameters().size(),
                "Should have 2 parameters: pageSize and offset");
        assertEquals(5, plan.getParameters().get(0),
                "First param should be pageSize (5)");
        assertEquals(5, plan.getParameters().get(1),
                "Second param should be offset = (2-1)*5 = 5");
    }

    @Test
    @DisplayName("buildListPlan: count SQL excludes sort and pagination")
    void buildListPlanCountSqlExcludesSortAndPagination() {
        CompiledFilter filter = new CompiledFilter("\"status\" = ?", List.of("active"));
        String sortClause = "\"name\" DESC";
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                filter, sortClause, 1, 10, List.of(), H2_DIALECT);

        String countSql = plan.getCountSql();
        assertNotNull(countSql, "Count SQL should not be null");
        assertTrue(countSql.startsWith("SELECT COUNT(*) FROM ("),
                "Count SQL should start with SELECT COUNT(*) FROM (");
        assertTrue(countSql.contains(CONFIGURED_SQL),
                "Count SQL should contain the configured SQL");
        assertTrue(countSql.contains(") _nocobase_sub"),
                "Count SQL should wrap in subquery aliased as _nocobase_sub");
        assertTrue(countSql.contains("WHERE \"status\" = ?"),
                "Count SQL should contain the filter WHERE clause");
        assertFalse(countSql.contains("ORDER BY"),
                "Count SQL should NOT contain ORDER BY");
        assertFalse(countSql.contains("LIMIT"),
                "Count SQL should NOT contain LIMIT");
        assertFalse(countSql.contains("OFFSET"),
                "Count SQL should NOT contain OFFSET");
        assertEquals(1, plan.getCountParameters().size(),
                "Count parameters should have 1 entry for the filter");
        assertEquals("active", plan.getCountParameters().get(0),
                "Count parameter should be 'active'");
    }

    @Test
    @DisplayName("buildListPlan: count SQL with empty filter has no WHERE")
    void buildListPlanCountSqlEmptyFilterNoWhere() {
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                CompiledFilter.empty(), "", 1, 10, List.of(), H2_DIALECT);

        String countSql = plan.getCountSql();
        assertNotNull(countSql, "Count SQL should not be null");
        assertFalse(countSql.toUpperCase().contains("WHERE"),
                "Count SQL should not contain WHERE when filter is empty");
        assertTrue(plan.getCountParameters().isEmpty(),
                "Count parameters should be empty");
    }

    @Test
    @DisplayName("buildListPlan: select clause is used in data SQL")
    void buildListPlanSelectClauseApplied() {
        String selectClause = "\"id\", \"name\"";
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, selectClause,
                CompiledFilter.empty(), "", 1, 10, List.of(), H2_DIALECT);

        String sql = plan.getSql();
        assertTrue(sql.startsWith("SELECT \"id\", \"name\" FROM ("),
                "SQL should use the provided select clause");
    }

    // ========== buildGetPlan tests ==========

    @Test
    @DisplayName("buildGetPlan: configured SQL is wrapped in subquery with SELECT *")
    void buildGetPlanWrapsConfiguredSqlInSubquery() {
        SqlQueryPlan plan = executor.buildGetPlan(CONFIGURED_SQL,
                CompiledFilter.empty(), List.of(), H2_DIALECT);

        String sql = plan.getSql();
        assertTrue(sql.startsWith("SELECT * FROM ("),
                "SQL should start with SELECT * FROM (");
        assertTrue(sql.contains(CONFIGURED_SQL),
                "SQL should contain the configured SQL verbatim");
        assertTrue(sql.contains(") _nocobase_sub"),
                "SQL should wrap in subquery aliased as _nocobase_sub");
        assertTrue(sql.contains("LIMIT ? OFFSET ?"),
                "SQL should contain dialect-generated LIMIT ? OFFSET ?");
    }

    @Test
    @DisplayName("buildGetPlan: empty filter produces no WHERE, only dialect pagination")
    void buildGetPlanEmptyFilterNoWhere() {
        SqlQueryPlan plan = executor.buildGetPlan(CONFIGURED_SQL,
                CompiledFilter.empty(), List.of(), H2_DIALECT);

        String sql = plan.getSql();
        assertFalse(sql.toUpperCase().contains("WHERE"),
                "SQL should not contain WHERE when filter is empty");
        assertTrue(sql.contains("LIMIT ? OFFSET ?"),
                "SQL should contain dialect-generated LIMIT ? OFFSET ?");
        assertEquals(2, plan.getParameters().size(),
                "Parameters should have 2 entries (limit=1, offset=0) when filter is empty");
        assertEquals(1, plan.getParameters().get(0),
                "First param should be limit=1");
        assertEquals(0, plan.getParameters().get(1),
                "Second param should be offset=0");
    }

    @Test
    @DisplayName("buildGetPlan: non-empty filter produces WHERE clause with dialect pagination")
    void buildGetPlanNonEmptyFilterProducesWhere() {
        CompiledFilter filter = new CompiledFilter("\"id\" = ? AND \"status\" = ?",
                List.of(42L, "active"));
        SqlQueryPlan plan = executor.buildGetPlan(CONFIGURED_SQL, filter, List.of(), H2_DIALECT);

        String sql = plan.getSql();
        assertTrue(sql.contains("WHERE \"id\" = ? AND \"status\" = ?"),
                "SQL should contain WHERE with the filter clause");
        assertTrue(sql.contains("LIMIT ? OFFSET ?"),
                "SQL should contain dialect-generated LIMIT ? OFFSET ?");
        // WHERE should appear before LIMIT
        int wherePos = sql.indexOf("WHERE");
        int limitPos = sql.indexOf("LIMIT");
        assertTrue(wherePos < limitPos,
                "WHERE clause should appear before LIMIT");
        assertEquals(4, plan.getParameters().size(),
                "Should have 4 parameters: id, status, limit=1, offset=0");
        assertEquals(42L, plan.getParameters().get(0),
                "First parameter should be 42L");
        assertEquals("active", plan.getParameters().get(1),
                "Second parameter should be 'active'");
        assertEquals(1, plan.getParameters().get(2),
                "Third parameter should be limit=1");
        assertEquals(0, plan.getParameters().get(3),
                "Fourth parameter should be offset=0");
    }

    @Test
    @DisplayName("buildGetPlan: count SQL is null (get has no count)")
    void buildGetPlanCountSqlIsNull() {
        SqlQueryPlan plan = executor.buildGetPlan(CONFIGURED_SQL,
                CompiledFilter.empty(), List.of(), H2_DIALECT);

        assertNull(plan.getCountSql(),
                "Get plan count SQL should be null (no count needed)");
        assertTrue(plan.getCountParameters().isEmpty(),
                "Get plan count parameters should be empty");
    }

    // ========== P0-E: Parameter ordering tests ==========

    @Test
    @DisplayName("P0-E: list data query parameter order — named SQL params → filter → limit → offset")
    void listDataQueryParamOrderNamedThenFilterThenPagination() {
        // Simulate: namedParams = ["active"], filter = ["filterVal"], pagination = [5, 0]
        List<Object> namedParams = List.of("active");
        CompiledFilter filter = new CompiledFilter("\"status\" = ?", List.of("filterVal"));
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                filter, "", 1, 5, namedParams, H2_DIALECT);

        List<Object> params = plan.getParameters();
        assertEquals(4, params.size(),
                "Should have 4 params: 1 named + 1 filter + 2 pagination");
        assertEquals("active", params.get(0),
                "Params[0] should be named SQL param value");
        assertEquals("filterVal", params.get(1),
                "Params[1] should be filter/scope param value");
        assertEquals(5, params.get(2),
                "Params[2] should be pageSize (limit)");
        assertEquals(0, params.get(3),
                "Params[3] should be offset");
    }

    @Test
    @DisplayName("P0-E: list count query parameter order — named SQL params → filter (no pagination)")
    void listCountQueryParamOrderNamedThenFilterNoPagination() {
        List<Object> namedParams = List.of("active");
        CompiledFilter filter = new CompiledFilter("\"owner_id\" = ?", List.of(1L));
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                filter, "", 1, 10, namedParams, H2_DIALECT);

        List<Object> countParams = plan.getCountParameters();
        assertEquals(2, countParams.size(),
                "Count params should have 2 entries: 1 named + 1 filter");
        assertEquals("active", countParams.get(0),
                "Count params[0] should be named SQL param value");
        assertEquals(1L, countParams.get(1),
                "Count params[1] should be filter/scope param value");

        // Count SQL should NOT contain LIMIT or OFFSET
        String countSql = plan.getCountSql();
        assertFalse(countSql.contains("LIMIT"), "Count SQL should not contain LIMIT");
        assertFalse(countSql.contains("OFFSET"), "Count SQL should not contain OFFSET");
    }

    @Test
    @DisplayName("P0-E: get query parameter order — named SQL params → filter params → pagination")
    void getQueryParamOrderNamedThenFilter() {
        List<Object> namedParams = List.of("active");
        CompiledFilter filter = new CompiledFilter(
                "\"id\" = ? AND \"owner_id\" = ?", List.of(42L, 1L));
        SqlQueryPlan plan = executor.buildGetPlan(CONFIGURED_SQL, filter, namedParams, H2_DIALECT);

        List<Object> params = plan.getParameters();
        assertEquals(5, params.size(),
                "Should have 5 params: 1 named + 2 filter + 2 pagination");
        assertEquals("active", params.get(0),
                "Params[0] should be named SQL param value");
        assertEquals(42L, params.get(1),
                "Params[1] should be first filter param (primary key)");
        assertEquals(1L, params.get(2),
                "Params[2] should be second filter param (scope)");
        assertEquals(1, params.get(3),
                "Params[3] should be limit=1");
        assertEquals(0, params.get(4),
                "Params[4] should be offset=0");

        // Verify SQL contains dialect-generated LIMIT ? OFFSET ?
        String sql = plan.getSql();
        assertTrue(sql.contains("LIMIT ? OFFSET ?"),
                "SQL should contain dialect-generated LIMIT ? OFFSET ?");

        // Count SQL should be null for get plans
        assertNull(plan.getCountSql(), "Get plan should have null count SQL");
    }

    @Test
    @DisplayName("P0-E: list data query with named params but empty filter")
    void listDataQueryParamOrderNamedParamsOnly() {
        // Only named params, no filter — order should still be: named → pagination
        List<Object> namedParams = List.of("active", "draft");
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                CompiledFilter.empty(), "", 2, 20, namedParams, H2_DIALECT);

        List<Object> params = plan.getParameters();
        assertEquals(4, params.size(),
                "Should have 4 params: 2 named + 2 pagination");
        assertEquals("active", params.get(0), "Params[0] should be first named param");
        assertEquals("draft", params.get(1), "Params[1] should be second named param");
        assertEquals(20, params.get(2), "Params[2] should be pageSize");
        assertEquals(20, params.get(3), "Params[3] should be offset = (2-1)*20 = 20");
    }

    @Test
    @DisplayName("P0-E: list data query with filter but no named params")
    void listDataQueryParamOrderFilterOnly() {
        // No named params, only filter — order should be: filter → pagination
        CompiledFilter filter = new CompiledFilter("\"status\" = ?", List.of("active"));
        SqlQueryPlan plan = executor.buildListPlan(CONFIGURED_SQL, "*",
                filter, "", 1, 10, List.of(), H2_DIALECT);

        List<Object> params = plan.getParameters();
        assertEquals(3, params.size(),
                "Should have 3 params: 1 filter + 2 pagination");
        assertEquals("active", params.get(0), "Params[0] should be filter param");
        assertEquals(10, params.get(1), "Params[1] should be pageSize");
        assertEquals(0, params.get(2), "Params[2] should be offset");
    }
}