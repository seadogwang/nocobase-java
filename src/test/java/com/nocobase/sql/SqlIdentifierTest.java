package com.nocobase.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SqlIdentifier} — unified SQL identifier validation and quoting.
 */
class SqlIdentifierTest {

    // ── validate() ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Simple valid identifier")
    void simpleValidIdentifier() {
        assertEquals("id", SqlIdentifier.validate("id"));
        assertEquals("user_name", SqlIdentifier.validate("user_name"));
        assertEquals("_private", SqlIdentifier.validate("_private"));
        assertEquals("col1", SqlIdentifier.validate("col1"));
        assertEquals("A", SqlIdentifier.validate("A"));
    }

    @Test
    @DisplayName("Complex valid identifier")
    void complexValidIdentifier() {
        assertEquals("user_id_123", SqlIdentifier.validate("user_id_123"));
        assertEquals("UPPER_CASE", SqlIdentifier.validate("UPPER_CASE"));
        assertEquals("mixedCase123", SqlIdentifier.validate("mixedCase123"));
    }

    @Test
    @DisplayName("Null identifier throws")
    void nullIdentifierThrows() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate(null));
    }

    @Test
    @DisplayName("Blank identifier throws")
    void blankIdentifierThrows() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate(""));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("   "));
    }

    @Test
    @DisplayName("Double quotes rejected")
    void doubleQuotesRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("col\"name"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("\"id\""));
    }

    @Test
    @DisplayName("Semicolons rejected")
    void semicolonsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("id;DROP"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("id;"));
    }

    @Test
    @DisplayName("Spaces rejected")
    void spacesRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("col name"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("col\tname"));
    }

    @Test
    @DisplayName("Comment characters rejected")
    void commentCharsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("id--"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("id/*x*/"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("--id"));
    }

    @Test
    @DisplayName("Special characters rejected")
    void specialCharsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("col@name"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("col#name"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("col$name"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("col!name"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("col%name"));
    }

    @Test
    @DisplayName("Starting with digit rejected")
    void startingWithDigitRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("1column"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("0id"));
    }

    @Test
    @DisplayName("Hyphen rejected")
    void hyphenRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("col-name"));
    }

    @Test
    @DisplayName("Dot rejected")
    void dotRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.validate("schema.table"));
    }

    // ── quote() ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Quote valid identifier")
    void quoteValidIdentifier() {
        assertEquals("\"id\"", SqlIdentifier.quote("id"));
        assertEquals("\"user_name\"", SqlIdentifier.quote("user_name"));
        assertEquals("\"col1\"", SqlIdentifier.quote("col1"));
    }

    @Test
    @DisplayName("Quote invalid identifier throws")
    void quoteInvalidIdentifierThrows() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.quote("col name"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.quote("col;DROP"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.quote(""));
    }

    @Test
    @DisplayName("Dialect quoteIdentifier delegates to SqlIdentifier")
    void dialectQuoteIdentifierDelegatesToSqlIdentifier() {
        SqlDialect h2 = new H2SqlDialect();
        SqlDialect pg = new PostgreSqlDialect();

        assertEquals("\"id\"", h2.quoteIdentifier("id"));
        assertEquals("\"user_name\"", pg.quoteIdentifier("user_name"));

        assertThrows(IllegalArgumentException.class, () -> h2.quoteIdentifier("col name"));
        assertThrows(IllegalArgumentException.class, () -> pg.quoteIdentifier("col;DROP"));
    }
}