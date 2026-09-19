package com.nocobase.ddl;

import com.nocobase.runtime.IndexDefinition;

import java.util.*;

/**
 * P1-E2: A plan describing DDL changes between current metadata and target metadata.
 *
 * <p>Represents the difference between two schema states:
 * <ul>
 *   <li>{@link Action#MISSING_TABLE} -- a table exists in target but not in current</li>
 *   <li>{@link Action#MISSING_COLUMN} -- a column exists in target but not in current</li>
 *   <li>{@link Action#MISSING_INDEX} -- an index exists in target but not in current</li>
 *   <li>{@link Action#INCOMPATIBLE_CHANGE} -- a column type differs between current and target</li>
 *   <li>{@link Action#NO_OP} -- no changes needed</li>
 * </ul>
 *
 * <p>This plan is conservative: it identifies changes but does NOT auto-execute
 * destructive changes (dropping columns, dropping tables, changing column types).
 * Destructive changes must be reviewed and approved before execution.
 */
public class SchemaPlan {

    /**
     * The type of schema change action.
     */
    public enum Action {
        /** A table needs to be created. */
        MISSING_TABLE,
        /** A column needs to be added. */
        MISSING_COLUMN,
        /** An index needs to be created. */
        MISSING_INDEX,
        /** A column type or constraint is incompatible. */
        INCOMPATIBLE_CHANGE,
        /** No changes needed. */
        NO_OP
    }

    private final Action action;
    private final String collectionName;
    private final String detail;
    private final boolean destructive;

    public SchemaPlan(Action action, String collectionName, String detail, boolean destructive) {
        this.action = action;
        this.collectionName = collectionName;
        this.detail = detail;
        this.destructive = destructive;
    }

    public Action getAction() { return action; }
    public String getCollectionName() { return collectionName; }
    public String getDetail() { return detail; }
    public boolean isDestructive() { return destructive; }
    public boolean isNoOp() { return action == Action.NO_OP; }

    /**
     * Generate a list of schema plans by comparing current metadata against target metadata.
     *
     * @param currentTables set of table names currently in the database
     * @param targetTables  set of table names expected by metadata
     * @param currentColumns map of table name to set of column definitions
     * @param targetColumns  map of table name to set of expected column definitions
     * @return a list of SchemaPlan entries, one per change
     */
    public static List<SchemaPlan> generate(
            Set<String> currentTables, Set<String> targetTables,
            Map<String, Set<ColumnInfo>> currentColumns,
            Map<String, Set<ColumnInfo>> targetColumns) {

        List<SchemaPlan> plans = new ArrayList<>();

        // Check for missing tables
        for (String targetTable : targetTables) {
            if (!currentTables.contains(targetTable)) {
                plans.add(new SchemaPlan(Action.MISSING_TABLE, targetTable,
                        "Table '" + targetTable + "' does not exist", false));
            }
        }

        // Check for missing columns and incompatible changes
        for (String tableName : targetTables) {
            Set<ColumnInfo> targetCols = targetColumns.getOrDefault(tableName, Set.of());
            Set<ColumnInfo> currentCols = currentColumns.getOrDefault(tableName, Set.of());

            Map<String, ColumnInfo> currentColMap = new HashMap<>();
            for (ColumnInfo col : currentCols) {
                currentColMap.put(col.name, col);
            }

            for (ColumnInfo targetCol : targetCols) {
                ColumnInfo currentCol = currentColMap.get(targetCol.name);
                if (currentCol == null) {
                    plans.add(new SchemaPlan(Action.MISSING_COLUMN, tableName,
                            "Column '" + targetCol.name + "' does not exist in table '" + tableName + "'",
                            false));
                } else if (!currentCol.isCompatibleWith(targetCol)) {
                    plans.add(new SchemaPlan(Action.INCOMPATIBLE_CHANGE, tableName,
                            "Column '" + targetCol.name + "' in table '" + tableName
                            + "' has incompatible type: current=" + currentCol.type
                            + ", target=" + targetCol.type,
                            true));
                }
            }
        }

        if (plans.isEmpty()) {
            plans.add(new SchemaPlan(Action.NO_OP, null, "No schema changes required", false));
        }

        return Collections.unmodifiableList(plans);
    }

