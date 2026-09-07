package com.nocobase.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit test for SqlNamedParameterParser — no Spring context needed.
 */
class SqlNamedParameterParserTest {

    @Test
    @DisplayName("single param — :name converted to ?")
    void singleParam() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT * FROM users WHERE status = :status");
        assertEquals("SELECT * FROM users WHERE status = ?", result.getSql());
        assertEquals(List.of("status"), result.getParameterNames());
    }

    @Test
    @DisplayName("repeated param — same name appears multiple times")
    void repeatedParam() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT * FROM t WHERE a = :x OR b = :x");
        assertEquals("SELECT * FROM t WHERE a = ? OR b = ?", result.getSql());
        assertEquals(List.of("x", "x"), result.getParameterNames());
    }

    @Test
    @DisplayName("multiple params — different names")
    void multipleParams() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT * FROM users WHERE status = :status AND role = :role AND age > :min_age");
        assertEquals("SELECT * FROM users WHERE status = ? AND role = ? AND age > ?", result.getSql());
        assertEquals(List.of("status", "role", "min_age"), result.getParameterNames());
    }

    @Test
    @DisplayName("string literal with colon — colon inside '...' is skipped")
    void stringLiteralWithColon() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT * FROM t WHERE url = 'http://example.com' AND name = :name");
        assertEquals("SELECT * FROM t WHERE url = 'http://example.com' AND name = ?", result.getSql());
        assertEquals(List.of("name"), result.getParameterNames());
    }

    @Test
    @DisplayName("string literal with colon-like content — ':' inside string ignored")
    void stringLiteralWithColonLike() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT * FROM t WHERE label = 'x:y:z' AND id = :id");
        assertEquals("SELECT * FROM t WHERE label = 'x:y:z' AND id = ?", result.getSql());
        assertEquals(List.of("id"), result.getParameterNames());
    }

    @Test
    @DisplayName("quoted identifier — colon inside \"...\" is skipped")
    void quotedIdentifier() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT :name FROM \"my:table\"");
        assertEquals("SELECT ? FROM \"my:table\"", result.getSql());
        assertEquals(List.of("name"), result.getParameterNames());
    }

    @Test
    @DisplayName("PostgreSQL cast — ::type is not treated as a named param")
    void postgresCast() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT :value::integer, :value::text FROM t");
        assertEquals("SELECT ?::integer, ?::text FROM t", result.getSql());
        assertEquals(List.of("value", "value"), result.getParameterNames());
    }

    @Test
    @DisplayName("URL — http://, jdbc:postgresql:// are not treated as params")
    void urlLiterals() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT * FROM t WHERE url = :url AND conn LIKE 'jdbc:postgresql://host'");
        assertEquals("SELECT * FROM t WHERE url = ? AND conn LIKE 'jdbc:postgresql://host'", result.getSql());
        assertEquals(List.of("url"), result.getParameterNames());
    }

    @Test
    @DisplayName("time literal — 12:00:00 is not treated as a param")
    void timeLiteral() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT * FROM events WHERE start_time = :start AND time_col = '12:00:00'");
        assertEquals("SELECT * FROM events WHERE start_time = ? AND time_col = '12:00:00'", result.getSql());
        assertEquals(List.of("start"), result.getParameterNames());
    }

    @Test
    @DisplayName("time literal outside string — colons between digits are skipped")
    void timeLiteralOutsideString() {
        // Time literal not inside a string: 12:00:00 should not match :00 as a param
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT time '12:00:00'");
        assertEquals("SELECT time '12:00:00'", result.getSql());
        assertTrue(result.getParameterNames().isEmpty());
    }

    @Test
    @DisplayName("empty SQL — returns empty result")
    void emptySql() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse("");
        assertEquals("", result.getSql());
        assertTrue(result.getParameterNames().isEmpty());
    }

    @Test
    @DisplayName("null SQL — returns null")
    void nullSql() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(null);
        assertNull(result.getSql());
        assertTrue(result.getParameterNames().isEmpty());
    }

    @Test
    @DisplayName("no params — SQL unchanged")
    void noParams() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT * FROM users WHERE status = 'active'");
        assertEquals("SELECT * FROM users WHERE status = 'active'", result.getSql());
        assertTrue(result.getParameterNames().isEmpty());
    }

    @Test
    @DisplayName("escaped quote in string — '' inside '...' preserved")
    void escapedQuoteInString() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT * FROM t WHERE name = 'O''Brien' AND status = :status");
        assertEquals("SELECT * FROM t WHERE name = 'O''Brien' AND status = ?", result.getSql());
        assertEquals(List.of("status"), result.getParameterNames());
    }

    @Test
    @DisplayName("escaped quote in identifier — \"\" inside \"...\" preserved")
    void escapedQuoteInIdentifier() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT :val FROM \"my\"\"table\"");
        assertEquals("SELECT ? FROM \"my\"\"table\"", result.getSql());
        assertEquals(List.of("val"), result.getParameterNames());
    }

    // ========== P0-A: Malformed named parameter token rejection ==========

    @Test
    @DisplayName("P0-A: :1bad — starts with digit, rejected")
    void malformedStartsWithDigit() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                SqlNamedParameterParser.parse("SELECT * FROM t WHERE x = :1bad"));
        assertTrue(ex.getMessage().contains("Malformed named parameter token"));
        assertTrue(ex.getMessage().contains(":1bad"));
    }

    @Test
    @DisplayName("P0-A: :bad-name — contains hyphen, rejected")
    void malformedContainsHyphen() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                SqlNamedParameterParser.parse("SELECT * FROM t WHERE x = :bad-name"));
        assertTrue(ex.getMessage().contains("Malformed named parameter token"));
        assertTrue(ex.getMessage().contains(":bad-name"));
    }

    @Test
    @DisplayName("P0-A: dangling colon — colon at end of string, rejected")
    void malformedDanglingColon() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                SqlNamedParameterParser.parse("SELECT * FROM t WHERE x = :"));
        assertTrue(ex.getMessage().contains("Malformed named parameter token"));
        assertTrue(ex.getMessage().contains(":"));
    }

    @Test
    @DisplayName("P0-A: : status — space after colon, rejected")
    void malformedSpaceAfterColon() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                SqlNamedParameterParser.parse("SELECT * FROM t WHERE x = : status"));
        assertTrue(ex.getMessage().contains("Malformed named parameter token"));
        assertTrue(ex.getMessage().contains(":"));
    }

    @Test
    @DisplayName("P0-A: := — assignment operator, rejected")
    void malformedAssignment() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                SqlNamedParameterParser.parse("SELECT * FROM t WHERE x := 1"));
        assertTrue(ex.getMessage().contains("Malformed named parameter token"));
        assertTrue(ex.getMessage().contains(":="));
    }

    @Test
    @DisplayName("P0-A: valid :name and PostgreSQL ::cast both accepted")
    void validNameAndPostgresCast() {
        SqlNamedParameterParser.Result result = SqlNamedParameterParser.parse(
                "SELECT :value::integer FROM t WHERE x = :name");
        assertEquals("SELECT ?::integer FROM t WHERE x = ?", result.getSql());
        assertEquals(List.of("value", "name"), result.getParameterNames());
    }
}