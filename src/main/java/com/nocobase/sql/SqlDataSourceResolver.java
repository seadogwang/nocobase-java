package com.nocobase.sql;

import com.nocobase.config.NocobaseDataSourceProperties;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@link JdbcTemplate} by data source key.
 *
 * <p>Uses {@link NocobaseDataSourceProperties} to validate data-source keys
 * and enforce the {@code enabled} flag. External data sources are lazily
 * initialized as read-only HikariCP pools and cached for subsequent accesses.
 * External data sources never participate in JPA, Flyway, or DDL operations.
 *
 * <p><b>Failure isolation:</b> When an external data source fails to connect,
 * it is marked as unavailable. However, unavailable data sources can be
 * re-validated via {@link #refreshDataSource(String)} or when a collection
 * referencing them is reloaded. SQL collections referencing unavailable
 * data sources become invalid, but the main system and other data
 * sources are unaffected.
 *
 * <p><b>Lifecycle:</b> External data sources are tracked via {@link DataSourceHolder}
 * and are automatically closed on application shutdown via {@link #destroy()}.
 * The main datasource (managed by Spring Boot) is never closed by this resolver.
 */
@Component
public class SqlDataSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(SqlDataSourceResolver.class);

    private final JdbcTemplate mainJdbcTemplate;
    private final DataSource mainDataSource;
    private final NocobaseDataSourceProperties dataSourceProperties;

    /** Registry of lazily-created external data source holders, keyed by data source key. */
    private final Map<String, DataSourceHolder> registry = new ConcurrentHashMap<>();

    /** Keys of data sources that failed to connect and are currently unavailable. */
    private final Set<String> unavailableDataSources = ConcurrentHashMap.newKeySet();

    /** Per-key locks for concurrency protection during datasource creation. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public SqlDataSourceResolver(JdbcTemplate jdbcTemplate,
                                  NocobaseDataSourceProperties dataSourceProperties) {
        this.mainJdbcTemplate = jdbcTemplate;
        this.mainDataSource = jdbcTemplate.getDataSource();
        this.dataSourceProperties = dataSourceProperties;
    }

    /**
     * Resolve the {@link JdbcTemplate} for the given data source key.
     * Accepts "default" as a deprecated alias for "main".
     *
     * <p>For non-main keys, looks up the configuration in
     * {@link NocobaseDataSourceProperties}, creates a read-only
     * {@link HikariDataSource} on first access, caches the resulting
     * {@link JdbcTemplate} in a {@link DataSourceHolder}, and returns
     * it on subsequent calls.
     *
     * <p>Connection failures during datasource creation go through
     * {@link SqlErrorSanitizer} so no credentials or JDBC URLs leak
     * into client-facing error messages.
     *
     * @param dataSourceKey the data source key (e.g., "main")
     * @return the corresponding JdbcTemplate
     * @throws IllegalStateException        if the key is configured but disabled
     * @throws UnsupportedOperationException if the key is not configured
     * @throws IllegalArgumentException      if the external datasource cannot be created (sanitized)
     */
    public JdbcTemplate resolve(String dataSourceKey) {
        if (dataSourceKey == null || dataSourceKey.isEmpty()
                || "main".equals(dataSourceKey)
                || "default".equals(dataSourceKey)) {
            if ("default".equals(dataSourceKey)) {
                log.warn("dataSourceKey 'default' is deprecated, use 'main' instead");
            }
            return mainJdbcTemplate;
        }

        // Check if already marked as unavailable
        if (unavailableDataSources.contains(dataSourceKey)) {
            throw new DataSourceUnavailableException(dataSourceKey,
                    "It failed to connect on a previous attempt.");
        }

        // Check cache first
        DataSourceHolder holder = registry.get(dataSourceKey);
        if (holder != null) {
            return holder.jdbcTemplate;
        }

        // Look up the config
        NocobaseDataSourceProperties.DataSourceConfig config =
                dataSourceProperties.getDataSource(dataSourceKey);

        if (config == null) {
            throw new UnsupportedOperationException(
                    "Data source '" + dataSourceKey + "' is not configured. "
                            + "Add it under nocobase.data-sources." + dataSourceKey + ".");
        }

        if (!config.isEnabled()) {
            throw new IllegalStateException(
                    "Data source '" + dataSourceKey + "' is disabled (enabled=false). "
                            + "It cannot be used by SQL collections.");
        }

        // Per-key lock: ensure concurrent resolves for the same key
        // create only one connection pool.
        Object lock = locks.computeIfAbsent(dataSourceKey, k -> new Object());
        synchronized (lock) {
            // Double-check cache inside the lock
            holder = registry.get(dataSourceKey);
            if (holder != null) {
                return holder.jdbcTemplate;
            }

            // Create the external JdbcTemplate with connection error sanitization
            DataSourceHolder newHolder = null;
            try {
                newHolder = createExternalDataSourceHolder(dataSourceKey, config);
                registry.put(dataSourceKey, newHolder);
                log.info("Created external data source '{}'", dataSourceKey);
                return newHolder.jdbcTemplate;
            } catch (Exception e) {
                // If the holder was created but something failed after HikariDataSource
                // construction (e.g., the post-creation connection test), close the
                // HikariDataSource to prevent resource leaks.
                if (newHolder != null) {
                    closeDataSourceSafely(newHolder.dataSource, dataSourceKey);
                }
                // Mark as unavailable — do NOT crash the application
                unavailableDataSources.add(dataSourceKey);
                log.error("Failed to create data source '{}': {} [{}]",
                        dataSourceKey, SqlErrorSanitizer.sanitizeForLog(e.getMessage()),
                        e.getClass().getSimpleName());
                throw new DataSourceUnavailableException(dataSourceKey,
                        SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
            }
        }
    }

    /**
     * Returns {@code true} if the named data source is available for use.
     * The main data source is always available. External data sources are
     * available only if they are configured, enabled, have been successfully
     * resolved (present in the registry), and have not failed a previous
     * connection attempt.
     *
     * @param dataSourceKey the data source key
     * @return {@code true} if the data source can be resolved
     */
    public boolean isAvailable(String dataSourceKey) {
        if (dataSourceKey == null || dataSourceKey.isEmpty()
                || "main".equals(dataSourceKey)
                || "default".equals(dataSourceKey)) {
            return true;
        }
        if (unavailableDataSources.contains(dataSourceKey)) {
            return false;
        }
        // External datasource is available only if it has been successfully resolved
        // (i.e., is in the registry with a validated connection).
        if (registry.containsKey(dataSourceKey)) {
            return true;
        }
        // Config exists but hasn't been proven — not available yet
        return false;
    }

    /**
     * Resolve the {@link SqlDialect} for the given data source key.
     *
     * <p>For the main data source, the dialect is auto-detected from the
     * JDBC URL. If undetectable, defaults to H2 with a warning.
     *
     * <p>For external data sources, the configured {@code dialect} property
     * is used. If no dialect is configured, it is derived from the JDBC URL.
     * If neither can determine the dialect, a clear configuration error is
     * thrown — external data sources never silently fall back to H2.
     *
     * <p>Only {@code h2} and {@code postgresql} dialects are supported.
     * Unsupported dialects (e.g. {@code mysql}) are rejected with a clear
     * error message.
     *
     * @param dataSourceKey the data source key
     * @return the appropriate SqlDialect, never null
     * @throws IllegalArgumentException if an external data source's dialect
     *         cannot be determined or is unsupported
     */
    public SqlDialect resolveDialect(String dataSourceKey) {
        boolean isMain = dataSourceKey == null || dataSourceKey.isEmpty()
                || "main".equals(dataSourceKey)
                || "default".equals(dataSourceKey);

        NocobaseDataSourceProperties.DataSourceConfig config;
        if (isMain) {
            config = dataSourceProperties.getMain();
        } else {
            config = dataSourceProperties.getDataSource(dataSourceKey);
        }
        return detectDialect(config, isMain, dataSourceKey);
    }

    /**
     * Clear the unavailable data sources set and external template cache.
     * Closes all cached external data sources before clearing the registry.
     * (Primarily for testing.)
     */
    public void clearUnavailable() {
        unavailableDataSources.clear();
        closeAllExternalDataSources();
        registry.clear();
    }

    /**
     * Invalidate the resolver cache for a specific data source key.
     * Closes the cached connection pool (if any), removes the entry from
     * the registry, and clears the unavailable flag.
     *
     * <p>This is called when a data source is updated, deleted, or
     * disabled via the {@code DataSourceConfigService}. The next call to
     * {@link #resolve(String)} will re-create the connection from the
     * latest configuration.
     *
     * <p>The main data source is never invalidated.
     *
     * @param dataSourceKey the data source key to invalidate
     */
    public void invalidateCache(String dataSourceKey) {
        if (dataSourceKey == null || dataSourceKey.isEmpty()
                || "main".equals(dataSourceKey)
                || "default".equals(dataSourceKey)) {
            return; // main is never invalidated
        }

        // Close the cached connection pool
        DataSourceHolder old = registry.remove(dataSourceKey);
        if (old != null) {
            closeDataSourceSafely(old.dataSource, dataSourceKey);
        }

        // Clear the unavailable flag so re-resolution is possible
        unavailableDataSources.remove(dataSourceKey);

        // Remove the per-key lock so a fresh lock is created on next resolve
        locks.remove(dataSourceKey);

        log.info("Invalidated resolver cache for data source '{}'", dataSourceKey);
    }

    /**
     * Refresh an external data source: close the old connection, remove from
     * the unavailable set, and attempt to re-create and re-validate the connection.
     *
     * <p>The main data source is always considered refreshed successfully.
     * For external data sources, this method:
     * <ol>
     *   <li>Closes the old external datasource if cached in the registry</li>
     *   <li>Removes the data source key from the unavailable set</li>
     *   <li>Re-creates and re-validates the connection</li>
     *   <li>On success: clears unavailable, returns true</li>
     *   <li>On failure: re-marks unavailable, returns false</li>
     * </ol>
     *
     * @param dataSourceKey the data source key to refresh
     * @return {@code true} if the data source is now available, {@code false} otherwise
     */
    public boolean refreshDataSource(String dataSourceKey) {
        if (dataSourceKey == null || dataSourceKey.isEmpty()
                || "main".equals(dataSourceKey)
                || "default".equals(dataSourceKey)) {
            return true; // main is always available
        }

        // Close old external datasource if cached
        DataSourceHolder old = registry.remove(dataSourceKey);
        if (old != null) {
            closeDataSourceSafely(old.dataSource, dataSourceKey);
        }

        // Remove from unavailable set — allow re-validation
        unavailableDataSources.remove(dataSourceKey);

        // Re-create and re-validate
        try {
            resolve(dataSourceKey);
            log.info("Successfully refreshed data source '{}'", dataSourceKey);
            return true;
        } catch (Exception e) {
            log.warn("Failed to refresh data source '{}': {}",
                    dataSourceKey, SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
            // resolve() already re-added to unavailable set on failure
            return false;
        }
    }

    /**
     * Spring lifecycle callback. Closes all external HikariDataSources
     * on application shutdown. The main datasource (managed by Spring Boot)
     * is never closed by this method.
     */
    @PreDestroy
    public void destroy() {
        log.info("Shutting down external data sources ({} active)", registry.size());
        closeAllExternalDataSources();
        registry.clear();
    }

    // ── private helpers ──────────────────────────────────────────────────

    private SqlDialect detectDialect(NocobaseDataSourceProperties.DataSourceConfig config,
                                      boolean isMain, String dataSourceKey) {
        if (config == null) {
            if (isMain) {
                log.warn("Main data source config is null, defaulting to H2 dialect");
                return new H2SqlDialect();
            }
            throw new IllegalArgumentException(
                    "Data source '" + dataSourceKey + "' is not configured. "
                            + "Add it under nocobase.data-sources." + dataSourceKey + ".");
        }

        // Check explicitly configured dialect
        String dialect = config.getDialect();
        if (dialect != null && !dialect.isEmpty()) {
            if ("postgresql".equalsIgnoreCase(dialect)) {
                return new PostgreSqlDialect();
            }
            if ("h2".equalsIgnoreCase(dialect)) {
                return new H2SqlDialect();
            }
            // Unknown dialect
            if (isMain) {
                log.warn("Unknown dialect '{}' configured for main data source, falling back to H2", dialect);
                return new H2SqlDialect();
            }
            throw new IllegalArgumentException(
                    "Data source '" + dataSourceKey + "' has unsupported dialect '" + dialect
                            + "'. Supported dialects: 'h2', 'postgresql'.");
        }

        // Auto-detect from JDBC URL
        String url = config.getUrl();
        if (url != null) {
            if (url.contains(":postgresql:")) {
                return new PostgreSqlDialect();
            }
            if (url.contains(":h2")) {
                return new H2SqlDialect();
            }
        }

        // Cannot derive dialect
        if (isMain) {
            log.warn("Could not detect dialect for main data source from URL '{}', defaulting to H2", url);
            return new H2SqlDialect();
        }
        throw new IllegalArgumentException(
                "Data source '" + dataSourceKey + "' has no dialect configured and the dialect "
                        + "could not be derived from the JDBC URL. "
                        + "Please configure the 'dialect' property (supported: 'h2', 'postgresql').");
    }

    /**
     * Create a DataSourceHolder wrapping a read-only HikariDataSource for an
     * external data source. External data sources use a minimal connection
     * pool and do NOT participate in JPA, Flyway, or DDL operations.
     *
     * <p><b>Preflight check:</b> Before creating the HikariDataSource, a
     * lightweight connection test is performed using {@link DriverManager}.
     * This ensures that Hikari never logs raw "Exception during pool
     * initialization" stack traces — the preflight catches connection
     * failures before Hikari is ever involved.
     *
     * @param key    the data source key (for pool naming)
     * @param config the data source configuration
     * @return a new DataSourceHolder containing the JdbcTemplate and DataSource
     * @throws DataSourceUnavailableException if the preflight connection fails
     */
    private DataSourceHolder createExternalDataSourceHolder(String key,
                                                             NocobaseDataSourceProperties.DataSourceConfig config) {
        // Step 1: Preflight — use DriverManager to test connectivity
        // BEFORE creating any HikariDataSource. This prevents Hikari
        // from logging raw "Exception during pool initialization" with
        // JDBC URL, host, port, and full stack trace.
        preflightConnection(key, config);

        // Step 2: Create HikariDataSource (only after preflight succeeds)
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(config.getUrl());
        if (config.getDriverClassName() != null && !config.getDriverClassName().isEmpty()) {
            dataSource.setDriverClassName(config.getDriverClassName());
        }
        if (config.getUsername() != null) {
            dataSource.setUsername(config.getUsername());
        }
        if (config.getPassword() != null) {
            dataSource.setPassword(config.getPassword());
        }

        // External data sources are always read-only — no writes allowed
        dataSource.setReadOnly(true);

        // Pool configuration for external read-only data sources
        dataSource.setPoolName("nocobase-ext-" + key);
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(10000);   // 10 s
        dataSource.setIdleTimeout(300000);        // 5 min
        dataSource.setMaxLifetime(600000);        // 10 min

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // Step 3: Quick connection test from the Hikari pool to verify
        // the pool works. If this fails, the caller will close the
        // HikariDataSource to prevent resource leaks.
        try (Connection conn = dataSource.getConnection()) {
            // Connection opened successfully — close immediately
        } catch (SQLException e) {
            throw new DataSourceUnavailableException(key,
                    SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
        }

        return new DataSourceHolder(jdbcTemplate, dataSource);
    }

    /**
     * Preflight connection check using {@link DriverManager} directly.
     * This runs BEFORE any HikariDataSource is created, so failed
     * datasource initialization never triggers Hikari pool creation
     * and Hikari never logs raw error stack traces.
     *
     * <p>The preflight also verifies read-only mode support (replaces the
     * old {@code verifyReadOnlyMode} method) and validates the connection
     * (replaces the old {@code validateConnection} method). The connection
     * is opened only once.
     *
     * @param key    the data source key (for log/error messages)
     * @param config the data source configuration
     * @throws DataSourceUnavailableException if the connection cannot be established
     */
    private void preflightConnection(String key,
                                     NocobaseDataSourceProperties.DataSourceConfig config) {
        String url = config.getUrl();
        String username = config.getUsername();
        String password = config.getPassword();
        String driverClassName = config.getDriverClassName();

        // Load the JDBC driver class if needed
        if (driverClassName != null && !driverClassName.isEmpty()) {
            try {
                Class.forName(driverClassName);
            } catch (ClassNotFoundException e) {
                throw new DataSourceUnavailableException(key,
                        "JDBC driver class not found.");
            }
        }

        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            // Verify read-only mode is supported by the JDBC driver
            if (!conn.isReadOnly()) {
                log.warn("External data source '{}' driver does not support read-only mode. "
                        + "The data source will be treated as read-only at the application level "
                        + "(SQL collections are always read-only regardless of the driver).", key);
            }
            // Connection opened and verified — close immediately
        } catch (SQLException e) {
            // Sanitize the error message BEFORE throwing — DriverManager
            // exceptions may contain JDBC URL, host, port, etc.
            throw new DataSourceUnavailableException(key,
                    SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
        }
    }

    /**
     * Close all external data sources held in the registry.
     * Skips the main datasource (managed by Spring Boot) to prevent
     * interfering with Spring's own lifecycle.
     */
    private void closeAllExternalDataSources() {
        for (Map.Entry<String, DataSourceHolder> entry : registry.entrySet()) {
            DataSource ds = entry.getValue().dataSource;
            if (ds == mainDataSource) {
                continue; // never close the main datasource
            }
            if (ds instanceof HikariDataSource hds) {
                if (!hds.isClosed()) {
                    hds.close();
                    log.info("Closed external data source '{}'", entry.getKey());
                }
            }
        }
    }

    // ── inner types ──────────────────────────────────────────────────────

    /**
     * Close a data source safely, logging any errors.
     */
    private void closeDataSourceSafely(DataSource ds, String dataSourceKey) {
        if (ds == null) {
            return;
        }
        if (ds == mainDataSource) {
            return; // never close the main datasource
        }
        try {
            if (ds instanceof HikariDataSource hds) {
                if (!hds.isClosed()) {
                    hds.close();
                    log.info("Closed external data source '{}'", dataSourceKey);
                }
            }
        } catch (Exception e) {
            log.warn("Error closing data source '{}': {}",
                    dataSourceKey, SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
        }
    }

    /**
     * Holder that caches both the {@link JdbcTemplate} and the underlying
     * {@link DataSource} for an external data source. This allows the resolver
     * to close the connection pool on shutdown or reset.
     */
    static class DataSourceHolder {
        final JdbcTemplate jdbcTemplate;
        final DataSource dataSource;

        DataSourceHolder(JdbcTemplate jdbcTemplate, DataSource dataSource) {
            this.jdbcTemplate = jdbcTemplate;
            this.dataSource = dataSource;
        }
    }
}