package com.nocobase.ddl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Factory that selects the appropriate DialectAdapter based on the current datasource.
 * Detects H2 vs PostgreSQL at runtime.
 */
@Component
public class DialectAdapterFactory {

    private final DialectAdapter adapter;

    @Autowired
    public DialectAdapterFactory(DataSource dataSource) {
        this.adapter = detectDialect(dataSource);
    }

    /**
     * Returns the appropriate DialectAdapter for the current datasource.
     */
    public DialectAdapter getAdapter() {
        return adapter;
    }

    private DialectAdapter detectDialect(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String driverName = conn.getMetaData().getDriverName();
            String url = conn.getMetaData().getURL();

            if (driverName != null && driverName.toLowerCase().contains("postgresql")) {
                return new PostgresDialectAdapter();
            }
            if (url != null && url.contains(":postgresql:")) {
                return new PostgresDialectAdapter();
            }
        } catch (Exception e) {
            // If detection fails, default to H2 for development safety
        }
        return new H2DialectAdapter();
    }
}