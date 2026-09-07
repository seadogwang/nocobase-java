package com.nocobase.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SqlValidator} — SQL collection query safety checks.
 */
class SqlValidatorTest {

    // ═══════════════════════════════════════════════════════════════════
    // P1-H: CTE with DML rejection tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1-H: CTE with DELETE is rejected")
    void cteWithDeleteIsRejected() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlValidator.validate("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"),
                "CTE containing DELETE should be rejected");

        assertTrue(ex.getMessage().contains("DELETE"),
                "Error message should mention the forbidden keyword DELETE");
    }

    @Test
    @DisplayName("P1-H: CTE with INSERT is rejected")
    void cteWithInsertIsRejected() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlValidator.validate("WITH x AS (INSERT INTO t VALUES (1) RETURNING *) SELECT * FROM x"),
                "CTE containing INSERT should be rejected");

        assertTrue(ex.getMessage().contains("INSERT"),
                "Error message should mention the forbidden keyword INSERT");
    }

    @Test
    @DisplayName("P1-H: CTE with UPDATE is rejected")
    void cteWithUpdateIsRejected() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SqlValidator.validate("WITH x AS (UPDATE t SET a = 1 RETURNING *) SELECT * FROM x"),
                "CTE containing UPDATE should be rejected");

        assertTrue(ex.getMessage().contains("UPDATE"),
                "Error message should mention the forbidden keyword UPDATE");
    }

    @Test
    @DisplayName("P1-H: valid CTE with SELECT is accepted")
    void validCteWithSelectIsAccepted() {
        assertDoesNotThrow(() ->
                SqlValidator.validate("WITH x AS (SELECT * FROM t) SELECT * FROM x"),
                "CTE containing only SELECT should be accepted");
    }

    @Test
    @DisplayName("P1-H: valid CTE with multiple SELECT subqueries is accepted")
    void validCteWithMultipleSelectsIsAccepted() {
        assertDoesNotThrow(() ->
                SqlValidator.validate("WITH a AS (SELECT * FROM t1), b AS (SELECT * FROM t2) SELECT * FROM a JOIN b ON a.id = b.id"),
                "CTE with multiple SELECT subqueries should be accepted");
    }
}