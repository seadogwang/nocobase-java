package com.nocobase.ddl;

import com.nocobase.runtime.IndexDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SchemaPlan — table/column diff and index diff.
 */
class SchemaPlanTest {

    // ========================================================================
    // Basic table/column diff tests
    // ========================================================================

    @Test
    @DisplayName("SchemaPlan: missing table generates MISSING_TABLE")
    void missingTableGeneratesMissingTable() {
        Set<String> currentTables = Set.of();
        Set<String> targetTables = Set.of("test_table");
        Map<String, Set<SchemaPlan.ColumnInfo>> currentColumns = Map.of();
        Map<String, Set<SchemaPlan.ColumnInfo>> targetColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("name", "VARCHAR"))
        );

        List<SchemaPlan> plans = SchemaPlan.generate(
                currentTables, targetTables, currentColumns, targetColumns);

        // generate() reports both MISSING_TABLE and MISSING_COLUMN
        // (it does not skip column checks for missing tables)
        assertEquals(2, plans.size());
        boolean hasMissingTable = plans.stream()
                .anyMatch(p -> p.getAction() == SchemaPlan.Action.MISSING_TABLE);
        boolean hasMissingColumn = plans.stream()
                .anyMatch(p -> p.getAction() == SchemaPlan.Action.MISSING_COLUMN);
        assertTrue(hasMissingTable, "Should report MISSING_TABLE");
        assertTrue(hasMissingColumn, "Should report MISSING_COLUMN for the table");
        // All entries should be non-destructive
        plans.forEach(p -> assertFalse(p.isDestructive()));
    }

    @Test
    @DisplayName("SchemaPlan: missing column generates MISSING_COLUMN")
    void missingColumnGeneratesMissingColumn() {
        Set<String> currentTables = Set.of("test_table");
        Set<String> targetTables = Set.of("test_table");
        Map<String, Set<SchemaPlan.ColumnInfo>> currentColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("id", "BIGINT"))
        );
        Map<String, Set<SchemaPlan.ColumnInfo>> targetColumns = Map.of(
                "test_table", Set.of(
                        new SchemaPlan.ColumnInfo("id", "BIGINT"),
                        new SchemaPlan.ColumnInfo("name", "VARCHAR"))
        );

        List<SchemaPlan> plans = SchemaPlan.generate(
                currentTables, targetTables, currentColumns, targetColumns);

        assertEquals(1, plans.size());
        assertEquals(SchemaPlan.Action.MISSING_COLUMN, plans.get(0).getAction());
        assertEquals("test_table", plans.get(0).getCollectionName());
        assertTrue(plans.get(0).getDetail().contains("name"));
        assertFalse(plans.get(0).isDestructive());
    }

    @Test
    @DisplayName("SchemaPlan: incompatible column type generates INCOMPATIBLE_CHANGE")
    void incompatibleColumnGeneratesIncompatibleChange() {
        Set<String> currentTables = Set.of("test_table");
        Set<String> targetTables = Set.of("test_table");
        Map<String, Set<SchemaPlan.ColumnInfo>> currentColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("age", "VARCHAR"))
        );
        Map<String, Set<SchemaPlan.ColumnInfo>> targetColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("age", "INTEGER"))
        );

        List<SchemaPlan> plans = SchemaPlan.generate(
                currentTables, targetTables, currentColumns, targetColumns);

        assertEquals(1, plans.size());
        assertEquals(SchemaPlan.Action.INCOMPATIBLE_CHANGE, plans.get(0).getAction());
        assertTrue(plans.get(0).isDestructive());
    }

    @Test
    @DisplayName("SchemaPlan: no changes generates NO_OP")
    void noChangesGeneratesNoOp() {
        Set<String> currentTables = Set.of("test_table");
        Set<String> targetTables = Set.of("test_table");
        Map<String, Set<SchemaPlan.ColumnInfo>> currentColumns = Map.of(
                "test_table", Set.of(
                        new SchemaPlan.ColumnInfo("id", "BIGINT"),
                        new SchemaPlan.ColumnInfo("name", "VARCHAR"))
        );
        Map<String, Set<SchemaPlan.ColumnInfo>> targetColumns = Map.of(
                "test_table", Set.of(
                        new SchemaPlan.ColumnInfo("id", "BIGINT"),
                        new SchemaPlan.ColumnInfo("name", "VARCHAR"))
        );

        List<SchemaPlan> plans = SchemaPlan.generate(
                currentTables, targetTables, currentColumns, targetColumns);

        assertEquals(1, plans.size());
        assertTrue(plans.get(0).isNoOp());
    }

    // ========================================================================
    // Index diff tests
    // ========================================================================

    @Test
    @DisplayName("SchemaPlan: missing index generates MISSING_INDEX")
    void missingIndexGeneratesMissingIndex() {
        Set<String> currentIndexNames = Set.of(); // no indexes exist
        List<IndexDefinition> targetIndexes = List.of(
                IndexDefinition.builder()
                        .name("idx_test_email")
                        .tableName("test_table")
                        .addColumnName("email")
                        .unique(false)
                        .collectionName("test_table")
                        .build()
        );

        List<SchemaPlan> plans = SchemaPlan.diffIndexes(
                currentIndexNames, targetIndexes, "test_table");

        assertEquals(1, plans.size());
        assertEquals(SchemaPlan.Action.MISSING_INDEX, plans.get(0).getAction());
        assertEquals("test_table", plans.get(0).getCollectionName());
        assertTrue(plans.get(0).getDetail().contains("idx_test_email"));
        assertFalse(plans.get(0).isDestructive());
    }

    @Test
    @DisplayName("SchemaPlan: existing index not reported as missing")
    void existingIndexNotReported() {
        Set<String> currentIndexNames = Set.of("idx_test_email", "udx_test_code");
        List<IndexDefinition> targetIndexes = List.of(
                IndexDefinition.builder()
                        .name("idx_test_email")
                        .tableName("test_table")
                        .addColumnName("email")
                        .unique(false)
                        .collectionName("test_table")
                        .build(),
                IndexDefinition.builder()
                        .name("udx_test_code")
                        .tableName("test_table")
                        .addColumnName("code")
                        .unique(true)
                        .collectionName("test_table")
                        .build()
        );

        List<SchemaPlan> plans = SchemaPlan.diffIndexes(
                currentIndexNames, targetIndexes, "test_table");

        // Both indexes exist, so no MISSING_INDEX entries
        assertTrue(plans.isEmpty());
    }

    @Test
    @DisplayName("SchemaPlan: only missing indexes reported, existing ones skipped")
    void onlyMissingIndexesReported() {
        Set<String> currentIndexNames = Set.of("idx_test_email"); // only one exists
        List<IndexDefinition> targetIndexes = List.of(
                IndexDefinition.builder()
                        .name("idx_test_email")
                        .tableName("test_table")
                        .addColumnName("email")
                        .unique(false)
                        .collectionName("test_table")
                        .build(),
                IndexDefinition.builder()
                        .name("udx_test_code")
                        .tableName("test_table")
                        .addColumnName("code")
                        .unique(true)
                        .collectionName("test_table")
                        .build(),
                IndexDefinition.builder()
                        .name("idx_test_name")
                        .tableName("test_table")
                        .addColumnName("name")
                        .unique(false)
                        .collectionName("test_table")
                        .build()
        );

        List<SchemaPlan> plans = SchemaPlan.diffIndexes(
                currentIndexNames, targetIndexes, "test_table");

        assertEquals(2, plans.size());
        List<String> missingNames = plans.stream()
                .map(p -> {
                    String detail = p.getDetail();
                    if (detail.contains("udx_test_code")) return "udx_test_code";
                    if (detail.contains("idx_test_name")) return "idx_test_name";
                    return detail;
                })
                .toList();
        assertTrue(missingNames.contains("udx_test_code"));
        assertTrue(missingNames.contains("idx_test_name"));
        // All should be MISSING_INDEX
        plans.forEach(p -> assertEquals(SchemaPlan.Action.MISSING_INDEX, p.getAction()));
    }

    @Test
    @DisplayName("SchemaPlan: null target indexes returns empty list")
    void nullTargetIndexesReturnsEmpty() {
        List<SchemaPlan> plans = SchemaPlan.diffIndexes(
                Set.of(), null, "test_table");
        assertTrue(plans.isEmpty());
    }

    @Test
    @DisplayName("SchemaPlan: empty target indexes returns empty list")
    void emptyTargetIndexesReturnsEmpty() {
        List<SchemaPlan> plans = SchemaPlan.diffIndexes(
                Set.of(), List.of(), "test_table");
        assertTrue(plans.isEmpty());
    }

    @Test
    @DisplayName("SchemaPlan: multi-column index in diff")
    void multiColumnIndexInDiff() {
        Set<String> currentIndexNames = Set.of();
        List<IndexDefinition> targetIndexes = List.of(
                IndexDefinition.builder()
                        .name("idx_multi")
                        .tableName("test_table")
                        .columnNames(List.of("first_name", "last_name"))
                        .unique(false)
                        .collectionName("test_table")
                        .build()
        );

        List<SchemaPlan> plans = SchemaPlan.diffIndexes(
                currentIndexNames, targetIndexes, "test_table");

        assertEquals(1, plans.size());
        assertEquals(SchemaPlan.Action.MISSING_INDEX, plans.get(0).getAction());
        assertTrue(plans.get(0).getDetail().contains("first_name"));
        assertTrue(plans.get(0).getDetail().contains("last_name"));
        assertFalse(plans.get(0).isDestructive());
    }

    @Test
    @DisplayName("SchemaPlan: unique index in diff preserves unique flag")
    void uniqueIndexInDiffPreservesFlag() {
        Set<String> currentIndexNames = Set.of();
        List<IndexDefinition> targetIndexes = List.of(
                IndexDefinition.builder()
                        .name("udx_unique")
                        .tableName("test_table")
                        .addColumnName("username")
                        .unique(true)
                        .collectionName("test_table")
                        .build()
        );

        List<SchemaPlan> plans = SchemaPlan.diffIndexes(
                currentIndexNames, targetIndexes, "test_table");

        assertEquals(1, plans.size());
        assertEquals(SchemaPlan.Action.MISSING_INDEX, plans.get(0).getAction());
        assertTrue(plans.get(0).getDetail().contains("unique: true"));
    }

    // ========================================================================
    // Combined diff tests (tables + columns + indexes)
    // ========================================================================

    @Test
    @DisplayName("SchemaPlan: combined diff with missing table and missing index")
    void combinedDiffMissingTableAndIndex() {
        Set<String> currentTables = Set.of();
        Set<String> targetTables = Set.of("test_table");
        Map<String, Set<SchemaPlan.ColumnInfo>> currentColumns = Map.of();
        Map<String, Set<SchemaPlan.ColumnInfo>> targetColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("name", "VARCHAR"))
        );
        Map<String, Set<String>> currentIndexNames = Map.of();
        Map<String, List<IndexDefinition>> targetIndexes = Map.of(
                "test_table", List.of(
                        IndexDefinition.builder()
                                .name("idx_test_name")
                                .tableName("test_table")
                                .addColumnName("name")
                                .unique(false)
                                .collectionName("test_table")
                                .build()
                )
        );

        List<SchemaPlan> plans = SchemaPlan.diff(
                currentTables, targetTables, currentColumns, targetColumns,
                currentIndexNames, targetIndexes);

        // Should report MISSING_TABLE only (indexes can't be checked if table doesn't exist)
        // Actually, the generate() method reports MISSING_TABLE, and the diff() method
        // also checks indexes. But since the table doesn't exist, the index diff will
        // report MISSING_INDEX as well.
        // Let's verify: generate() returns MISSING_TABLE, then diffIndexes adds MISSING_INDEX
        assertTrue(plans.size() >= 1);
        boolean hasMissingTable = plans.stream()
                .anyMatch(p -> p.getAction() == SchemaPlan.Action.MISSING_TABLE);
        boolean hasMissingIndex = plans.stream()
                .anyMatch(p -> p.getAction() == SchemaPlan.Action.MISSING_INDEX);
        assertTrue(hasMissingTable, "Should report MISSING_TABLE");
        assertTrue(hasMissingIndex, "Should report MISSING_INDEX for the table");
    }

    @Test
    @DisplayName("SchemaPlan: combined diff table exists but missing column and index")
    void combinedDiffTableExistsButMissingColumnAndIndex() {
        Set<String> currentTables = Set.of("test_table");
        Set<String> targetTables = Set.of("test_table");
        Map<String, Set<SchemaPlan.ColumnInfo>> currentColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("id", "BIGINT"))
        );
        Map<String, Set<SchemaPlan.ColumnInfo>> targetColumns = Map.of(
                "test_table", Set.of(
                        new SchemaPlan.ColumnInfo("id", "BIGINT"),
                        new SchemaPlan.ColumnInfo("name", "VARCHAR"))
        );
        Map<String, Set<String>> currentIndexNames = Map.of(
                "test_table", Set.of()
        );
        Map<String, List<IndexDefinition>> targetIndexes = Map.of(
                "test_table", List.of(
                        IndexDefinition.builder()
                                .name("idx_test_name")
                                .tableName("test_table")
                                .addColumnName("name")
                                .unique(false)
                                .collectionName("test_table")
                                .build()
                )
        );

        List<SchemaPlan> plans = SchemaPlan.diff(
                currentTables, targetTables, currentColumns, targetColumns,
                currentIndexNames, targetIndexes);

        assertEquals(2, plans.size());
        boolean hasMissingColumn = plans.stream()
                .anyMatch(p -> p.getAction() == SchemaPlan.Action.MISSING_COLUMN);
        boolean hasMissingIndex = plans.stream()
                .anyMatch(p -> p.getAction() == SchemaPlan.Action.MISSING_INDEX);
        assertTrue(hasMissingColumn, "Should report MISSING_COLUMN");
        assertTrue(hasMissingIndex, "Should report MISSING_INDEX");
    }

    @Test
    @DisplayName("SchemaPlan: combined diff everything matches returns NO_OP")
    void combinedDiffEverythingMatchesReturnsNoOp() {
        Set<String> currentTables = Set.of("test_table");
        Set<String> targetTables = Set.of("test_table");
        Map<String, Set<SchemaPlan.ColumnInfo>> currentColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("id", "BIGINT"))
        );
        Map<String, Set<SchemaPlan.ColumnInfo>> targetColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("id", "BIGINT"))
        );
        Map<String, Set<String>> currentIndexNames = Map.of(
                "test_table", Set.of("idx_test_id")
        );
        Map<String, List<IndexDefinition>> targetIndexes = Map.of(
                "test_table", List.of(
                        IndexDefinition.builder()
                                .name("idx_test_id")
                                .tableName("test_table")
                                .addColumnName("id")
                                .unique(false)
                                .collectionName("test_table")
                                .build()
                )
        );

        List<SchemaPlan> plans = SchemaPlan.diff(
                currentTables, targetTables, currentColumns, targetColumns,
                currentIndexNames, targetIndexes);

        assertEquals(1, plans.size());
        assertTrue(plans.get(0).isNoOp());
    }

    @Test
    @DisplayName("SchemaPlan: combined diff with null index maps works")
    void combinedDiffNullIndexMapsWorks() {
        Set<String> currentTables = Set.of("test_table");
        Set<String> targetTables = Set.of("test_table");
        Map<String, Set<SchemaPlan.ColumnInfo>> currentColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("id", "BIGINT"))
        );
        Map<String, Set<SchemaPlan.ColumnInfo>> targetColumns = Map.of(
                "test_table", Set.of(new SchemaPlan.ColumnInfo("id", "BIGINT"))
        );

        // Null index maps — should still work (no-op)
        List<SchemaPlan> plans = SchemaPlan.diff(
                currentTables, targetTables, currentColumns, targetColumns,
                null, null);

        assertEquals(1, plans.size());
        assertTrue(plans.get(0).isNoOp());
    }

    // ========================================================================
    // Helper tests
    // ========================================================================

    @Test
    @DisplayName("SchemaPlan: ColumnInfo equality and compatibility")
    void columnInfoEqualityAndCompatibility() {
        SchemaPlan.ColumnInfo col1 = new SchemaPlan.ColumnInfo("name", "VARCHAR", false, "'default'");
        SchemaPlan.ColumnInfo col2 = new SchemaPlan.ColumnInfo("name", "VARCHAR");
        SchemaPlan.ColumnInfo col3 = new SchemaPlan.ColumnInfo("name", "INTEGER");

        // Equality by name
        assertEquals(col1, col2);
        assertEquals(col1, col3);

        // Compatibility by type (case-insensitive)
        assertTrue(col1.isCompatibleWith(col2));
        assertTrue(col1.isCompatibleWith(new SchemaPlan.ColumnInfo("name", "varchar")));
        assertFalse(col1.isCompatibleWith(col3));
        assertFalse(col1.isCompatibleWith(null));
    }

    @Test
    @DisplayName("SchemaPlan: toString returns readable format")
    void toStringReturnsReadableFormat() {
        SchemaPlan plan = new SchemaPlan(
                SchemaPlan.Action.MISSING_TABLE, "test", "detail", false);
        String str = plan.toString();
        assertTrue(str.contains("MISSING_TABLE"));
        assertTrue(str.contains("test"));
        assertTrue(str.contains("detail"));
    }
}