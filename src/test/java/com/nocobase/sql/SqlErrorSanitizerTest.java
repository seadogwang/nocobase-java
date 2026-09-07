package com.nocobase.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SqlErrorSanitizer}.
 * Verifies that SQL text is stripped from H2 and PostgreSQL error messages
 * while preserving the meaningful error category/message.
 */
class SqlErrorSanitizerTest {

    // ========== H2 style errors ==========

    @Test
    @DisplayName("H2: strips SQL statement from error message")
    void h2StripsSqlStatement() {
        String raw = "Column \"UNKNOWN\" not found; SQL statement:\n" +
                "SELECT \"id\", \"name\", \"status\" FROM \"users\" [42122-214]";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertEquals("Column \"UNKNOWN\" not found", sanitized,
                "should strip everything after '; SQL statement:'");
        assertFalse(sanitized.contains("SELECT"), "sanitized message must not contain SQL");
        assertFalse(sanitized.contains("users"), "sanitized message must not contain table name from SQL");
        assertFalse(sanitized.contains("[42122-214]"), "sanitized message must not contain H2 error code");
    }

    @Test
    @DisplayName("H2: strips table name from H2 error with SQL text")
    void h2StripsTableNameFromSql() {
        String raw = "Table \"MY_TABLE\" not found; SQL statement:\n" +
                "SELECT * FROM \"MY_TABLE\" [42102-214]";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertEquals("Table \"MY_TABLE\" not found", sanitized,
                "should preserve the error category (Table not found)");
        assertFalse(sanitized.contains("SELECT"), "sanitized message must not contain SQL");
    }

    @Test
    @DisplayName("H2: Spring-wrapped bad SQL grammar is stripped")
    void h2SpringWrappedBadSqlGrammar() {
        String raw = "PreparedStatementCallback; bad SQL grammar [SELECT \"id\", \"name\" FROM \"t\" WHERE \"x\" = ?]; " +
                "nested exception is org.h2.jdbc.JdbcSQLSyntaxErrorException: " +
                "Column \"x\" not found; SQL statement:\n" +
                "SELECT \"id\", \"name\" FROM \"t\" WHERE \"x\" = ? [42122-214]";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertFalse(sanitized.contains("SELECT"), "sanitized message must not contain SQL");
        assertFalse(sanitized.contains("FROM \"t\""), "sanitized message must not contain table names from SQL text");
        assertTrue(sanitized.contains("bad SQL grammar"), "should preserve the error category");
        assertTrue(sanitized.contains("nested exception"), "should preserve nested exception info");
        assertTrue(sanitized.contains("Column"), "should preserve error details from nested exception");
    }

    // ========== PostgreSQL style errors ==========

    @Test
    @DisplayName("PostgreSQL: strips SQL from error message")
    void postgresqlStripsSql() {
        String raw = "PreparedStatementCallback; bad SQL grammar [SELECT \"id\", \"name\" FROM \"users\" WHERE \"x\" = ?]; " +
                "nested exception is org.postgresql.util.PSQLException: ERROR: column \"x\" does not exist";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertFalse(sanitized.contains("SELECT"), "sanitized message must not contain SQL");
        assertFalse(sanitized.contains("\"users\""), "sanitized message must not contain table names from SQL");
        assertTrue(sanitized.contains("column \"x\" does not exist"),
                "should preserve the meaningful error description");
        assertTrue(sanitized.contains("bad SQL grammar"), "should preserve error category");
    }

    @Test
    @DisplayName("PostgreSQL: strips SQL with table and column names")
    void postgresqlStripsTableAndColumnNames() {
        String raw = "PreparedStatementCallback; bad SQL grammar [SELECT * FROM \"orders\" WHERE \"status\" = ?]; " +
                "nested exception is org.postgresql.util.PSQLException: ERROR: relation \"orders\" does not exist";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertFalse(sanitized.contains("SELECT * FROM"), "sanitized message must not contain SQL");
        assertFalse(sanitized.contains("\"orders\" WHERE"), "sanitized message must not contain SQL table names");
        assertTrue(sanitized.contains("relation \"orders\" does not exist"),
                "should preserve the meaningful error description from PostgreSQL");
        assertTrue(sanitized.contains("bad SQL grammar"), "should preserve error category");
    }

    @Test
    @DisplayName("PostgreSQL: strips SQL with parameter placeholders")
    void postgresqlStripsParameterPlaceholders() {
        String raw = "PreparedStatementCallback; bad SQL grammar [SELECT * FROM t WHERE a = ? AND b = ?]; " +
                "nested exception is org.postgresql.util.PSQLException: ERROR: syntax error at or near \"WHERE\"";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertFalse(sanitized.contains("SELECT"), "sanitized message must not contain SQL");
        assertFalse(sanitized.contains("WHERE a = ?"), "sanitized message must not contain SQL with placeholders");
        assertTrue(sanitized.contains("syntax error"), "should preserve the error message from PostgreSQL");
        assertTrue(sanitized.contains("bad SQL grammar"), "should preserve error category");
    }