    /**
     * Diff current indexes against target index definitions.
     * Generates MISSING_INDEX entries for indexes that are defined in the target
     * but do not exist in the current database.
     *
     * @param currentIndexNames set of index names currently in the database
     * @param targetIndexes     list of target index definitions
     * @param tableName         the table name (for detail messages)
     * @return list of SchemaPlan entries for missing indexes
     */
    public static List<SchemaPlan> diffIndexes(
            Set<String> currentIndexNames,
            List<IndexDefinition> targetIndexes,
            String tableName) {
        List<SchemaPlan> plans = new ArrayList<>();

        if (targetIndexes == null || targetIndexes.isEmpty()) {
            return plans;
        }

        for (IndexDefinition idx : targetIndexes) {
            if (!currentIndexNames.contains(idx.getName())) {
                plans.add(new SchemaPlan(Action.MISSING_INDEX, idx.getCollectionName(),
                        "Index '" + idx.getName() + "' does not exist on table '" + tableName
                        + "' (columns: " + idx.getColumnNames() + ", unique: " + idx.isUnique() + ")",
                        false));
            }
        }

        return Collections.unmodifiableList(plans);
    }

    /**
     * Diff current metadata against target metadata, including tables, columns, and indexes.
     * Combines table/column diff with index diff into a single plan list.
     *
     * @param currentTables       set of table names currently in the database
     * @param targetTables        set of table names expected by metadata
     * @param currentColumns      map of table name to set of column definitions
     * @param targetColumns       map of table name to set of expected column definitions
     * @param currentIndexNames   map of table name to set of index names currently in the database
     * @param targetIndexes       map of table name to list of target index definitions
     * @return a list of SchemaPlan entries, one per change
     */
    public static List<SchemaPlan> diff(
            Set<String> currentTables, Set<String> targetTables,
            Map<String, Set<ColumnInfo>> currentColumns,
            Map<String, Set<ColumnInfo>> targetColumns,
            Map<String, Set<String>> currentIndexNames,
            Map<String, List<IndexDefinition>> targetIndexes) {

        List<SchemaPlan> plans = new ArrayList<>();

        // Table and column diff
        plans.addAll(generate(currentTables, targetTables, currentColumns, targetColumns));

        // Remove the NO_OP sentinel -- we'll add indexes and re-check at the end
        if (plans.size() == 1 && plans.get(0).isNoOp()) {
            plans.clear();
        }

        // Index diff
        if (targetIndexes != null) {
            for (String tableName : targetTables) {
                Set<String> currentIdxNames = currentIndexNames != null
                        ? currentIndexNames.getOrDefault(tableName, Set.of())
                        : Set.of();
                List<IndexDefinition> targetIdxDefs = targetIndexes.get(tableName);
                if (targetIdxDefs != null && !targetIdxDefs.isEmpty()) {
                    plans.addAll(diffIndexes(currentIdxNames, targetIdxDefs, tableName));
                }
            }
        }

        if (plans.isEmpty()) {
            plans.add(new SchemaPlan(Action.NO_OP, null, "No schema changes required", false));
        }

        return Collections.unmodifiableList(plans);
    }

    @Override
    public String toString() {
        return "SchemaPlan{action=" + action + ", collection='" + collectionName
                + "', detail='" + detail + "', destructive=" + destructive + "}";
    }

    /**
     * Represents column metadata for schema comparison.
     */
    public static class ColumnInfo {
        private final String name;
        private final String type;
        private final boolean nullable;
        private final String defaultValue;

        public ColumnInfo(String name, String type, boolean nullable, String defaultValue) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.defaultValue = defaultValue;
        }

        public ColumnInfo(String name, String type) {
            this(name, type, true, null);
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public boolean isNullable() { return nullable; }
        public String getDefaultValue() { return defaultValue; }

        /**
         * Check if this column info is compatible with another.
         * Two columns are compatible if they have the same type name (case-insensitive).
         */
        public boolean isCompatibleWith(ColumnInfo other) {
            if (other == null) return false;
            return this.type != null && this.type.equalsIgnoreCase(other.type);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ColumnInfo that)) return false;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }
}