package com.nocobase.sql;

import java.util.regex.Pattern;

/**
 * Unified SQL identifier validation and quoting.
 *
 * <p>All identifiers used in SQL generation (column names, sort columns,
 * primary key columns, relation foreignKey/sourceKey/targetKey/otherKey)
 * must pass through this validator before being quoted.
 *
 * <p>Valid identifiers match {@code [A-Za-z_][A-Za-z0-9_]*}. Identifiers
 * containing double quotes, semicolons, spaces, comment characters, or
 * any other special characters are rejected.
 */
public final class SqlIdentifier {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private SqlIdentifier() {
        // utility class
    }

    /**
     * Validate that an identifier matches the safe pattern
     * {@code [A-Za-z_][A-Za-z0-9_]*}.
     *
     * <p>Rejects identifiers containing double quotes, semicolons, spaces,
     * comment characters ({@code --}, {@code /*}), or any other special
     * characters.
     *
     * @param identifier the identifier to validate, must not be null or blank
     * @return the validated identifier (unchanged)
     * @throws IllegalArgumentException if the identifier is invalid
     */
    public static String validate(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(
                    "SQL identifier must not be null or blank");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Invalid SQL identifier '" + identifier + "'. "
                            + "Identifier must match pattern: [A-Za-z_][A-Za-z0-9_]*. "
                            + "Double quotes, semicolons, spaces, and comment characters are not allowed.");
        }
        return identifier;
    }

    /**
     * Validate and double-quote an identifier for safe use in SQL.
     *
     * <p>This is the canonical method for SQL generation. All callers that
     * need to embed an identifier in a SQL string should use this method.
     *
     * @param identifier the identifier to validate and quote
     * @return the validated identifier wrapped in double quotes
     * @throws IllegalArgumentException if the identifier is invalid
     */
    public static String quote(String identifier) {
        return "\"" + validate(identifier) + "\"";
    }
}