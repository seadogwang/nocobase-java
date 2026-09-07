package com.nocobase.sql;

/**
 * Lightweight SQL dialect abstraction for quoting identifiers and applying
 * database-specific pagination clauses.
 *
 * <p>Each supported database provides its own implementation so that the
 * query executor never hard-codes LIMIT/OFFSET syntax or quote characters.
 */
public interface SqlDialect {

    /**
     * Quote an identifier (column name) according to the dialect rules.
     * Delegates to {@link SqlIdentifier#quote(String)} which validates
     * the identifier against {@code [A-Za-z_][A-Za-z0-9_]*} and wraps
     * it in double-quotes.
     *
     * @param identifier the unquoted identifier
     * @return the quoted identifier
     * @throws IllegalArgumentException if the identifier is invalid
     */
    default String quoteIdentifier(String identifier) {
        return SqlIdentifier.quote(identifier);
    }

    /**
     * Return the pagination clause with JDBC {@code ?} placeholders.
     * The caller is responsible for adding the limit and offset values
     * to the parameter list in order.
     *
     * @return a clause like {@code LIMIT ? OFFSET ?}
     */
    String getLimitOffsetClause();
}