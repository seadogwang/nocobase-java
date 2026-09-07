package com.nocobase.sql;

/**
 * H2 database dialect.
 *
 * <p>H2 uses double-quoted identifiers and {@code LIMIT ? OFFSET ?} pagination.
 */
public class H2SqlDialect implements SqlDialect {

    @Override
    public String getLimitOffsetClause() {
        return "LIMIT ? OFFSET ?";
    }
}