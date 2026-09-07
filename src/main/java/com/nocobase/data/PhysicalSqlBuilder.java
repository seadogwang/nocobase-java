package com.nocobase.data;

import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.sql.SqlDialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates parameterized SQL plans for physical collections.
 *
 * <p>All table and column names are quoted via {@link SqlDialect#quoteIdentifier(String)}.
 * All values are bound as {@code ?} placeholders. Pagination uses the dialect's
 * {@link SqlDialect#getLimitOffsetClause()}.
 *
 * <p>This builder produces {@link SqlPlan} objects — it never executes SQL.
 * Callers use {@link org.springframework.jdbc.core.JdbcTemplate} to execute
 * the plan's SQL with its parameters.
 */
public class PhysicalSqlBuilder {

    private final SqlDialect dialect;

    public PhysicalSqlBuilder(SqlDialect dialect) {
        this.dialect = dialect;
    }

    // ── identifier helpers ────────────────────────────────────────────────

    private String quote(String identifier) {
        return dialect.quoteIdentifier(identifier);
    }

    private String tableName(CollectionDefinition def) {
        return quote(def.getTableName());
    }

    // ── plan builders ─────────────────────────────────────────────────────

    /**
     * Build a SELECT plan with pagination and a companion COUNT plan.
     * The returned {@link SqlPlan} carries both {@code sql} (data query)
     * and {@code countSql} (total row count).
     *
     * @param def         collection metadata
     * @param selectClause column list, e.g. {@code "*"} or {@code "\"name\", \"age\""}
     * @param whereClause  pre-built WHERE clause with {@code ?} placeholders (may be empty)
     * @param params       bound parameter values for the WHERE clause
     * @param sortClause   pre-built ORDER BY clause (may be empty)
     * @param page         1-based page number
     * @param pageSize     records per page
     * @return SqlPlan with both data and count SQL
     */
    public SqlPlan buildListPlan(CollectionDefinition def, String selectClause, String whereClause,
                                 List<Object> params, String sortClause, int page, int pageSize) {
        String tbl = tableName(def);
        List<Object> sqlParams = new ArrayList<>();

        StringBuilder sql = new StringBuilder("SELECT ").append(selectClause).append(" FROM ").append(tbl);

        if (whereClause != null && !whereClause.isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
            sqlParams.addAll(params);
        }

        if (sortClause != null && !sortClause.isEmpty()) {
            sql.append(" ORDER BY ").append(sortClause);
        }

        int offset = (page - 1) * pageSize;
        sql.append(" ").append(dialect.getLimitOffsetClause());
        sqlParams.add(pageSize);
        sqlParams.add(offset);

        // Count SQL — same WHERE, no ORDER BY / pagination
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM ").append(tbl);
        List<Object> countParams = new ArrayList<>();
        if (whereClause != null && !whereClause.isEmpty()) {
            countSql.append(" WHERE ").append(whereClause);
            countParams.addAll(params);
        }

        return new SqlPlan(sql.toString(), sqlParams, countSql.toString(), countParams, "list", def.getName());
    }

    /**
     * Build a SELECT plan without LIMIT/OFFSET — returns ALL matching rows.
     * Used by {@link DynamicRepository#listLinks} for through-table queries
     * that must return all links for a given set of source IDs.
     *
     * <p>Unlike {@link #buildListPlan}, this method does NOT paginate.
     * Each batch of source IDs should return ALL matching through rows.
     *
     * @param def          collection metadata
     * @param selectClause column list, e.g. {@code "\"source_id\", \"target_id\""}
     * @param whereClause  pre-built WHERE clause with {@code ?} placeholders
     * @param params       bound parameter values for the WHERE clause
     * @return SqlPlan with the SELECT (no LIMIT/OFFSET, no count SQL)
     */
    public SqlPlan buildListLinksPlan(CollectionDefinition def, String selectClause,
                                      String whereClause, List<Object> params) {
        String tbl = tableName(def);
        List<Object> sqlParams = new ArrayList<>();

        StringBuilder sql = new StringBuilder("SELECT ").append(selectClause).append(" FROM ").append(tbl);

        if (whereClause != null && !whereClause.isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
            sqlParams.addAll(params);
        }

        return new SqlPlan(sql.toString(), sqlParams, null, null, "listLinks", def.getName());
    }

    /**
     * Build a {@code SELECT COUNT(*)} plan.
     *
     * @param def         collection metadata
     * @param whereClause pre-built WHERE clause (may be empty)
     * @param params      bound parameter values
     * @return SqlPlan for the count query
     */
    public SqlPlan buildCountPlan(CollectionDefinition def, String whereClause, List<Object> params) {
        String tbl = tableName(def);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(tbl);
        List<Object> sqlParams = new ArrayList<>();

        if (whereClause != null && !whereClause.isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
            sqlParams.addAll(params);
        }

        return new SqlPlan(sql.toString(), sqlParams, null, null, "count", def.getName());
    }

    /**
     * Build a {@code SELECT * LIMIT 1} plan for single-record retrieval.
     *
     * @param def         collection metadata
     * @param whereClause pre-built WHERE clause (may be empty)
     * @param params      bound parameter values
     * @return SqlPlan for the single-row lookup
     */
    public SqlPlan buildGetPlan(CollectionDefinition def, String whereClause, List<Object> params) {
        String tbl = tableName(def);
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tbl);
        List<Object> sqlParams = new ArrayList<>();

        if (whereClause != null && !whereClause.isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
            sqlParams.addAll(params);
        }

        sql.append(" ").append(dialect.getLimitOffsetClause());
        sqlParams.add(1);
        sqlParams.add(0);

        return new SqlPlan(sql.toString(), sqlParams, null, null, "get", def.getName());
    }

    /**
     * Build an {@code INSERT INTO} plan.
     *
     * @param def           collection metadata
     * @param columnMapping field name to column name mapping (ordered)
     * @param params        bound parameter values in the same order as {@code columnMapping}
     * @return SqlPlan for the insert
     */
    public SqlPlan buildInsertPlan(CollectionDefinition def, Map<String, String> columnMapping,
                                   List<Object> params) {
        String tbl = tableName(def);
        StringBuilder columns = new StringBuilder();
        StringBuilder values = new StringBuilder();

        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            if (columns.length() > 0) {
                columns.append(", ");
                values.append(", ");
            }
            columns.append(quote(entry.getValue()));
            values.append("?");
        }

        String sql = "INSERT INTO " + tbl + " (" + columns + ") VALUES (" + values + ")";
        return new SqlPlan(sql, params, null, null, "insert", def.getName());
    }

    /**
     * Build an {@code UPDATE} plan.
     *
     * @param def           collection metadata
     * @param columnMapping field name to column name mapping (ordered)
     * @param setParams     bound values for the SET clause, in columnMapping order
     * @param whereClause   pre-built WHERE clause (may be empty)
     * @param whereParams   bound values for the WHERE clause
     * @return SqlPlan for the update
     */
    public SqlPlan buildUpdatePlan(CollectionDefinition def, Map<String, String> columnMapping,
                                   List<Object> setParams, String whereClause, List<Object> whereParams) {
        String tbl = tableName(def);
        StringBuilder setClause = new StringBuilder();

        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            if (setClause.length() > 0) {
                setClause.append(", ");
            }
            setClause.append(quote(entry.getValue())).append(" = ?");
        }

        List<Object> allParams = new ArrayList<>(setParams);
        if (whereParams != null) {
            allParams.addAll(whereParams);
        }

        String sql = "UPDATE " + tbl + " SET " + setClause;
        if (whereClause != null && !whereClause.isEmpty()) {
            sql += " WHERE " + whereClause;
        }

        return new SqlPlan(sql, allParams, null, null, "update", def.getName());
    }

    /**
     * Build a {@code DELETE FROM} plan.
     *
     * @param def         collection metadata
     * @param whereClause pre-built WHERE clause (may be empty for "delete all")
     * @param params      bound parameter values
     * @return SqlPlan for the delete
     */
    public SqlPlan buildDeletePlan(CollectionDefinition def, String whereClause, List<Object> params) {
        String tbl = tableName(def);
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(tbl);
        List<Object> sqlParams = new ArrayList<>();

        if (whereClause != null && !whereClause.isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
            sqlParams.addAll(params);
        }

        return new SqlPlan(sql.toString(), sqlParams, null, null, "delete", def.getName());
    }
}