package com.nocobase.data;

import com.nocobase.acl.AclFilterInjector;
import com.nocobase.acl.AclService;
import com.nocobase.acl.FieldPermission;
import com.nocobase.acl.FieldPermissionFilter;
import com.nocobase.sql.SqlDataSourceResolver;
import com.nocobase.sql.SqlIdentifier;
import com.nocobase.sql.SqlQueryCollectionExecutor;
import com.nocobase.runtime.CollectionCapability;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.runtime.FieldDefinition;
import com.nocobase.web.ForbiddenException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Metadata-driven dynamic data repository.
 * Enforces capability checks, ACL permissions, and field-level access control.
 * All data access goes through this repository.
 */
@Repository
public class DynamicRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamicRepository.class);

    private static final Set<String> SYSTEM_FIELDS = Set.of("id", "created_at", "updated_at", "created_at_time", "updated_at_time");

    /**
     * Maximum number of items to include in a single SQL $in clause.
     * Larger sets are batched into multiple queries automatically.
     */
    private static final int SAFE_BATCH_SIZE = 1000;

    /**
     * Maximum number of records an internal query (listAllInternal) may return.
     * Prevents unbounded memory usage. Configurable via nocobase.internal.query-max-limit.
     */
    @Value("${nocobase.internal.query-max-limit:10000}")
    private int maxInternalQueryLimit = 10000;

    private final JdbcTemplate jdbcTemplate;
    private final CollectionRuntimeService runtimeService;
    private final AclService aclService;
    private final AclFilterInjector aclFilterInjector;
    private final SqlQueryCollectionExecutor sqlExecutor;
    private final PhysicalSqlBuilder sqlBuilder;

    public DynamicRepository(JdbcTemplate jdbcTemplate,
                             CollectionRuntimeService runtimeService,
                             AclService aclService,
                             AclFilterInjector aclFilterInjector,
                             SqlQueryCollectionExecutor sqlExecutor,
                             SqlDataSourceResolver dataSourceResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeService = runtimeService;
        this.aclService = aclService;
        this.aclFilterInjector = aclFilterInjector;
        this.sqlExecutor = sqlExecutor;
        this.sqlBuilder = new PhysicalSqlBuilder(dataSourceResolver.resolveDialect("main"));
    }

    @PostConstruct
    void validateConfig() {
        if (maxInternalQueryLimit <= 0) {
            throw new IllegalStateException(
                    "nocobase.internal.query-max-limit must be positive, got: " + maxInternalQueryLimit);
        }
    }

    /**
     * List records with filtering, sorting, pagination, and field projection.
     * ACL: checks action permission, injects scope filter, filters readable fields.
     */
    public ListResult list(String collectionName, Map<String, Object> filter,
                           String sort, int page, int pageSize, String fields) {
        CollectionDefinition def = runtimeService.get(collectionName);
        checkCapability(def, "list");
        checkAclAction(collectionName, "list");

        // Delegate to SQL executor for SQL collections
        if (def.isSql()) {
            return executeSqlList(def, collectionName, filter, sort, page, pageSize, fields);
        }

        // Merge ACL scope filter
        filter = aclFilterInjector.mergeScopeFilter(collectionName, "list", filter);

        CompiledFilter compiledFilter = FilterCompiler.compile(filter, def);
        String sortClause = buildSortClause(sort, def);
        String selectClause = buildSelectClause(fields, def);

        SqlPlan plan = sqlBuilder.buildListPlan(def, selectClause, compiledFilter.getWhereClause(),
                compiledFilter.getParameters(), sortClause, page, pageSize);
        Long total = jdbcTemplate.queryForObject(plan.getCountSql(), Long.class, plan.getCountParametersArray());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(plan.getSql(), plan.getParametersArray());

        // Filter readable fields
        FieldPermission readableFields = aclService.getReadableFields(collectionName);
        if (!readableFields.isAll()) {
            rows = rows.stream()
                    .map(row -> FieldPermissionFilter.filter(readableFields, def, row))
                    .collect(Collectors.toList());
        }

        return new ListResult(rows, total, page, pageSize);
    }

    /**
     * Get a single record by primary key.
     * ACL: checks action permission, injects scope filter, filters readable fields.
     */
    public Map<String, Object> get(String collectionName, Object primaryKey) {
        CollectionDefinition def = runtimeService.get(collectionName);
        checkCapability(def, "get");
        checkAclAction(collectionName, "get");

        // SQL collection: delegate to executor
        if (def.isSql()) {
            if (!def.hasPrimaryKey()) {
                throw new IllegalArgumentException(
                    "SQL collection '" + collectionName + "' has no primary key; get() is not supported");
            }
            Map<String, Object> mergedFilter = aclFilterInjector.mergeScopeFilter(collectionName, "get",
                    Map.of(def.getPrimaryKeyFieldName(), Map.of("$eq", primaryKey)));
            Map<String, Object> row = sqlExecutor.executeGet(def, mergedFilter);
            if (row == null) return null;
            return FieldPermissionFilter.filter(aclService.getReadableFields(collectionName), def, row);
        }

        // Merge scope filter with primary key lookup
        String pkField = def.getPrimaryKeyFieldName();
        Map<String, Object> filter = Map.of(pkField, Map.of("$eq", primaryKey));
        filter = aclFilterInjector.mergeScopeFilter(collectionName, "get", filter);
        CompiledFilter compiledFilter = FilterCompiler.compile(filter, def);

        SqlPlan plan = sqlBuilder.buildGetPlan(def, compiledFilter.getWhereClause(), compiledFilter.getParameters());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(plan.getSql(), plan.getParametersArray());
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        FieldPermission readableFields = aclService.getReadableFields(collectionName);
        return FieldPermissionFilter.filter(readableFields, def, row);
    }

    /**
     * Create a new record. Returns the actual inserted record.
     * ACL: checks action permission, validates writable fields.
     */
    public Map<String, Object> create(String collectionName, Map<String, Object> data) {
        CollectionDefinition def = runtimeService.get(collectionName);
        checkCapability(def, "create");
        checkAclAction(collectionName, "create");

        Map<String, String> columnMapping = validateAndMapFields(data, def, true);

        // Check ACL writable fields
        for (String fieldName : columnMapping.keySet()) {
            aclService.checkWritableField(collectionName, "create", fieldName);
        }

        if (columnMapping.isEmpty()) {
            throw new IllegalArgumentException("No valid fields to insert");
        }

        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            params.add(data.get(entry.getKey()));
        }

        SqlPlan plan = sqlBuilder.buildInsertPlan(def, columnMapping, params);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(plan.getSql(), new String[]{def.getPrimaryKeyColumnName()});
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            return ps;
        }, keyHolder);

        Number generatedId = keyHolder.getKey();
        if (generatedId != null) {
            return readAfterWrite(collectionName, generatedId.longValue(), "create");
        }
        return data;
    }

    /**
     * Update a record by primary key. Returns the updated record.
     * ACL: checks action permission, validates writable fields, enforces scope.
     * Scope and id conditions are combined into a single SQL for atomicity.
     */
    public Map<String, Object> update(String collectionName, Object primaryKey, Map<String, Object> data) {
        CollectionDefinition def = runtimeService.get(collectionName);
        checkCapability(def, "update");
        checkAclAction(collectionName, "update");

        Map<String, String> columnMapping = validateAndMapFields(data, def, false);

        for (String fieldName : columnMapping.keySet()) {
            aclService.checkWritableField(collectionName, "update", fieldName);
        }

        if (columnMapping.isEmpty()) {
            throw new IllegalArgumentException("No valid fields to update");
        }

        // Build WHERE clause: scope + id
        StringBuilder whereClause = new StringBuilder();
        List<Object> whereParams = new ArrayList<>();

        Map<String, Object> scopeFilter = aclFilterInjector.mergeScopeFilter(collectionName, "update", null);
        if (scopeFilter != null && !scopeFilter.isEmpty()) {
            CompiledFilter scopeCompiled = FilterCompiler.compile(scopeFilter, def);
            if (!scopeCompiled.isEmpty()) {
                whereClause.append("(").append(scopeCompiled.getWhereClause()).append(") AND ");
                whereParams.addAll(scopeCompiled.getParameters());
            }
        }
        whereClause.append(pk(def)).append(" = ?");
        whereParams.add(primaryKey);

        List<Object> setParams = new ArrayList<>();
        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            setParams.add(data.get(entry.getKey()));
        }

        SqlPlan plan = sqlBuilder.buildUpdatePlan(def, columnMapping, setParams, whereClause.toString(), whereParams);
        int affected = jdbcTemplate.update(plan.getSql(), plan.getParametersArray());

        if (affected == 0) {
            throw new ForbiddenException("Record not found or not in scope");
        }

        return readAfterWrite(collectionName, primaryKey, "update");
    }

    /**
     * Destroy a record by primary key.
     * ACL: checks action permission, enforces scope.
     * Scope and id conditions are combined into a single SQL for atomicity.
     */
    public void destroy(String collectionName, Object primaryKey) {
        CollectionDefinition def = runtimeService.get(collectionName);
        checkCapability(def, "destroy");
        checkAclAction(collectionName, "destroy");

        // Build WHERE clause: scope + id
        StringBuilder whereClause = new StringBuilder();
        List<Object> whereParams = new ArrayList<>();

        Map<String, Object> scopeFilter = aclFilterInjector.mergeScopeFilter(collectionName, "destroy", null);
        if (scopeFilter != null && !scopeFilter.isEmpty()) {
            CompiledFilter scopeCompiled = FilterCompiler.compile(scopeFilter, def);
            if (!scopeCompiled.isEmpty()) {
                whereClause.append("(").append(scopeCompiled.getWhereClause()).append(") AND ");
                whereParams.addAll(scopeCompiled.getParameters());
            }
        }
        whereClause.append(pk(def)).append(" = ?");
        whereParams.add(primaryKey);

        SqlPlan plan = sqlBuilder.buildDeletePlan(def, whereClause.toString(), whereParams);
        int affected = jdbcTemplate.update(plan.getSql(), plan.getParametersArray());

        if (affected == 0) {
            throw new ForbiddenException("Record not found or not in scope");
        }
    }

    // ========================================================================
    // Internal API — for relation/association layer, NOT for public controllers
    // These methods do NOT check public CRUD action permissions.
    // They use action-specific scope and field checks.
    // ========================================================================

    /**
     * Check if a record exists within the scope of a given action.
     * Does NOT check the action permission itself — only scope.
     * Used by association services to verify source/target records are accessible.
     */
    public boolean existsInScope(String collectionName, String action, Object primaryKey) {
        CollectionDefinition def = runtimeService.get(collectionName);

        StringBuilder whereClause = new StringBuilder();
        List<Object> whereParams = new ArrayList<>();

        Map<String, Object> scopeFilter = aclFilterInjector.mergeScopeFilter(collectionName, action, null);
        if (scopeFilter != null && !scopeFilter.isEmpty()) {
            CompiledFilter scopeCompiled = FilterCompiler.compile(scopeFilter, def);
            if (!scopeCompiled.isEmpty()) {
                whereClause.append("(").append(scopeCompiled.getWhereClause()).append(") AND ");
                whereParams.addAll(scopeCompiled.getParameters());
            }
        }
        whereClause.append(pk(def)).append(" = ?");
        whereParams.add(primaryKey);

        SqlPlan plan = sqlBuilder.buildCountPlan(def, whereClause.toString(), whereParams);
        Long count = jdbcTemplate.queryForObject(plan.getSql(), Long.class, plan.getParametersArray());
        return count != null && count > 0;
    }

    /**
     * Find records by filter for a specific action.
     * Applies the action's scope but does NOT check action permission.
     * Used by relation/association services for internal queries.
     */
    public ListResult findByFilterForAction(String collectionName, String action,
                                             Map<String, Object> filter,
                                             String sort, int page, int pageSize, String fields) {
        CollectionDefinition def = runtimeService.get(collectionName);

        // Apply scope for the given action
        filter = aclFilterInjector.mergeScopeFilter(collectionName, action, filter);

        CompiledFilter compiledFilter = FilterCompiler.compile(filter, def);
        String sortClause = buildSortClause(sort, def);
        String selectClause = buildSelectClause(fields, def);

        SqlPlan plan = sqlBuilder.buildListPlan(def, selectClause, compiledFilter.getWhereClause(),
                compiledFilter.getParameters(), sortClause, page, pageSize);
        Long total = jdbcTemplate.queryForObject(plan.getCountSql(), Long.class, plan.getCountParametersArray());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(plan.getSql(), plan.getParametersArray());

        return new ListResult(rows, total, page, pageSize);
    }

    /**
     * Internal method: fetch ALL records matching a filter, looping through pages
     * until all data is retrieved. Does NOT check public action permission.
     * Applies the action's scope filter. Used by relation/association services
     * for internal queries that must not be truncated by maxPageSize governance.
     * <p>
     * Safety: enforces a configurable max limit (default 10000) to prevent
     * unbounded memory usage. Throws an exception if the limit is exceeded.
     */
    public List<Map<String, Object>> listAllInternal(String collectionName, String action,
                                                     Map<String, Object> filter,
                                                     String sort, String fields) {
        CollectionDefinition def = runtimeService.get(collectionName);
        List<Map<String, Object>> allRows = new ArrayList<>();
        int page = 1;
        int pageSize = 500;
        int maxLimit = getMaxInternalQueryLimit();

        while (true) {
            ListResult result;
            if (def.isSql()) {
                result = executeSqlListForAction(def, collectionName, action, filter, sort, page, pageSize, fields);
            } else {
                result = findByFilterForAction(collectionName, action, filter, sort, page, pageSize, fields);
            }

            int currentPageSize = result.getData().size();

            // Check before adding: would total exceed limit?
            if (allRows.size() + currentPageSize > maxLimit) {
                throw new IllegalStateException(
                        "Internal query on '" + collectionName + "' exceeded max limit of " + maxLimit
                                + ". Increase nocobase.internal.query-max-limit if needed.");
            }

            allRows.addAll(result.getData());

            // Stop if we've fetched all records or got an empty page
            if (allRows.size() >= result.getCount() || currentPageSize == 0) {
                break;
            }

            page++;
        }

        return allRows;
    }

    /**
     * Execute a SQL collection list query for a specific action.
     * Applies the action's scope and field-level filtering.
     * Does NOT check action permission.
     */
    private ListResult executeSqlListForAction(CollectionDefinition def, String collectionName, String action,
                                                Map<String, Object> filter, String sort, int page, int pageSize, String fields) {
        filter = aclFilterInjector.mergeScopeFilter(collectionName, action, filter);
        SqlQueryCollectionExecutor.ListResult sqlResult = sqlExecutor.executeList(def, filter, sort, page, pageSize, fields);
        FieldPermission readableFields = aclService.getReadableFields(collectionName);
        List<Map<String, Object>> filteredRows = sqlResult.getData();
        if (!readableFields.isAll()) {
            filteredRows = filteredRows.stream()
                    .map(row -> FieldPermissionFilter.filter(readableFields, def, row))
                    .collect(Collectors.toList());
        }
        return new ListResult(filteredRows, sqlResult.getCount(), sqlResult.getPage(), sqlResult.getPageSize());
    }

    /**
     * Get the configured max internal query limit (for testing).
     */
    public int getMaxInternalQueryLimit() {
        return maxInternalQueryLimit;
    }

    /**
     * Read a record after a write operation (create/update).
     * Uses the write action's scope for the lookup, not the 'get' action.
     * Does NOT require 'get' permission.
     * Returns record with readable fields filtered if available.
     */
    public Map<String, Object> readAfterWrite(String collectionName, Object primaryKey, String writeAction) {
        CollectionDefinition def = runtimeService.get(collectionName);

        StringBuilder whereClause = new StringBuilder();
        List<Object> whereParams = new ArrayList<>();

        // Use the write action's scope
        Map<String, Object> scopeFilter = aclFilterInjector.mergeScopeFilter(collectionName, writeAction, null);
        if (scopeFilter != null && !scopeFilter.isEmpty()) {
            CompiledFilter scopeCompiled = FilterCompiler.compile(scopeFilter, def);
            if (!scopeCompiled.isEmpty()) {
                whereClause.append("(").append(scopeCompiled.getWhereClause()).append(") AND ");
                whereParams.addAll(scopeCompiled.getParameters());
            }
        }
        whereClause.append(pk(def)).append(" = ?");
        whereParams.add(primaryKey);

        SqlPlan plan = sqlBuilder.buildGetPlan(def, whereClause.toString(), whereParams);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(plan.getSql(), plan.getParametersArray());
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        // Filter readable fields for the write action.
        // For write-only users, return only minimal info (id).
        FieldPermission readableFields = aclService.getReadableFields(collectionName);
        if (readableFields.isAll()) {
            return row; // full access
        }
        if (readableFields.isNone()) {
            Map<String, Object> minimal = new LinkedHashMap<>();
            if (def.hasPrimaryKey()) {
                minimal.put(pkField(def), row.get(pkField(def)));
            }
            return minimal;
        }
        // Partial field access
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (readableFields.allows(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        String pkFieldName = pkField(def);
        if (!filtered.containsKey(pkFieldName)) {
            filtered.put(pkFieldName, row.get(pkFieldName));
        }
        return filtered;
    }

    /**
     * Update records matching a filter for a specific action.
     * Used by association services for batch updates (e.g., hasMany set clearing).
     */
    public int updateByFilterForAction(String collectionName, String action,
                                        Map<String, Object> data, Map<String, Object> filter) {
        CollectionDefinition def = runtimeService.get(collectionName);
        Map<String, String> columnMapping = validateAndMapFields(data, def, false);

        if (columnMapping.isEmpty()) {
            throw new IllegalArgumentException("No valid fields to update");
        }

        // Build WHERE with scope
        StringBuilder whereClause = new StringBuilder();
        List<Object> whereParams = new ArrayList<>();

        Map<String, Object> scopeFilter = aclFilterInjector.mergeScopeFilter(collectionName, action, null);
        if (scopeFilter != null && !scopeFilter.isEmpty()) {
            CompiledFilter scopeCompiled = FilterCompiler.compile(scopeFilter, def);
            if (!scopeCompiled.isEmpty()) {
                whereClause.append("(").append(scopeCompiled.getWhereClause()).append(") AND ");
                whereParams.addAll(scopeCompiled.getParameters());
            }
        }

        if (filter != null && !filter.isEmpty()) {
            CompiledFilter userCompiled = FilterCompiler.compile(filter, def);
            if (!userCompiled.isEmpty()) {
                whereClause.append("(").append(userCompiled.getWhereClause()).append(") AND ");
                whereParams.addAll(userCompiled.getParameters());
            }
        }

        if (whereClause.isEmpty()) {
            throw new IllegalArgumentException("updateByFilterForAction requires scope or filter conditions");
        }
        // Remove trailing " AND "
        String where = whereClause.toString();
        if (where.endsWith(" AND ")) {
            where = where.substring(0, where.length() - 5);
        }

        List<Object> setParams = new ArrayList<>();
        for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
            setParams.add(data.get(entry.getKey()));
        }

        SqlPlan plan = sqlBuilder.buildUpdatePlan(def, columnMapping, setParams, where, whereParams);
        return jdbcTemplate.update(plan.getSql(), plan.getParametersArray());
    }

    // ========================================================================
    // Through table internal operations — for belongsToMany association
    // These bypass frontend resource permissions on the through table.
    // Source and target collection permissions must be checked by the caller.
    // ========================================================================

    /**
     * Validate that a column name exists in the through collection's metadata.
     */
    private void validateThroughColumn(String throughCollection, String columnName, String role) {
        if (columnName == null || columnName.isEmpty()) {
            throw new IllegalArgumentException(role + " column name is required for through table operation");
        }
        try {
            SqlIdentifier.validate(columnName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + role + " column name: " + columnName, e);
        }
        CollectionDefinition def = runtimeService.get(throughCollection);
        if (!def.hasField(columnName) && !isSystemColumn(columnName)) {
            throw new IllegalArgumentException(
                "Column '" + columnName + "' not found in through collection '" + throughCollection + "'");
        }
    }

    /**
     * List links from a through table (internal, no through scope/action check).
     * Only returns sourceKey and otherKey columns, not full through table rows.
     * Batches large sourceId sets to avoid overly large SQL $in clauses.
     */
    public ListResult listLinks(String throughCollection, String sourceKey, List<Object> sourceIds,
                                 String otherKey) {
        validateThroughColumn(throughCollection, sourceKey, "sourceKey");
        validateThroughColumn(throughCollection, otherKey, "otherKey");
        CollectionDefinition def = runtimeService.get(throughCollection);

        if (sourceIds == null || sourceIds.isEmpty()) {
            return new ListResult(List.of(), 0, 1, 0);
        }

        List<Map<String, Object>> allRows = new ArrayList<>();
        for (int i = 0; i < sourceIds.size(); i += SAFE_BATCH_SIZE) {
            int end = Math.min(i + SAFE_BATCH_SIZE, sourceIds.size());
            List<Object> batch = sourceIds.subList(i, end);

            String inPlaceholders = batch.stream().map(v -> "?").collect(Collectors.joining(", "));
            String whereClause = quote(sourceKey) + " IN (" + inPlaceholders + ")";
            String selectClause = quote(sourceKey) + ", " + quote(otherKey)
                    + (def.hasPrimaryKey() ? ", " + pk(def) : "");

            SqlPlan plan = sqlBuilder.buildListLinksPlan(def, selectClause, whereClause, batch);
            allRows.addAll(jdbcTemplate.queryForList(plan.getSql(), plan.getParametersArray()));
        }
        return new ListResult(allRows, (long) allRows.size(), 1, allRows.size());
    }

    /**
     * Create a link in a through table.
     * Uses direct SQL — does NOT check through table action permissions.
     * Caller must verify source and target collection permissions.
     */
    public Map<String, Object> createLink(String throughCollection, String sourceKey, Object sourceId,
                                           String otherKey, Object targetId) {
        validateThroughColumn(throughCollection, sourceKey, "sourceKey");
        validateThroughColumn(throughCollection, otherKey, "otherKey");
        CollectionDefinition def = runtimeService.get(throughCollection);
        Map<String, String> columnMapping = new LinkedHashMap<>();
        columnMapping.put(sourceKey, sourceKey);
        columnMapping.put(otherKey, otherKey);
        List<Object> params = List.of(sourceId, targetId);

        SqlPlan plan = sqlBuilder.buildInsertPlan(def, columnMapping, params);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(plan.getSql(), new String[]{def.getPrimaryKeyColumnName()});
            ps.setObject(1, sourceId);
            ps.setObject(2, targetId);
            return ps;
        }, keyHolder);

        Number generatedId = keyHolder.getKey();
        if (generatedId != null) {
            SqlPlan getPlan = sqlBuilder.buildGetPlan(def, pk(def) + " = ?", List.of(generatedId.longValue()));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(getPlan.getSql(), getPlan.getParametersArray());
            return rows.isEmpty() ? null : rows.get(0);
        }
        return null;
    }

    /**
     * Delete a link from a through table.
     * Uses direct SQL — does NOT check through table action permissions.
     * Caller must verify source and target collection permissions.
     */
    public void deleteLink(String throughCollection, String sourceKey, Object sourceId,
                            String otherKey, Object targetId) {
        validateThroughColumn(throughCollection, sourceKey, "sourceKey");
        validateThroughColumn(throughCollection, otherKey, "otherKey");
        CollectionDefinition def = runtimeService.get(throughCollection);
        String whereClause = quote(sourceKey) + " = ? AND " + quote(otherKey) + " = ?";
        SqlPlan plan = sqlBuilder.buildDeletePlan(def, whereClause, List.of(sourceId, targetId));
        jdbcTemplate.update(plan.getSql(), plan.getParametersArray());
    }

    /**
     * Replace all links in a through table.
     * Uses direct SQL — does NOT check through table action permissions.
     * Caller must verify source and target collection permissions.
     */
    public void replaceLinks(String throughCollection, String sourceKey, Object sourceId,
                              String otherKey, List<Object> targetIds) {
        validateThroughColumn(throughCollection, sourceKey, "sourceKey");
        validateThroughColumn(throughCollection, otherKey, "otherKey");
        CollectionDefinition def = runtimeService.get(throughCollection);
        // Remove all existing
        SqlPlan deletePlan = sqlBuilder.buildDeletePlan(def, quote(sourceKey) + " = ?", List.of(sourceId));
        jdbcTemplate.update(deletePlan.getSql(), deletePlan.getParametersArray());

        // Add new ones
        if (targetIds != null && !targetIds.isEmpty()) {
            Map<String, String> columnMapping = new LinkedHashMap<>();
            columnMapping.put(sourceKey, sourceKey);
            columnMapping.put(otherKey, otherKey);
            for (Object targetId : targetIds) {
                SqlPlan insertPlan = sqlBuilder.buildInsertPlan(def, columnMapping, List.of(sourceId, targetId));
                jdbcTemplate.update(insertPlan.getSql(), insertPlan.getParametersArray());
            }
        }
    }

    // --- Capability checks ---

    private void checkCapability(CollectionDefinition def, String action) {
        CollectionCapability cap = CollectionCapability.forType(def.getType());

        switch (action) {
            case "list", "get" -> {
                if (!cap.isReadable()) {
                    throw new ForbiddenException(
                        "Cannot read from " + def.getType() + " collection: " + def.getName());
                }
            }
            case "create", "update", "destroy" -> {
                if (!cap.isWritable()) {
                    throw new ForbiddenException(
                        "Cannot write to " + def.getType() + " collection: " + def.getName());
                }
            }
        }
    }

    // --- ACL checks ---

    private void checkAclAction(String resourceName, String action) {
        if (!aclService.canAction(resourceName, action)) {
            throw new ForbiddenException(
                "No permission to perform '" + action + "' on '" + resourceName + "'");
        }
    }

    // --- Field validation and write rules ---

    private Map<String, String> validateAndMapFields(Map<String, Object> data, CollectionDefinition def, boolean isCreate) {
        Map<String, String> mapping = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String fieldName = entry.getKey();

            if (SYSTEM_FIELDS.contains(fieldName) || SYSTEM_FIELDS.contains(fieldName.toLowerCase())) {
                throw new IllegalArgumentException(
                    "System field '" + fieldName + "' cannot be written directly");
            }

            FieldDefinition fieldDef = def.getField(fieldName);
            if (fieldDef == null) {
                // Allow foreign key columns of belongsTo relations
                if (isForeignKeyColumn(fieldName, def)) {
                    mapping.put(fieldName, fieldName);
                    continue;
                }
                throw new IllegalArgumentException(
                    "Unknown field '" + fieldName + "' in collection '" + def.getName() + "'");
            }

            if (fieldDef.isRelation()) {
                if ("belongsTo".equals(fieldDef.getType())) {
                    var rel = def.getRelation(fieldName);
                    if (rel != null && rel.getForeignKey() != null) {
                        mapping.put(fieldName, rel.getForeignKey());
                        continue;
                    }
                }
                throw new IllegalArgumentException(
                    "Cannot write to relation field '" + fieldName + "' directly");
            }

            if (!fieldDef.isPhysical()) {
                throw new IllegalArgumentException(
                    "Field '" + fieldName + "' is not a physical column");
            }

            mapping.put(fieldName, fieldDef.getEffectiveColumnName());
        }

        return mapping;
    }

    // --- Helper methods ---

    private String quote(String identifier) {
        return SqlIdentifier.quote(identifier);
    }

    private String pk(CollectionDefinition def) {
        return quote(def.getPrimaryKeyColumnName());
    }

    private String pkField(CollectionDefinition def) {
        return def.getPrimaryKeyFieldName();
    }

    private boolean isSystemColumn(String name) {
        return "id".equals(name) || "created_at".equals(name) || "updated_at".equals(name)
                || "created_at_time".equals(name) || "updated_at_time".equals(name);
    }

    private boolean isForeignKeyColumn(String fieldName, CollectionDefinition def) {
        return def.getRelations().stream()
                .anyMatch(r -> fieldName.equals(r.getForeignKey()));
    }

    private ListResult executeSqlList(CollectionDefinition def, String collectionName,
                                       Map<String, Object> filter, String sort, int page, int pageSize, String fields) {
        filter = aclFilterInjector.mergeScopeFilter(collectionName, "list", filter);
        SqlQueryCollectionExecutor.ListResult sqlResult = sqlExecutor.executeList(def, filter, sort, page, pageSize, fields);
        FieldPermission readableFields = aclService.getReadableFields(collectionName);
        List<Map<String, Object>> filteredRows = sqlResult.getData();
        if (!readableFields.isAll()) {
            filteredRows = filteredRows.stream()
                    .map(row -> FieldPermissionFilter.filter(readableFields, def, row))
                    .collect(Collectors.toList());
        }
        return new ListResult(filteredRows, sqlResult.getCount(), sqlResult.getPage(), sqlResult.getPageSize());
    }

    private String buildSelectClause(String fields, CollectionDefinition def) {
        if (fields == null || fields.isEmpty()) {
            return "*";
        }

        String[] fieldNames = fields.split(",");
        List<String> validColumns = new ArrayList<>();
        for (String fieldName : fieldNames) {
            fieldName = fieldName.trim();
            if (fieldName.isEmpty()) continue;
            FieldDefinition fieldDef = def.getField(fieldName);
            if (fieldDef == null && !isSystemColumn(fieldName)) {
                throw new IllegalArgumentException(
                    "Unknown field '" + fieldName + "' in collection '" + def.getName() + "'");
            }
            if (fieldDef != null && !fieldDef.isPhysical()) {
                throw new IllegalArgumentException(
                    "Field '" + fieldName + "' is not a physical column in collection '" + def.getName() + "'");
            }
            String colName = fieldDef != null ? fieldDef.getEffectiveColumnName() : fieldName;
            validColumns.add(quote(colName));
        }

        if (validColumns.isEmpty()) {
            throw new IllegalArgumentException("No valid fields specified for collection '" + def.getName() + "'");
        }
        return String.join(", ", validColumns);
    }

    private String buildSortClause(String sort, CollectionDefinition def) {
        if (sort == null || sort.isEmpty()) {
            return "";
        }

        String[] parts = sort.split(",");
        List<String> sortParts = new ArrayList<>();

        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            boolean desc = part.startsWith("-");
            String fieldName = desc ? part.substring(1) : part;

            FieldDefinition fieldDef = def.getField(fieldName);
            if (fieldDef == null && !isSystemColumn(fieldName)) {
                throw new IllegalArgumentException(
                    "Unknown sort field '" + fieldName + "' in collection '" + def.getName() + "'");
            }
            if (fieldDef != null && !fieldDef.isPhysical()) {
                throw new IllegalArgumentException(
                    "Cannot sort by non-physical field '" + fieldName + "' in collection '" + def.getName() + "'");
            }
            String colName = fieldDef != null ? fieldDef.getEffectiveColumnName() : fieldName;
            sortParts.add(quote(colName) + (desc ? " DESC" : " ASC"));
        }

        return String.join(", ", sortParts);
    }

    public static class ListResult {
        private final List<Map<String, Object>> data;
        private final long count;
        private final int page;
        private final int pageSize;

        public ListResult(List<Map<String, Object>> data, long count, int page, int pageSize) {
            this.data = data;
            this.count = count;
            this.page = page;
            this.pageSize = pageSize;
        }

        public List<Map<String, Object>> getData() { return data; }
        public long getCount() { return count; }
        public int getPage() { return page; }
        public int getPageSize() { return pageSize; }
    }
}