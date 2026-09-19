package com.nocobase.sql;

import com.nocobase.data.CompiledFilter;
import com.nocobase.data.FilterCompiler;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.FieldDefinition;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes SQL query collections.
 * Only called by DynamicRepository -- never directly by controllers or services.
 */
@Component
public class SqlQueryCollectionExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryCollectionExecutor.class);

    // -- query governance defaults (overridable via application properties) --

    @Value("${nocobase.sql.query-timeout-seconds:30}")
    private int queryTimeoutSeconds = 30;

    @Value("${nocobase.sql.validation-timeout-seconds:5}")
    private int validationTimeoutSeconds = 5;

    @Value("${nocobase.sql.max-page-size:200}")
    private int maxPageSize = 200;

    private final SqlDataSourceResolver dataSourceResolver;
    private final SqlParameterResolver parameterResolver;

    public SqlQueryCollectionExecutor(SqlDataSourceResolver dataSourceResolver,
                                       SqlParameterResolver parameterResolver) {
        this.dataSourceResolver = dataSourceResolver;
        this.parameterResolver = parameterResolver;
    }

    @PostConstruct
    void validateConfig() {
        if (maxPageSize <= 0) {
            log.warn("nocobase.sql.max-page-size is {} (must be positive), falling back to 200", maxPageSize);
            maxPageSize = 200;
        }
        if (queryTimeoutSeconds <= 0) {
            log.warn("nocobase.sql.query-timeout-seconds is {} (must be positive), falling back to 30", queryTimeoutSeconds);
            queryTimeoutSeconds = 30;
        }
        if (validationTimeoutSeconds <= 0) {
            log.warn("nocobase.sql.validation-timeout-seconds is {} (must be positive), falling back to 5", validationTimeoutSeconds);
            validationTimeoutSeconds = 5;
        }
    }

    /**
     * Execute a list query on a SQL collection.
     */
    public ListResult executeList(CollectionDefinition def, Map<String, Object> filter,
                                   String sort, int page, int pageSize, String fields) {
        long totalStart = System.currentTimeMillis();
        boolean success = false;
        int returnedRows = 0;
        long countDurationMs = 0;
        long dataDurationMs = 0;
        try {
            // -- query governance: normalize page and cap pageSize --
            page = Math.max(1, page);
            if (pageSize <= 0) {
                pageSize = 20; // default consistent with GenericCrudController
            } else if (pageSize > maxPageSize) {
                pageSize = maxPageSize;
            }

            String configuredSql = def.getSql();
            SqlValidator.validate(configuredSql);

            // Hydrate: parse named parameters and bind static values
            SqlParameterMetadata paramMeta = SqlParameterMetadata.from(def);
            HydratedSql hydrated = hydrateSql(configuredSql, paramMeta);

            CompiledFilter compiledFilter = filter != null ? FilterCompiler.compile(filter, def) : CompiledFilter.empty();

            SqlDialect dialect = dataSourceResolver.resolveDialect(def.getDataSourceKey());
            String sortClause = buildSortClause(sort, def, dialect);
            String selectClause = buildSelectClause(fields, def, dialect);

            SqlQueryPlan plan = buildListPlan(hydrated.sql, selectClause, compiledFilter, sortClause,
                    page, pageSize, hydrated.paramValues, dialect);

            // Execute count -- wrap in try-catch to prevent SQL leakage in error messages
            Long total;
            List<Map<String, Object>> rows;
            try {
                // P0-C: statement-level query timeout -- no shared state modified
                JdbcTemplate jdbc = dataSourceResolver.resolve(def.getDataSourceKey());

                // Count phase
                long countStart = System.currentTimeMillis();
                total = jdbc.query(
                        (Connection con) -> {
                            PreparedStatement ps = con.prepareStatement(plan.getCountSql());
                            ps.setQueryTimeout(queryTimeoutSeconds);
                            Object[] params = plan.getCountParametersArray();
                            for (int i = 0; i < params.length; i++) {
                                ps.setObject(i + 1, params[i]);
                            }
                            return ps;
                        },
                        (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong(1) : 0L);
                countDurationMs = System.currentTimeMillis() - countStart;

                // Data phase
                long dataStart = System.currentTimeMillis();
                rows = jdbc.query(
                        (Connection con) -> {
                            PreparedStatement ps = con.prepareStatement(plan.getSql());
                            ps.setQueryTimeout(queryTimeoutSeconds);
                            Object[] params = plan.getParametersArray();
                            for (int i = 0; i < params.length; i++) {
                                ps.setObject(i + 1, params[i]);
                            }
                            return ps;
                        },
                        (ResultSetExtractor<List<Map<String, Object>>>) rs -> {
                            List<Map<String, Object>> results = new ArrayList<>();
                            ResultSetMetaData meta = rs.getMetaData();
                            int colCount = meta.getColumnCount();
                            while (rs.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int i = 1; i <= colCount; i++) {
                                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                                }
                                results.add(row);
                            }
                            return results;
                        });
                dataDurationMs = System.currentTimeMillis() - dataStart;
            } catch (Exception e) {
                // A genuinely unavailable data source must surface as 503 (service
                // unavailable, retry later), not a generic 500 SQL error, so the
                // client can distinguish transient infrastructure failure from a
                // real query error. Re-throw it unwrapped to reach the dedicated
                // @ExceptionHandler(DataSourceUnavailableException.class) -> 503.
                if (e instanceof DataSourceUnavailableException) {
                    throw (DataSourceUnavailableException) e;
                }
                log.error("SQL execution error for collection '{}': {} [{}]",
                        def.getName(), SqlErrorSanitizer.sanitizeForLog(e.getMessage()), e.getClass().getSimpleName());
                throw new SqlCollectionExecutionException(
                        SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
            }
            success = true;
            returnedRows = rows.size();
            return new ListResult(rows, total, page, pageSize);
        } finally {
            long totalDurationMs = System.currentTimeMillis() - totalStart;
            if (success) {
                log.info("SQL collection operation: collection={} dataSourceKey={} action=list "
                        + "countDurationMs={} dataDurationMs={} totalDurationMs={} "
                        + "returnedRows={} page={} pageSize={} status=success",
                        def.getName(), def.getDataSourceKey(), countDurationMs, dataDurationMs,
                        totalDurationMs, returnedRows, page, pageSize);
            } else {
                log.warn("SQL collection operation: collection={} dataSourceKey={} action=list "
                        + "totalDurationMs={} status=failure",
                        def.getName(), def.getDataSourceKey(), totalDurationMs);
            }
        }
    }

    /**
     * Hydrate a configured SQL by parsing named parameters and resolving values.
     * Named parameters are converted to JDBC ? placeholders.
     * Values are resolved via {@link SqlParameterResolver} (supports static and currentUser sources).
     */
    HydratedSql hydrateSql(String configuredSql, SqlParameterMetadata paramMeta) {
        SqlNamedParameterParser.Result parseResult = SqlNamedParameterParser.parse(configuredSql);
        if (parseResult.getParameterNames().isEmpty()) {
            return new HydratedSql(configuredSql, List.of());
        }
        List<Object> values = parameterResolver.resolve(paramMeta, parseResult.getParameterNames());
        return new HydratedSql(parseResult.getSql(), values);
    }

    /**
     * Holds the hydrated SQL (with ? placeholders) and the bound parameter values.
     */
    static class HydratedSql {
        final String sql;
        final List<Object> paramValues;

        HydratedSql(String sql, List<Object> paramValues) {
            this.sql = sql;
            this.paramValues = paramValues;
        }
    }

    /**
     * Build a SqlQueryPlan for a list query.
     * Separates SQL construction from execution for clarity and testability.
     */
    SqlQueryPlan buildListPlan(String configuredSql, String selectClause,
                                CompiledFilter compiledFilter, String sortClause,
                                int page, int pageSize, List<Object> namedParams,
                                SqlDialect dialect) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectClause).append(" FROM (");
        sql.append(configuredSql);
        sql.append(") _nocobase_sub");

        List<Object> params = new ArrayList<>(namedParams);
        if (!compiledFilter.isEmpty()) {
            sql.append(" WHERE ").append(compiledFilter.getWhereClause());
            params.addAll(compiledFilter.getParameters());
        }

        if (!sortClause.isEmpty()) {
            sql.append(" ORDER BY ").append(sortClause);
        }

        // Count SQL -- same filtering, no field projection, no sort, no pagination
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM (")
                .append(configuredSql)
                .append(") _nocobase_sub");
        List<Object> countParams = new ArrayList<>(namedParams);
        if (!compiledFilter.isEmpty()) {
            countSql.append(" WHERE ").append(compiledFilter.getWhereClause());
            countParams.addAll(compiledFilter.getParameters());
        }

        // Pagination via dialect
        int offset = (page - 1) * pageSize;
        sql.append(" ").append(dialect.getLimitOffsetClause());
        params.add(pageSize);
        params.add(offset);

        return new SqlQueryPlan(sql.toString(), params, countSql.toString(), countParams);
    }

    private String buildSelectClause(String fields, CollectionDefinition def, SqlDialect dialect) {
        if (fields == null || fields.isEmpty()) return "*";
        String[] fieldNames = fields.split(",");
        List<String> valid = new ArrayList<>();
        for (String f : fieldNames) {
            f = f.trim();
            if (f.isEmpty()) continue;
            var fieldDef = def.getField(f);
            if (fieldDef == null) throw new IllegalArgumentException("Unknown field '" + f + "' in collection '" + def.getName() + "'");
            valid.add(dialect.quoteIdentifier(fieldDef.getEffectiveColumnName()));
        }
        return valid.isEmpty() ? "*" : String.join(", ", valid);
    }

    private String buildSortClause(String sort, CollectionDefinition def, SqlDialect dialect) {
        if (sort == null || sort.isEmpty()) return "";
        String[] parts = sort.split(",");
        List<String> sortParts = new ArrayList<>();
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            boolean desc = part.startsWith("-");
            String fieldName = desc ? part.substring(1) : part;
            var fieldDef = def.getField(fieldName);
            if (fieldDef == null) throw new IllegalArgumentException("Unknown sort field '" + fieldName + "'");
            sortParts.add(dialect.quoteIdentifier(fieldDef.getEffectiveColumnName()) + (desc ? " DESC" : " ASC"));
        }
        return String.join(", ", sortParts);
    }

    /**
     * Execute a get query on a SQL collection.
     * Only works when the collection has a primary key configured.
     * @param mergedFilter merged ACL scope filter with primary key condition
     */
    public Map<String, Object> executeGet(CollectionDefinition def, Map<String, Object> mergedFilter) {
        long start = System.currentTimeMillis();
        boolean success = false;
        boolean found = false;
        try {
            if (!def.hasPrimaryKey()) {
                throw new IllegalArgumentException(
                        "SQL collection '" + def.getName() + "' has no primary key; get() is not supported");
            }
            String configuredSql = def.getSql();
            SqlValidator.validate(configuredSql);

            // Hydrate: parse named parameters and bind static values
            SqlParameterMetadata paramMeta = SqlParameterMetadata.from(def);
            HydratedSql hydrated = hydrateSql(configuredSql, paramMeta);

            CompiledFilter compiled = FilterCompiler.compile(mergedFilter, def);
            SqlDialect dialect = dataSourceResolver.resolveDialect(def.getDataSourceKey());
            SqlQueryPlan plan = buildGetPlan(hydrated.sql, compiled, hydrated.paramValues, dialect);

            List<Map<String, Object>> rows;
            try {
                // P0-C: statement-level query timeout -- no shared state modified
                JdbcTemplate jdbc = dataSourceResolver.resolve(def.getDataSourceKey());
                rows = jdbc.query(
                        (Connection con) -> {
                            PreparedStatement ps = con.prepareStatement(plan.getSql());
                            ps.setQueryTimeout(queryTimeoutSeconds);
                            Object[] params = plan.getParametersArray();
                            for (int i = 0; i < params.length; i++) {
                                ps.setObject(i + 1, params[i]);
                            }
                            return ps;
                        },
                        (ResultSetExtractor<List<Map<String, Object>>>) rs -> {
                            List<Map<String, Object>> results = new ArrayList<>();
                            ResultSetMetaData meta = rs.getMetaData();
                            int colCount = meta.getColumnCount();
                            while (rs.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int i = 1; i <= colCount; i++) {
                                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                                }
                                results.add(row);
                            }
                            return results;
                        });
            } catch (Exception e) {
                // DataSourceUnavailableException must surface as 503, not 500.
                if (e instanceof DataSourceUnavailableException) {
                    throw (DataSourceUnavailableException) e;
                }
                log.error("SQL execution error for collection '{}' (get): {} [{}]",
                        def.getName(), SqlErrorSanitizer.sanitizeForLog(e.getMessage()), e.getClass().getSimpleName());
                throw new SqlCollectionExecutionException(
                        SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
            }
            success = true;
            found = !rows.isEmpty();
            return found ? rows.get(0) : null;
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            if (success) {
                log.info("SQL collection operation: collection={} dataSourceKey={} action=get durationMs={} found={} status=success",
                        def.getName(), def.getDataSourceKey(), durationMs, found);
            } else {
                log.warn("SQL collection operation: collection={} dataSourceKey={} action=get durationMs={} status=failure",
                        def.getName(), def.getDataSourceKey(), durationMs);
            }
        }
    }

    /**
     * Build a SqlQueryPlan for a get (single-record) query.
     * Wraps the configured SQL in a subquery, applies the compiled filter as WHERE,
     * and appends pagination via dialect.
     */
    SqlQueryPlan buildGetPlan(String configuredSql, CompiledFilter compiledFilter,
                               List<Object> namedParams, SqlDialect dialect) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM (");
        sql.append(configuredSql);
        sql.append(") _nocobase_sub");

        List<Object> params = new ArrayList<>(namedParams);
        if (!compiledFilter.isEmpty()) {
            sql.append(" WHERE ").append(compiledFilter.getWhereClause());
            params.addAll(compiledFilter.getParameters());
        }
        sql.append(" ").append(dialect.getLimitOffsetClause());
        params.add(1);
        params.add(0);

        return new SqlQueryPlan(sql.toString(), params, null, null);
    }

    /**
     * Validate that the configured SQL's result set columns match the collection's
     * field definitions. Executes the SQL wrapped as a subquery with {@code WHERE 1=0}
     * to read only metadata without returning any rows.
     *
     * <p>Named parameters ({@code :param}) are converted to JDBC {@code ?} placeholders
     * via {@link SqlNamedParameterParser#parse} and bound with type-appropriate dummy
     * values so the database can parse the query without requiring real parameter values.
     *
     * <p>The JdbcTemplate is resolved from the collection's data source key.
     * If the data source is unavailable, this method throws and the caller
     * should treat the collection as invalid.
     *
     * @param def the collection definition
     * @throws IllegalArgumentException if any field references a column not in the SQL result set
     */
    public void validateFields(CollectionDefinition def) {
        if (!def.isSql() || def.getSql() == null || def.getSql().isBlank()) {
            return;
        }

        long start = System.currentTimeMillis();
        boolean success = false;
        try {
            String configuredSql = def.getSql();

            // Parse named parameters to get JDBC-compatible SQL with ? placeholders
            // and ordered parameter names (handles strings, casts, URLs, time literals safely)
            SqlNamedParameterParser.Result parseResult = SqlNamedParameterParser.parse(configuredSql);
            SqlParameterMetadata paramMeta = SqlParameterMetadata.from(def);

            // Build dummy values based on declared parameter types in parser order
            List<Object> dummyParams = new ArrayList<>();
            for (String paramName : parseResult.getParameterNames()) {
                SqlParameterMetadata.ParameterDef paramDef = paramMeta.getParameter(paramName);
                if (paramDef != null) {
                    dummyParams.add(buildDummyValue(paramDef.getType()));
                } else {
                    // Fallback: param in SQL but not in metadata (should not happen -- SqlParameterMetadata.from validates)
                    dummyParams.add(0);
                }
            }

            // Wrap as subquery with WHERE 1=0 to expose only metadata, no rows
            String validationSql = "SELECT * FROM (" + parseResult.getSql() + ") _nocobase_sub WHERE 1=0";

            // P0-C: statement-level query timeout -- no shared state modified
            JdbcTemplate jdbcTemplate = dataSourceResolver.resolve(def.getDataSourceKey());

            Set<String> columnNames;
            try {
                columnNames = jdbcTemplate.query(
                        (Connection con) -> {
                            PreparedStatement ps = con.prepareStatement(validationSql);
                            ps.setQueryTimeout(validationTimeoutSeconds);
                            Object[] params = dummyParams.toArray();
                            for (int i = 0; i < params.length; i++) {
                                ps.setObject(i + 1, params[i]);
                            }
                            return ps;
                        },
                        (ResultSetExtractor<Set<String>>) rs -> {
                            Set<String> names = new HashSet<>();
                            ResultSetMetaData meta = rs.getMetaData();
                            int count = meta.getColumnCount();
                            for (int i = 1; i <= count; i++) {
                                names.add(meta.getColumnName(i));
                            }
                            return names;
                        });
            } catch (Exception e) {
                // P0-B: Only log sanitized metadata -- no SQL, no raw exception
                log.error("Field validation failed for collection '{}' (dataSourceKey='{}'): error category={}, exception class={}",
                        def.getName(), def.getDataSourceKey(),
                        categorizeError(e), e.getClass().getSimpleName());
                throw new IllegalArgumentException(
                        "Failed to validate fields for collection '" + def.getName() + "': "
                                + SqlErrorSanitizer.sanitizeForClient(e.getMessage()), e);
            }

            // Verify every field's effective column name exists in the SQL result set
            for (FieldDefinition field : def.getFields().values()) {
                String effectiveColName = field.getEffectiveColumnName();
                if (effectiveColName != null && !columnNames.contains(effectiveColName)) {
                    log.error("Field mismatch for collection '{}' (dataSourceKey='{}'): field '{}' references column '{}' which does not exist in the query result",
                            def.getName(), def.getDataSourceKey(), field.getName(), effectiveColName);
                    throw new IllegalArgumentException(
                            "SQL collection field metadata does not match query result");
                }
            }
            success = true;
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            if (success) {
                log.info("SQL collection operation: collection={} dataSourceKey={} action=validateFields durationMs={} status=success",
                        def.getName(), def.getDataSourceKey(), durationMs);
            } else {
                log.warn("SQL collection operation: collection={} dataSourceKey={} action=validateFields durationMs={} status=failure",
                        def.getName(), def.getDataSourceKey(), durationMs);
            }
        }
    }

    /**
     * Build a type-appropriate dummy value for parameter binding during field validation.
     * The value is only used so the database can parse the query -- no rows are returned.
     */
    private static Object buildDummyValue(String type) {
        switch (type) {
            case "string":
                return "";
            case "number":
                return 0;
            case "boolean":
                return false;
            case "date":
                return java.sql.Date.valueOf("1970-01-01");
            case "datetime":
                return java.sql.Timestamp.valueOf("1970-01-01 00:00:00");
            default:
                return 0;
        }
    }

    /**
     * Categorize a database error into a sanitized, low-information category for logging.
     * Does NOT expose SQL text, table names, column names, or connection strings.
     */
    private static String categorizeError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return "unknown";
        }
        msg = msg.toLowerCase();
        if (msg.contains("syntax")) return "syntax_error";
        if (msg.contains("table") || msg.contains("not found")) return "schema_error";
        if (msg.contains("column")) return "column_error";
        if (msg.contains("permission") || msg.contains("access")) return "permission_error";
        if (msg.contains("connection") || msg.contains("timeout")) return "connection_error";
        if (msg.contains("parameter") || msg.contains("argument")) return "parameter_error";
        return "execution_error";
    }

    // -- query governance helpers (package-private for testing) --

    /**
     * Normalize a page number: values less than 1 are normalized to 1.
     */
    static int normalizePage(int page) {
        return page < 1 ? 1 : page;
    }

    /**
     * Cap pageSize to the configured maximum.
     * Values <= 0 are normalized to 1.
     * If maxPageSize is invalid (<= 0), falls back to 200.
     */
    public static int capPageSize(int pageSize, int maxPageSize) {
        if (pageSize <= 0) pageSize = 1;
        if (maxPageSize <= 0) maxPageSize = 200;
        return Math.min(pageSize, maxPageSize);
    }

    public static class ListResult {
        private final List<Map<String, Object>> data;
        private final long count;
        private final int page;
        private final int pageSize;

        public ListResult(List<Map<String, Object>> data, long count, int page, int pageSize) {
            this.data = data; this.count = count; this.page = page; this.pageSize = pageSize;
        }
        public List<Map<String, Object>> getData() { return data; }
        public long getCount() { return count; }
        public int getPage() { return page; }
        public int getPageSize() { return pageSize; }
    }
}