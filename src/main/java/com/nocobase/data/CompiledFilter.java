package com.nocobase.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compiled filter result, containing the WHERE clause and bound parameters.
 * All values are parameter-bound for SQL injection safety.
 */
public class CompiledFilter {

    private final String whereClause;
    private final List<Object> parameters;

    public CompiledFilter(String whereClause, List<Object> parameters) {
        this.whereClause = whereClause;
        this.parameters = parameters;
    }

    public String getWhereClause() { return whereClause; }
    public List<Object> getParameters() { return parameters; }
    public Object[] getParametersArray() { return parameters.toArray(); }
    public boolean isEmpty() { return whereClause == null || whereClause.isEmpty(); }

    public static CompiledFilter empty() {
        return new CompiledFilter("", List.of());
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    /**
     * Compile a NocoBase filter DSL into a parameterized WHERE clause.
     * @deprecated Use {@link FilterCompiler#compile(Map, CollectionDefinition)} instead.
     *             This method does not validate field names against collection metadata
     *             and should only be used in tests.
     */
    @Deprecated
    public static CompiledFilter compile(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return empty();
        }

        List<Object> params = new ArrayList<>();
        String clause = buildClause(filter, params);
        return new CompiledFilter(clause, params);
    }

    private static String buildClause(Map<String, Object> filter, List<Object> params) {
        List<String> conditions = new ArrayList<>();

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (key.startsWith("$")) {
                // Logical operators
                String logicClause = buildLogicalOperator(key, value, params);
                if (logicClause != null && !logicClause.isEmpty()) {
                    conditions.add(logicClause);
                }
            } else if (value instanceof Map) {
                // Field with operators: { "age": { "$gt": 18 } }
                String fieldClause = buildFieldOperators(key, (Map<String, Object>) value, params);
                if (fieldClause != null && !fieldClause.isEmpty()) {
                    conditions.add(fieldClause);
                }
            } else {
                // Simple equality: { "status": "active" }
                conditions.add(quote(key) + " = ?");
                params.add(value);
            }
        }

        return conditions.isEmpty() ? "" : String.join(" AND ", conditions);
    }

    private static String buildLogicalOperator(String key, Object value, List<Object> params) {
        if (!(value instanceof List)) {
            return null;
        }

        List<?> items = (List<?>) value;
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

    private static String buildFieldOperators(String field, Map<String, Object> operators, List<Object> params) {
        List<String> conditions = new ArrayList<>();

        for (Map.Entry<String, Object> op : operators.entrySet()) {
            String operator = op.getKey();
            Object opValue = op.getValue();

            String f = quote(field);

            switch (operator) {
                case "$eq" -> { conditions.add(f + " = ?"); params.add(opValue); }
                case "$ne" -> { conditions.add(f + " != ?"); params.add(opValue); }
                case "$gt" -> { conditions.add(f + " > ?"); params.add(opValue); }
                case "$gte" -> { conditions.add(f + " >= ?"); params.add(opValue); }
                case "$lt" -> { conditions.add(f + " < ?"); params.add(opValue); }
                case "$lte" -> { conditions.add(f + " <= ?"); params.add(opValue); }
                case "$in" -> {
                    if (opValue instanceof List<?> list && !list.isEmpty()) {
                        String ph = list.stream().map(v -> "?").collect(Collectors.joining(", "));
                        conditions.add(f + " IN (" + ph + ")");
                        params.addAll(list);
                    }
                }
                case "$notIn" -> {
                    if (opValue instanceof List<?> list && !list.isEmpty()) {
                        String ph = list.stream().map(v -> "?").collect(Collectors.joining(", "));
                        conditions.add(f + " NOT IN (" + ph + ")");
                        params.addAll(list);
                    }
                }
                case "$null" -> conditions.add(f + " IS NULL");
                case "$notNull" -> conditions.add(f + " IS NOT NULL");
                case "$includes" -> {
                    conditions.add(f + " LIKE ?");
                    params.add("%" + opValue + "%");
                }
                default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
            }
        }

        return conditions.isEmpty() ? "" : String.join(" AND ", conditions);
    }
}