package com.nocobase.sql;

/**
 * Exception thrown when a SQL collection execution fails.
 * The client message is fixed; the sanitized reason is for logging only.
 * No raw stack trace, SQL text, parameter values, or connection details are exposed.
 */
public class SqlCollectionExecutionException extends RuntimeException {

    private static final String CLIENT_MESSAGE = "Error executing SQL collection";

    private final String sanitizedReason;

    public SqlCollectionExecutionException(String sanitizedReason) {
        super(CLIENT_MESSAGE);
        this.sanitizedReason = sanitizedReason;
    }

    public String getClientMessage() {
        return CLIENT_MESSAGE;
    }

    public String getSanitizedReason() {
        return sanitizedReason;
    }
}