    // ========== Already-safe messages ==========

    @Test
    @DisplayName("Already-safe message is returned unchanged")
    void alreadySafeMessageReturnedUnchanged() {
        String raw = "Connection refused: connect";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertEquals(raw, sanitized, "already-safe message should be returned unchanged");
    }

    @Test
    @DisplayName("Already-safe message with table name is preserved")
    void alreadySafeMessageWithTableNamePreserved() {
        String raw = "Table 'users' is locked";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertEquals(raw, sanitized, "message without SQL text should be returned unchanged");
    }

    @Test
    @DisplayName("Already-safe message with column name is preserved")
    void alreadySafeMessageWithColumnNamePreserved() {
        String raw = "Column 'name' cannot be null";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertEquals(raw, sanitized, "message without SQL text should be returned unchanged");
        assertTrue(sanitized.contains("name"), "column name should be preserved in safe messages");
    }

    // ========== Null and edge cases ==========

    @Test
    @DisplayName("Null message returns fallback")
    void nullMessageReturnsFallback() {
        String sanitized = SqlErrorSanitizer.sanitize(null);
        assertEquals("Database error", sanitized, "null message should return a fallback");
    }

    @Test
    @DisplayName("Empty message is returned as-is")
    void emptyMessageReturnedAsIs() {
        String sanitized = SqlErrorSanitizer.sanitize("");
        assertEquals("", sanitized, "empty message should be returned as-is");
    }

    @Test
    @DisplayName("H2: strips SQL text with DELETE statement")
    void h2StripsDeleteSql() {
        String raw = "Permission denied; SQL statement:\nDELETE FROM \"users\" WHERE \"id\" = 1 [90000-214]";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertEquals("Permission denied", sanitized);
        assertFalse(sanitized.contains("DELETE"), "sanitized message must not contain SQL");
    }

    @Test
    @DisplayName("Generic: strips bracketed SELECT SQL from message")
    void genericStripsBracketedSelectSql() {
        String raw = "DataIntegrityViolationException: error executing [SELECT * FROM \"users\"]";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertFalse(sanitized.contains("SELECT"), "sanitized message must not contain SQL");
        assertTrue(sanitized.contains("DataIntegrityViolationException"),
                "should preserve the exception type");
    }

    @Test
    @DisplayName("Generic: strips bracketed WITH SQL from message")
    void genericStripsBracketedWithSql() {
        String raw = "Error executing query [WITH cte AS (SELECT 1) SELECT * FROM cte]";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertFalse(sanitized.contains("WITH"), "sanitized message must not contain SQL");
        assertFalse(sanitized.contains("SELECT"), "sanitized message must not contain SQL");
        assertTrue(sanitized.contains("Error executing query"),
                "should preserve the meaningful part of the message");
    }

    // ========== P0-C: JDBC URL/password masking ==========

    @Test
    @DisplayName("JDBC: masks password=... in connection string")
    void jdbcMasksPassword() {
        String raw = "Cannot connect to jdbc:h2:mem:testdb;password=secret123;DB_CLOSE_DELAY=-1";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertFalse(sanitized.contains("secret123"), "password value must be masked");
        assertTrue(sanitized.contains("password=***"), "password must be replaced with mask");
        assertTrue(sanitized.contains("jdbc:h2:mem:testdb"), "non-sensitive parts of URL should be preserved");
    }

    @Test
    @DisplayName("JDBC: masks user=... in connection string")
    void jdbcMasksUser() {
        String raw = "Cannot connect to jdbc:postgresql://localhost/db?user=admin&password=secret";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertFalse(sanitized.contains("admin"), "user value must be masked");
        assertFalse(sanitized.contains("secret"), "password value must be masked");
        assertTrue(sanitized.contains("user=***"), "user must be replaced with mask");
        assertTrue(sanitized.contains("password=***"), "password must be replaced with mask");
    }

    @Test
    @DisplayName("JDBC: masks password with uppercase PASSWORD=...")
    void jdbcMasksPasswordCaseInsensitive() {
        String raw = "Error: jdbc:mysql://host/db?PASSWORD=MySecret&useSSL=false";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertFalse(sanitized.contains("MySecret"), "password value must be masked regardless of case");
        assertTrue(sanitized.contains("password=***") || sanitized.contains("PASSWORD=***"),
                "password must be replaced with mask");
    }

    @Test
    @DisplayName("JDBC: no password in message returns unchanged")
    void jdbcNoPasswordReturnsUnchanged() {
        String raw = "Connection refused: connect to localhost:5432";
        String sanitized = SqlErrorSanitizer.sanitize(raw);
        assertEquals(raw, sanitized, "message without password should be returned unchanged");
    }

