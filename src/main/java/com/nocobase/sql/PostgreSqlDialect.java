package com.nocobase.sql;

/**
 * PostgreSQL database dialect.
 *
 * <p>PostgreSQL uses double-quoted identifiers and {@code LIMIT ? OFFSET ?}
 * pagination (JDBC normalises the native {@code $N} placeholders to {@code ?}).
 */
public class PostgreSqlDialect implements SqlDialect {

    @Override
    public String getLimitOffsetClause() {
        return "LIMIT ? OFFSET ?";
    }
}