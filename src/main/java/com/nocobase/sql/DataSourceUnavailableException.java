package com.nocobase.sql;

/**
 * Exception thrown when a data source is unavailable.
 * Contains the data source key and a sanitized reason -- no raw cause.
 * The client message is generic and does not expose connection details,
 * JDBC URLs, usernames, or passwords.
 */
public class DataSourceUnavailableException extends IllegalStateException {

    private final String dataSourceKey;
    private final String sanitizedReason;

    public DataSourceUnavailableException(String dataSourceKey, String sanitizedReason) {
        super("Data source '" + dataSourceKey + "' is unavailable");
        this.dataSourceKey = dataSourceKey;
        this.sanitizedReason = sanitizedReason;
    }

    public String getDataSourceKey() {
        return dataSourceKey;
    }

    public String getSanitizedReason() {
        return sanitizedReason;
    }
}