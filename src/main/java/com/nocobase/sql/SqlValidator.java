package com.nocobase.sql;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates SQL queries for SQL collection safety.
 * Blocks DDL/DML, multi-statement, comment injection, and JDBC {@code ?} parameters.
 *
 * <p>All checks use lexical scanning to correctly handle string literals, quoted identifiers,
 * and PostgreSQL {@code ::type} casts — checks only fire on content outside strings/identifiers.
 * Named parameters ({@code :param}) are allowed (Phase 2).</p>
 */
public class SqlValidator {

    private static final Set<String> FORBIDDEN_TOP_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "ALTER", "DROP", "TRUNCATE",
            "CREATE", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "REPLACE"
    );

    private static final Pattern COMMENT_PATTERN = Pattern.compile(
            "--|/\\*", Pattern.MULTILINE);

    /**
     * Validate a configured SQL for SQL collection.
     * <p>All checks (semicolon, comment, {@code ?}, DDL/DML keywords) are performed on a
     * lexically cleaned copy of the SQL where string literals and quoted identifiers are
     * replaced with spaces. This avoids false positives when checked characters happen to
     * appear inside strings or identifiers.</p>
     *
     * @throws IllegalArgumentException if the SQL is unsafe
     */
    public static void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL collection query cannot be empty");
        }

        String trimmed = sql.trim();

        // Must be a SELECT or WITH ... SELECT
        String upperPrefix = trimmed.substring(0, Math.min(20, trimmed.length())).toUpperCase();
        if (!upperPrefix.startsWith("SELECT") && !upperPrefix.startsWith("WITH")) {
            throw new IllegalArgumentException("SQL collection query must start with SELECT or WITH");
        }

        // Build a "clean" version of the SQL with string literals and quoted identifiers
        // replaced by placeholders, so all checks below don't fire on content inside
        // strings or identifiers.
        String cleanSql = stripStringsAndIdentifiers(trimmed);

        // Block any semicolons (multi-statement) — only outside strings/identifiers
        if (cleanSql.contains(";")) {
            throw new IllegalArgumentException("SQL collection query must not contain semicolons");
        }

        // Block comment injection — only outside strings/identifiers
        if (COMMENT_PATTERN.matcher(cleanSql).find()) {
            throw new IllegalArgumentException("SQL collection query must not contain comments");
        }

        // Block JDBC ? parameters — only outside strings/identifiers
        if (cleanSql.contains("?")) {
            throw new IllegalArgumentException("SQL collection query must not contain JDBC '?' parameters");
        }

        // Block DDL/DML keywords (check against cleaned SQL)
        String upperClean = cleanSql.toUpperCase();
        for (String keyword : FORBIDDEN_TOP_KEYWORDS) {
            if (upperClean.matches("(?s).*\\b" + keyword + "\\b.*")) {
                throw new IllegalArgumentException("SQL collection query must not contain " + keyword);
            }
        }

        // Named parameters (:param) are now allowed (Phase 2)
        // PostgreSQL ::type casts are handled by the lexical scanner and not flagged
    }

    /**
     * Strip single-quoted string literals and double-quoted identifiers from the SQL,
     * replacing them with spaces. This prevents keyword detection from firing on content
     * inside strings or identifiers.
     *
     * <p>Also handles:
     * <ul>
     *   <li>Escaped quotes: {@code ''} inside strings, {@code ""} inside identifiers</li>
     *   <li>PostgreSQL {@code ::type} casts — preserved as-is (not stripped)</li>
     * </ul>
     */
    static String stripStringsAndIdentifiers(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int len = sql.length();

        while (i < len) {
            char c = sql.charAt(i);

            if (c == '\'') {
                // Single-quoted string literal — replace with spaces
                i = skipSingleQuotedString(sql, i);
                out.append(' ');
                continue;
            }

            if (c == '"') {
                // Double-quoted identifier — replace with spaces
                i = skipDoubleQuotedIdentifier(sql, i);
                out.append(' ');
                continue;
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }

    /**
     * Skip past a single-quoted string literal starting at the opening quote.
     * Handles escaped quotes ('').
     * @return the index after the closing quote
     */
    private static int skipSingleQuotedString(String sql, int start) {
        int i = start + 1; // skip opening quote
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    // Escaped quote
                    i += 2;
                } else {
                    // Closing quote
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        // Unterminated string — return to end
        return len;
    }

    /**
     * Skip past a double-quoted identifier starting at the opening quote.
     * Handles escaped quotes ("").
     * @return the index after the closing quote
     */
    private static int skipDoubleQuotedIdentifier(String sql, int start) {
        int i = start + 1; // skip opening quote
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '"') {
                if (i + 1 < len && sql.charAt(i + 1) == '"') {
                    // Escaped quote
                    i += 2;
                } else {
                    // Closing quote
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        // Unterminated identifier — return to end
        return len;
    }
}