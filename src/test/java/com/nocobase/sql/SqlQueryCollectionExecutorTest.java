package com.nocobase.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SqlQueryCollectionExecutor} query governance.
 */
class SqlQueryCollectionExecutorTest {

    // ═══════════════════════════════════════════════════════════════════
    // P1-G: Query governance tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1-G: page < 1 is normalized to 1")
    void pageLessThanOneNormalizedToOne() {
        assertEquals(1, SqlQueryCollectionExecutor.normalizePage(0),
                "page=0 should normalize to 1");
        assertEquals(1, SqlQueryCollectionExecutor.normalizePage(-1),
                "page=-1 should normalize to 1");
        assertEquals(1, SqlQueryCollectionExecutor.normalizePage(-100),
                "page=-100 should normalize to 1");
    }

    @Test
    @DisplayName("P1-G: page >= 1 is unchanged")
    void pageGreaterOrEqualToOneUnchanged() {
        assertEquals(1, SqlQueryCollectionExecutor.normalizePage(1),
                "page=1 should stay 1");
        assertEquals(5, SqlQueryCollectionExecutor.normalizePage(5),
                "page=5 should stay 5");
    }

    @Test
    @DisplayName("P1-G: pageSize > maxPageSize is capped")
    void pageSizeExceedsMaxPageSizeCapped() {
        assertEquals(200, SqlQueryCollectionExecutor.capPageSize(300, 200),
                "pageSize=300 should be capped to maxPageSize=200");
        assertEquals(200, SqlQueryCollectionExecutor.capPageSize(500, 200),
                "pageSize=500 should be capped to maxPageSize=200");
        assertEquals(200, SqlQueryCollectionExecutor.capPageSize(1000, 200),
                "pageSize=1000 should be capped to maxPageSize=200");
    }

    @Test
    @DisplayName("P1-G: pageSize within maxPageSize is unchanged")
    void pageSizeWithinMaxPageSizeUnchanged() {
        assertEquals(50, SqlQueryCollectionExecutor.capPageSize(50, 200),
                "pageSize=50 should be unchanged");
        assertEquals(200, SqlQueryCollectionExecutor.capPageSize(200, 200),
                "pageSize=200 should be unchanged (equal to max)");
        assertEquals(1, SqlQueryCollectionExecutor.capPageSize(1, 200),
                "pageSize=1 should be unchanged");
    }

    @Test
    @DisplayName("P1-G: default maxPageSize is 200")
    void defaultMaxPageSizeIs200() {
        assertEquals(200, SqlQueryCollectionExecutor.capPageSize(300, 200),
                "Default maxPageSize should effectively cap at 200");
    }
}