package com.nocobase.data;

import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.FieldDefinition;
import com.nocobase.sql.SqlIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compiles NocoBase filter DSL into parameterized SQL WHERE clauses.
 * All field names are validated against the CollectionDefinition.
 * Unsupported fields or operators throw standard exceptions.
 */
public class FilterCompiler {

    private final CollectionDefinition collectionDef;

    public FilterCompiler(CollectionDefinition collectionDef) {
        this.collectionDef = collectionDef;
    }

    /**
     * Compile a filter map into a parameterized WHERE clause.
     */
    public static CompiledFilter compile(Map<String, Object> filter, CollectionDefinition def) {
        if (filter == null || filter.isEmpty()) {
            return CompiledFilter.empty();
        }
        return new FilterCompiler(def).compileFilter(filter);
    }

    private CompiledFilter compileFilter(Map<String, Object> filter) {
        List<Object> params = new ArrayList<>();
        String clause = buildClause(filter, params);
        return new CompiledFilter(clause, params);
    }

    private String buildClause(Map<String, Object> filter, List<Object> params) {
        List<String> conditions = new ArrayList<>();

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if ("$alwaysFalse".equals(key)) {
                conditions.add("1 = 0");
            } else if (key.startsWith("$")) {
                String logicClause = buildLogicalOperator(key, value, params);
                if (logicClause != null && !logicClause.isEmpty()) {
                    conditions.add(logicClause);
                }
            } else if (value instanceof Map) {
                String fieldClause = buildFieldOperators(key, (Map<String, Object>) value, params);
                if (fieldClause != null && !fieldClause.isEmpty()) {
                    conditions.add(fieldClause);
                }
            } else {
                // Simple equality: resolve field to column name
                String column = resolveColumn(key);
                conditions.add(column + " = ?");
                params.add(value);
            }
        }

        return conditions.isEmpty() ? "" : String.join(" AND ", conditions);
    }

    private String buildLogicalOperator(String key, Object value, List<Object> params) {
        if (!(value instanceof List<?> items)) {
            throw new IllegalArgumentException("Logical operator " + key + " requires an array value");
        }

        return switch (key) {
            case "$and" -> {
                List<String> subClauses = new ArrayList<>();
                for (Object item : items) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        String sub = buildClause((Map<String, Object>) item, params);
                        if (!sub.isEmpty()) subClauses.add("(" + sub + ")");
                    }
                }
                yield subClauses.isEmpty() ? "" : "(" + String.join(" AND ", subClauses) + ")";
            }
            case "$or" -> {
                List<String> subClauses = new ArrayList<>();
                for (Object item : items) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        String sub = buildClause((Map<String, Object>) item, params);
                        if (!sub.isEmpty()) subClauses.add("(" + sub + ")");
                    }
                }
                yield subClauses.isEmpty() ? "" : "(" + String.join(" OR ", subClauses) + ")";
            }
            default -> throw new IllegalArgumentException("Unsupported logical operator: " + key);
        };
    }

    private String buildFieldOperators(String fieldName, Map<String, Object> operators, List<Object> params) {
        // Validate and resolve the field name
        String column = resolveColumn(fieldName);
        List<String> conditions = new ArrayList<>();

        for (Map.Entry<String, Object> op : operators.entrySet()) {
            String operator = op.getKey();
            Object opValue = op.getValue();

            switch (operator) {
                case "$eq" -> { conditions.add(column + " = ?"); params.add(opValue); }
                case "$ne" -> { conditions.add(column + " != ?"); params.add(opValue); }
                case "$gt" -> { conditions.add(column + " > ?"); params.add(opValue); }
                case "$gte" -> { conditions.add(column + " >= ?"); params.add(opValue); }
                case "$lt" -> { conditions.add(column + " < ?"); params.add(opValue); }
                case "$lte" -> { conditions.add(column + " <= ?"); params.add(opValue); }
                case "$in" -> {
                    if (opValue instanceof List<?> list && !list.isEmpty()) {
                        String ph = list.stream().map(v -> "?").collect(Collectors.joining(", "));
                        conditions.add(column + " IN (" + ph + ")");
                        params.addAll(list);
                    }
                }
                case "$notIn" -> {
                    if (opValue instanceof List<?> list && !list.isEmpty()) {
                        String ph = list.stream().map(v -> "?").collect(Collectors.joining(", "));
                        conditions.add(column + " NOT IN (" + ph + ")");
                        params.addAll(list);
                    }
                }
                case "$null" -> conditions.add(column + " IS NULL");
                case "$notNull" -> conditions.add(column + " IS NOT NULL");
                case "$includes" -> {
                    conditions.add(column + " LIKE ?");
                    params.add("%" + opValue + "%");
                }
                default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
            }
        }

        return conditions.isEmpty() ? "" : String.join(" AND ", conditions);
    }

    /**
     * Resolve a field name to its effective column name, validating against metadata.
     */
    private String resolveColumn(String fieldName) {
        // Allow system columns even if not in fields metadata
        if ("id".equals(fieldName) || "created_at".equals(fieldName) || "updated_at".equals(fieldName)
                || "created_at_time".equals(fieldName) || "updated_at_time".equals(fieldName)) {
            return quote(fieldName);
        }

        FieldDefinition fieldDef = collectionDef.getField(fieldName);
        if (fieldDef == null) {
            throw new IllegalArgumentException(
                "Unknown field '" + fieldName + "' in collection '" + collectionDef.getName() + "'");
        }

        if (fieldDef.isRelation()) {
            // For belongsTo, use the foreign key column
            if ("belongsTo".equals(fieldDef.getType())) {
                var rel = collectionDef.getRelation(fieldName);
                if (rel != null && rel.getForeignKey() != null) {
                    return quote(rel.getForeignKey());
                }
            }
            throw new IllegalArgumentException(
                "Filtering on relation field '" + fieldName + "' is not supported. Use the foreign key field instead.");
        }

        return quote(fieldDef.getEffectiveColumnName());
    }

    private static String quote(String identifier) {
        return SqlIdentifier.quote(identifier);
    }
}