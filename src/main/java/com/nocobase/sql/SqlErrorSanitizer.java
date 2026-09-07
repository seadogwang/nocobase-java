package com.nocobase.sql;

/**
 * Sanitizes JDBC/database error messages to prevent full SQL text from leaking
 * into client-facing error responses and log messages.
 *
 * <p>Both H2 and PostgreSQL include the SQL statement in their error messages,
 * which this utility strips. The sanitizer handles:
 * <ul>
 *   <li>H2 native format: {@code "error message; SQL statement:\nsql [error-code]"}</li>
 *   <li>H2 Spring-wrapped: {@code "PreparedStatementCallback; bad SQL grammar [SQL] nested exception is ..."}</li>
 *   <li>PostgreSQL native: positional info and SQL text in error details</li>
 *   <li>PostgreSQL Spring-wrapped: {@code "PreparedStatementCallback; ... SQL [...]"}</li>
 *   <li>Generic bracketed SQL: {@code "[SELECT ...]"} appearing anywhere in the message</li>
 *   <li>JDBC connection strings: masks {@code password=...} and {@code user=...}</li>
 * </ul>
 *
 * <p>Already-safe error messages (without SQL text) are returned unchanged.
 * <p>All matching is case-insensitive.
 */
public final class SqlErrorSanitizer {

    private SqlErrorSanitizer() {
        // utility class
    }

    /**
     * Sanitize a JDBC/database error message, removing SQL text.
     * Delegates to {@link #sanitizeForLog(String)}.
     *
     * @param message the raw error message (may be null)
     * @return sanitized message without SQL text
     */
    public static String sanitize(String message) {
        return sanitizeForLog(message);
    }

    /**
     * Sanitize a JDBC/database error message for logging.
     * Strips SQL text, parameter values, and connection strings.
     * Keeps safe category info (e.g., "bad SQL grammar", "syntax error").
     * All matching is case-insensitive.
     *
     * @param message the raw error message (may be null)
     * @return sanitized message safe for log output
     */
    public static String sanitizeForLog(String message) {
        if (message == null) {
            return "Database error";
        }

        String result = message;

        // Mask connection strings (password=..., user=...)
        result = maskConnectionStrings(result);

        // Rule 1: Strip Spring-wrapped "bad SQL grammar [SQL text]" (case-insensitive)
        int badSqlIdx = indexOfIgnoreCase(result, "bad SQL grammar [");
        if (badSqlIdx >= 0) {
            int endIdx = result.indexOf("]", badSqlIdx);
            if (endIdx >= 0) {
                result = (result.substring(0, badSqlIdx + "bad SQL grammar".length())
                        + result.substring(endIdx + 1)).trim();
            }
        }

        // Rule 2: Strip H2 native format "error message; SQL statement:\nsql [error-code]"
        // Everything after "; SQL statement:" is SQL text + error code.
        int sqlStatementIdx = indexOfIgnoreCase(result, "; SQL statement:");
        if (sqlStatementIdx >= 0) {
            result = result.substring(0, sqlStatementIdx).trim();
        }

        // Rule 3: Strip PostgreSQL "Position:", "Detail:", "Where:", "Hint:"
        result = stripPostgresDetails(result);

        // Rule 4: Strip any remaining bracketed SQL fragments (case-insensitive)
        result = stripBracketedSqlCI(result, "[select ");
        result = stripBracketedSqlCI(result, "[with ");
        result = stripBracketedSqlCI(result, "[insert ");
        result = stripBracketedSqlCI(result, "[update ");
        result = stripBracketedSqlCI(result, "[delete ");

        // Rule 5: PostgreSQL / general Spring: "SQL [ ... ]" (case-insensitive)
        int sqlBracketIdx = indexOfIgnoreCase(result, "SQL [");
        if (sqlBracketIdx >= 0) {
            int endIdx = result.indexOf("]", sqlBracketIdx);
            if (endIdx >= 0) {
                result = (result.substring(0, sqlBracketIdx) + result.substring(endIdx + 1)).trim();
            }
        }

        return result;
    }

    /**
     * Sanitize a JDBC/database error message for client-facing responses.
     * Returns only a stable, low-information error message.
     * Must NOT contain table names, column names, or SQL text.
     *
     * @param message the raw error message (may be null)
     * @return a generic, safe error message for the client
     */
    public static String sanitizeForClient(String message) {
        if (message == null) {
            return "Database query failed";
        }
        // Always return a generic message for database errors.
        // The client must never see internal database details such as
        // table names, column names, SQL text, or connection strings.
        return "Database query failed";
    }

    // ========== private helpers ==========

    /**
     * Case-insensitive indexOf. Returns the index in the original string.
     */
    private static int indexOfIgnoreCase(String source, String search) {
        return source.toLowerCase().indexOf(search.toLowerCase());
    }

    /**
     * Mask password=... and user=... patterns in JDBC connection strings.
     * Patterns are matched case-insensitively.
     */
    private static String maskConnectionStrings(String message) {
        String result = message.replaceAll("(?i)password\\s*=\\s*[^;\\s&]+", "password=***");
        result = result.replaceAll("(?i)user\\s*=\\s*[^;\\s&]+", "user=***");
        return result;
    }

    /**
     * Strip PostgreSQL diagnostic fields: Position, Detail, Where, Hint.
     * Matches case-insensitively.
     */
    private static String stripPostgresDetails(String message) {
        String result = message.replaceAll("(?i)\\s*Position:\\s*\\d+", "");
        result = result.replaceAll("(?i)\\s*Detail:[^\\n]*", "");
        result = result.replaceAll("(?i)\\s*Where:[^\\n]*", "");
        result = result.replaceAll("(?i)\\s*Hint:[^\\n]*", "");
        return result.trim();
    }

    /**
     * Strip a bracketed SQL fragment starting with the given prefix (case-insensitive).
     * Example: "some error [SELECT * FROM t] more text" with prefix "[select "
     * becomes "some error  more text".
     */
    private static String stripBracketedSqlCI(String message, String prefix) {
        int idx = indexOfIgnoreCase(message, prefix);
        if (idx >= 0) {
            int endIdx = message.indexOf("]", idx);
            if (endIdx >= 0) {
                return (message.substring(0, idx) + message.substring(endIdx + 1)).trim();
            }
        }
        return message;
    }
}