    // ========== P0-D: sanitizeForLog ==========

    @Test
    @DisplayName("sanitizeForLog: strips SQL text but keeps category info")
    void sanitizeForLogStripsSqlKeepsCategory() {
        String raw = "PreparedStatementCallback; bad SQL grammar [SELECT * FROM \"t\"]; " +
                "nested exception is org.h2.jdbc.JdbcSQLSyntaxErrorException: " +
                "Column \"x\" not found; SQL statement:\n" +
                "SELECT * FROM \"t\" [42122-214]";
        String sanitized = SqlErrorSanitizer.sanitizeForLog(raw);
        assertFalse(sanitized.contains("SELECT"), "log sanitizer must not contain SQL text");
        assertFalse(sanitized.contains("[42122-214]"), "log sanitizer must not contain H2 error code");
        assertTrue(sanitized.contains("bad SQL grammar"), "log sanitizer should keep error category");
        assertTrue(sanitized.contains("Column \"x\" not found"), "log sanitizer should keep error details");
    }

    @Test
    @DisplayName("sanitizeForLog: masks connection strings")
    void sanitizeForLogMasksConnectionStrings() {
        String raw = "Cannot create PoolableConnectionFactory (jdbc:h2:mem:test;password=secret;user=admin)";
        String sanitized = SqlErrorSanitizer.sanitizeForLog(raw);
        assertFalse(sanitized.contains("secret"), "log sanitizer must mask password");
        assertFalse(sanitized.contains("admin"), "log sanitizer must mask user");
        assertTrue(sanitized.contains("password=***"), "log sanitizer must replace password with mask");
        assertTrue(sanitized.contains("user=***"), "log sanitizer must replace user with mask");
    }

    @Test
    @DisplayName("sanitizeForLog: handles case-insensitive SQL keywords")
    void sanitizeForLogCaseInsensitive() {
        // lowercase "select" in bracketed SQL
        String raw = "Error: bad SQL grammar [select * from \"users\"]";
        String sanitized = SqlErrorSanitizer.sanitizeForLog(raw);
        assertFalse(sanitized.contains("select"), "log sanitizer must strip lowercase SQL");
        assertFalse(sanitized.contains("users"), "log sanitizer must strip table names from SQL");
        assertTrue(sanitized.contains("bad SQL grammar"), "log sanitizer should keep error category");
    }

    @Test
    @DisplayName("sanitizeForLog: strips PostgreSQL Position and Detail")
    void sanitizeForLogStripsPostgresDiagnostics() {
        String raw = "ERROR: division by zero\n  Position: 42\n  Detail: Some detail\n  Where: some function\n  Hint: check your data";
        String sanitized = SqlErrorSanitizer.sanitizeForLog(raw);
        assertFalse(sanitized.contains("Position:"), "log sanitizer must strip Position");
        assertFalse(sanitized.contains("Detail:"), "log sanitizer must strip Detail");
        assertFalse(sanitized.contains("Where:"), "log sanitizer must strip Where");
        assertFalse(sanitized.contains("Hint:"), "log sanitizer must strip Hint");
        assertTrue(sanitized.contains("division by zero"), "log sanitizer should keep the error message");
    }

    @Test
    @DisplayName("sanitizeForLog: strips case-insensitive bracketed INSERT")
    void sanitizeForLogStripsInsertCaseInsensitive() {
        String raw = "Error executing [insert INTO \"users\" VALUES (1)]";
        String sanitized = SqlErrorSanitizer.sanitizeForLog(raw);
        assertFalse(sanitized.contains("insert"), "log sanitizer must strip INSERT SQL");
        assertFalse(sanitized.contains("INTO"), "log sanitizer must strip SQL keywords");
    }

    @Test
    @DisplayName("sanitizeForLog: strips case-insensitive bracketed UPDATE")
    void sanitizeForLogStripsUpdateCaseInsensitive() {
        String raw = "Error executing [UPDATE \"users\" SET name='x']";
        String sanitized = SqlErrorSanitizer.sanitizeForLog(raw);
        assertFalse(sanitized.contains("UPDATE"), "log sanitizer must strip UPDATE SQL");
        assertFalse(sanitized.contains("users"), "log sanitizer must strip table names from SQL");
    }

    @Test
    @DisplayName("sanitizeForLog: strips case-insensitive bracketed DELETE")
    void sanitizeForLogStripsDeleteCaseInsensitive() {
        String raw = "Error executing [delete from \"users\" where id=1]";
        String sanitized = SqlErrorSanitizer.sanitizeForLog(raw);
        assertFalse(sanitized.contains("delete"), "log sanitizer must strip DELETE SQL");
        assertFalse(sanitized.contains("users"), "log sanitizer must strip table names from SQL");
    }

