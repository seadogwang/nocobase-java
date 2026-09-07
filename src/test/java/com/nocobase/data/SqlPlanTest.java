package com.nocobase.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SqlPlan behavior, especially around SQL leak prevention.
 */
class SqlPlanTest {

    @Test
    @DisplayName("P0-D: SqlPlan.toString() does not contain SQL text")
    void toStringDoesNotContainSql() {
        SqlPlan plan = new SqlPlan(
                "SELECT * FROM users WHERE id = ?",
                List.of(1),
                "SELECT COUNT(*) FROM users WHERE id = ?",
                List.of(1),
                "list",
                "users"
        );

        String result = plan.toString();

        // Must include safe metadata
        assertTrue(result.contains("operation='list'"),
                "toString should contain operation");
        assertTrue(result.contains("collection='users'"),
                "toString should contain collection name");
        assertTrue(result.contains("parameterCount=1"),
                "toString should contain parameter count");
        assertTrue(result.contains("hasCountSql=true"),
                "toString should indicate countSql presence");

        // Must NOT contain SQL text
        assertFalse(result.contains("SELECT"),
                "toString must not contain SQL keywords (SELECT)");
        assertFalse(result.contains("FROM"),
                "toString must not contain SQL keywords (FROM)");
        assertFalse(result.contains("WHERE"),
                "toString must not contain SQL keywords (WHERE)");
        assertFalse(result.contains("COUNT"),
                "toString must not contain SQL keywords (COUNT)");

        // Verify the raw SQL is still accessible via getter (not removed from object)
        assertEquals("SELECT * FROM users WHERE id = ?", plan.getSql());
        assertEquals("SELECT COUNT(*) FROM users WHERE id = ?", plan.getCountSql());
    }

    @Test
    @DisplayName("P0-D: SqlPlan.toString() with null countSql")
    void toStringWithNullCountSql() {
        SqlPlan plan = new SqlPlan(
                "INSERT INTO users (name) VALUES (?)",
                List.of("test"),
                null,
                null,
                "create",
                "users"
        );

        String result = plan.toString();

        assertTrue(result.contains("hasCountSql=false"),
                "toString should indicate countSql is absent");

        assertFalse(result.contains("INSERT"),
                "toString must not contain SQL keywords");
    }

    @Test
    @DisplayName("P0-D: SqlPlan.toString() with null parameters")
    void toStringWithNullParameters() {
        SqlPlan plan = new SqlPlan(
                "DELETE FROM users",
                null,
                null,
                null,
                "destroy",
                "users"
        );

        String result = plan.toString();

        assertTrue(result.contains("parameterCount=0"),
                "toString should handle null parameters as count 0");

        assertFalse(result.contains("DELETE"),
                "toString must not contain SQL keywords");
    }
}