    // ========== P0-D: sanitizeForClient ==========

    @Test
    @DisplayName("sanitizeForClient: returns generic message for H2 error")
    void sanitizeForClientReturnsGenericForH2Error() {
        String raw = "Column \"UNKNOWN\" not found; SQL statement:\n" +
                "SELECT \"id\", \"name\" FROM \"users\" [42122-214]";
        String sanitized = SqlErrorSanitizer.sanitizeForClient(raw);
        assertEquals("Database query failed", sanitized,
                "client sanitizer must return generic message");
        assertFalse(sanitized.contains("UNKNOWN"), "client sanitizer must NOT contain column name");
        assertFalse(sanitized.contains("users"), "client sanitizer must NOT contain table name");
        assertFalse(sanitized.contains("SELECT"), "client sanitizer must NOT contain SQL");
    }

    @Test
    @DisplayName("sanitizeForClient: returns generic message for PostgreSQL error with table name")
    void sanitizeForClientReturnsGenericForPostgresqlError() {
        String raw = "PreparedStatementCallback; bad SQL grammar [SELECT * FROM \"orders\"]; " +
                "nested exception is org.postgresql.util.PSQLException: ERROR: relation \"orders\" does not exist";
        String sanitized = SqlErrorSanitizer.sanitizeForClient(raw);
        assertEquals("Database query failed", sanitized,
                "client sanitizer must return generic message");
        assertFalse(sanitized.contains("orders"), "client sanitizer must NOT contain table name");
        assertFalse(sanitized.contains("relation"), "client sanitizer must NOT contain relation details");
    }

    @Test
    @DisplayName("sanitizeForClient: returns generic message for column not found error")
    void sanitizeForClientReturnsGenericForColumnNotFound() {
        String raw = "PreparedStatementCallback; bad SQL grammar [SELECT \"x\" FROM \"t\"]; " +
                "nested exception is org.h2.jdbc.JdbcSQLSyntaxErrorException: " +
                "Column \"x\" not found; SQL statement:\n" +
                "SELECT \"x\" FROM \"t\" [42122-214]";
        String sanitized = SqlErrorSanitizer.sanitizeForClient(raw);
        assertEquals("Database query failed", sanitized,
                "client sanitizer must return generic message");
        assertFalse(sanitized.contains("Column"), "client sanitizer must NOT contain Column details");
        assertFalse(sanitized.contains("\"x\""), "client sanitizer must NOT contain column name");
        assertFalse(sanitized.contains("\"t\""), "client sanitizer must NOT contain table name");
    }

    @Test
    @DisplayName("sanitizeForClient: returns generic message for table not found error")
    void sanitizeForClientReturnsGenericForTableNotFound() {
        String raw = "Table \"MY_TABLE\" not found; SQL statement:\n" +
                "SELECT * FROM \"MY_TABLE\" [42102-214]";
        String sanitized = SqlErrorSanitizer.sanitizeForClient(raw);
        assertEquals("Database query failed", sanitized,
                "client sanitizer must return generic message");
        assertFalse(sanitized.contains("MY_TABLE"), "client sanitizer must NOT contain table name");
    }

    @Test
    @DisplayName("sanitizeForClient: returns generic message for null")
    void sanitizeForClientNullReturnsGeneric() {
        String sanitized = SqlErrorSanitizer.sanitizeForClient(null);
        assertEquals("Database query failed", sanitized,
                "client sanitizer must return generic message for null");
    }

    @Test
    @DisplayName("sanitizeForClient: returns generic message for connection string error")
    void sanitizeForClientReturnsGenericForConnectionError() {
        String raw = "Cannot create PoolableConnectionFactory (jdbc:h2:mem:test;password=secret;user=admin)";
        String sanitized = SqlErrorSanitizer.sanitizeForClient(raw);
        assertEquals("Database query failed", sanitized,
                "client sanitizer must return generic message");
        assertFalse(sanitized.contains("secret"), "client sanitizer must NOT contain password");
        assertFalse(sanitized.contains("admin"), "client sanitizer must NOT contain user");
    }

    @Test
    @DisplayName("sanitizeForClient: case-insensitive SQL brackets are stripped")
    void sanitizeForClientCaseInsensitive() {
        // If raw were not caught by sanitizeForLog, the client still gets a generic message
        String raw = "Error: bad SQL grammar [with cte as (select 1) select * from cte]";
        String sanitized = SqlErrorSanitizer.sanitizeForClient(raw);
        assertEquals("Database query failed", sanitized,
                "client sanitizer must return generic message");
        assertFalse(sanitized.contains("with"), "client sanitizer must NOT contain SQL");
        assertFalse(sanitized.contains("select"), "client sanitizer must NOT contain SQL");
    }
}