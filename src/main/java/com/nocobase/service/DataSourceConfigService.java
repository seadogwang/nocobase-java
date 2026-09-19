package com.nocobase.service;

import com.nocobase.config.NocobaseDataSourceProperties;
import com.nocobase.entity.DataSourceConfigEntity;
import com.nocobase.repository.DataSourceConfigRepository;
import com.nocobase.sql.SqlDataSourceResolver;
import com.nocobase.sql.SqlErrorSanitizer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Manages external data source configurations at runtime.
 * Syncs DB-persisted configs with the in-memory
 * {@link NocobaseDataSourceProperties} so that
 * {@link SqlDataSourceResolver} can resolve them.
 *
 * <p>Secrets (password) are encrypted at rest with AES-256-GCM and are never
 * returned to callers. All log messages are sanitized via
 * {@link SqlErrorSanitizer}.
 *
 * <p>URL and driver are validated against a whitelist to prevent
 * arbitrary JDBC driver loading and dangerous URL parameters.
 */
@Service
public class DataSourceConfigService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfigService.class);

    /** Reuses the key validation pattern from NocobaseDataSourceProperties */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    /** P0-D: Allowed JDBC driver class names */
    private static final Set<String> ALLOWED_DRIVERS = Set.of("org.h2.Driver", "org.postgresql.Driver");

    /** P0-D: Allowed JDBC URL prefixes */
    private static final Set<String> ALLOWED_URL_PREFIXES = Set.of("jdbc:h2:", "jdbc:postgresql:");

    /** P0-D: Dangerous H2 URL parameters/options */
    private static final Set<String> FORBIDDEN_H2_TOKENS = Set.of(
            "INIT", "RUNSCRIPT", "ACCESS_MODE_DATA"
    );

    /** P0-A: Sensitive query parameters to strip from API responses */
    private static final Set<String> SENSITIVE_QUERY_PARAMS = Set.of(
            "user", "username", "password", "pass", "pwd",
            "sslpassword", "secret", "token",
            "accesskey", "accesskeysecret", "accesskeyid"
    );

    private final DataSourceConfigRepository repository;
    private final NocobaseDataSourceProperties dataSourceProperties;
    private final SqlDataSourceResolver dataSourceResolver;
    private final DataSourcePasswordEncryptor passwordEncryptor;
    private final AuditLogService auditLogService;

    public DataSourceConfigService(DataSourceConfigRepository repository,
                                   NocobaseDataSourceProperties dataSourceProperties,
                                   SqlDataSourceResolver dataSourceResolver,
                                   DataSourcePasswordEncryptor passwordEncryptor,
                                   AuditLogService auditLogService) {
        this.repository = repository;
        this.dataSourceProperties = dataSourceProperties;
        this.dataSourceResolver = dataSourceResolver;
        this.passwordEncryptor = passwordEncryptor;
        this.auditLogService = auditLogService;
    }

    /**
     * On startup, load all persisted external data source configs into the
     * in-memory properties map so they are available for resolution.
     * Gracefully handles the case where the table does not yet exist
     * (first startup before migration). All other exceptions, including
     * decryption failures, propagate as fatal errors.
     *
     * <p>P0-B: Plaintext passwords from legacy records are migrated to
     * encrypted form on read.
     * <p>P0-C: Only "table not found" downgrades to warning; decryption
     * failures and other exceptions fail-fast.
     */
    @PostConstruct
    public void loadFromDatabase() {
        try {
            List<DataSourceConfigEntity> configs = repository.findAll();
            int migratedCount = 0;
            for (DataSourceConfigEntity entity : configs) {
                // P0-B: Migrate plaintext passwords to encrypted on read
                String storedPwd = entity.getPassword();
                if (storedPwd != null && !storedPwd.isEmpty()
                        && !passwordEncryptor.isEncrypted(storedPwd)) {
                    // Old plaintext record -- encrypt and mark for save
                    entity.setPassword(passwordEncryptor.encrypt(storedPwd));
                    migratedCount++;
                    log.info("Migrated plaintext password for data source '{}'", entity.getDsKey());
                }
                if (entity.isEnabled()) {
                    NocobaseDataSourceProperties.DataSourceConfig config = toPropertiesConfig(entity);
                    dataSourceProperties.getDataSources().put(entity.getDsKey(), config);
                    log.info("Loaded external data source '{}' from database", entity.getDsKey());
                }
            }
            if (migratedCount > 0) {
                repository.saveAll(configs);
                log.info("Migrated {} plaintext password(s) to encrypted storage", migratedCount);
            }
            log.info("Loaded {} external data source configs from database", configs.size());
        } catch (Exception e) {
            // P0-C: Only "table not found" can downgrade to warning
            if (isTableNotFound(e)) {
                log.warn("External data source config table not found. "
                        + "This is expected on first startup before Flyway migration. "
                        + "Cause: {}", SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
                return;
            }
            // All other exceptions (including decryption failures) fail-fast
            log.error("Failed to load external data source configs from database: {}",
                    SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
            throw new RuntimeException("Failed to load external data source configs from database", e);
        }
    }

    // -- public API ------------------------------------------------------

    /**
     * List all external data sources (never returns passwords).
     */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : dataSourceProperties.getDataSources().entrySet()) {
            if ("main".equals(entry.getKey())) {
                continue; // main is not an external data source
            }
            result.add(toResponseMap(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /**
     * Get a single external data source by key (never returns password).
     */
    public Map<String, Object> getByKey(String dsKey) {
        NocobaseDataSourceProperties.DataSourceConfig config = dataSourceProperties.getDataSource(dsKey);
        if (config == null || "main".equals(dsKey)) {
            throw new IllegalArgumentException("Data source '" + dsKey + "' not found");
        }
        return toResponseMap(dsKey, config);
    }

    /**
     * Create a new external data source.
     * Validates the key, URL/driver whitelist, persists to DB with encrypted
     * password, and registers in the in-memory map.
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String dsKey = (String) body.get("key");
        try {
            if (dsKey == null || dsKey.isBlank()) {
                throw new IllegalArgumentException("'key' is required");
            }
            validateKey(dsKey);

            if (repository.existsByDsKey(dsKey)) {
                throw new IllegalArgumentException("Data source '" + dsKey + "' already exists");
            }
            if (dataSourceProperties.getDataSource(dsKey) != null) {
                throw new IllegalArgumentException("Data source '" + dsKey + "' already exists in configuration");
            }

            DataSourceConfigEntity entity = buildEntity(dsKey, body);
            entity = repository.save(entity);

            // Register in in-memory properties
            if (entity.isEnabled()) {
                dataSourceProperties.getDataSources().put(dsKey, toPropertiesConfig(entity));
                log.info("Registered external data source '{}'", dsKey);
            }

            auditLogService.auditSuccess("create", "dataSource", dsKey,
                    Map.of("key", dsKey, "displayName", entity.getDisplayName(),
                            "enabled", entity.isEnabled()));

            return toResponseMap(dsKey, toPropertiesConfig(entity));
        } catch (Exception e) {
            auditLogService.auditFailure("create", "dataSource", dsKey != null ? dsKey : "unknown",
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Update an existing external data source.
     * Validates URL/driver whitelist on update, encrypts password before storage.
     */
    @Transactional
    public Map<String, Object> update(Map<String, Object> body) {
        String dsKey = (String) body.get("key");
        try {
            if (dsKey == null || dsKey.isBlank()) {
                throw new IllegalArgumentException("'key' is required");
            }

            DataSourceConfigEntity entity = repository.findByDsKey(dsKey)
                    .orElseThrow(() -> new IllegalArgumentException("Data source '" + dsKey + "' not found"));

            // Update fields from body
            if (body.containsKey("displayName")) {
                entity.setDisplayName((String) body.get("displayName"));
            }
            if (body.containsKey("url")) {
                String url = (String) body.get("url");
                if (url == null || url.isBlank()) {
                    throw new IllegalArgumentException("'url' is required for external data sources");
                }
                entity.setUrl(url);
            }
            if (body.containsKey("driverClassName")) {
                entity.setDriverClassName((String) body.get("driverClassName"));
            }
            if (body.containsKey("username")) {
                entity.setUsername((String) body.get("username"));
            }
            if (body.containsKey("password")) {
                // P1-E: When password is empty/null, keep existing password
                String rawPassword = (String) body.get("password");
                if (rawPassword != null && !rawPassword.isBlank()) {
                    entity.setPassword(passwordEncryptor.encrypt(rawPassword));
                }
                // else: keep existing password (don't overwrite with empty)
            }
            if (body.containsKey("enabled")) {
                entity.setEnabled(parseBoolean(body.get("enabled")));
            }
            if (body.containsKey("dialect")) {
                String dialect = (String) body.get("dialect");
                if (dialect != null && !dialect.isBlank()) {
                    if (!"h2".equalsIgnoreCase(dialect) && !"postgresql".equalsIgnoreCase(dialect)) {
                        throw new IllegalArgumentException(
                                "Dialect must be 'h2' or 'postgresql', but was '" + dialect + "'");
                    }
                }
                entity.setDialect(dialect);
            }

            // Validate config fields (including URL/driver whitelist)
            validateConfigFields(entity);

            entity = repository.save(entity);

            // Update in-memory properties
            if (entity.isEnabled()) {
                dataSourceProperties.getDataSources().put(dsKey, toPropertiesConfig(entity));
            } else {
                dataSourceProperties.getDataSources().remove(dsKey);
            }

            // Invalidate the resolver cache so the next resolve() call picks up
            // the updated configuration (or fails with a stable error if disabled).
            dataSourceResolver.invalidateCache(dsKey);

            log.info("Updated external data source '{}'", dsKey);
            auditLogService.auditSuccess("update", "dataSource", dsKey,
                    Map.of("key", dsKey, "displayName", entity.getDisplayName(),
                            "enabled", entity.isEnabled()));
            return toResponseMap(dsKey, toPropertiesConfig(entity));
        } catch (Exception e) {
            auditLogService.auditFailure("update", "dataSource", dsKey != null ? dsKey : "unknown",
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Delete an external data source.
     */
    @Transactional
    public void delete(String dsKey) {
        try {
            if (dsKey == null || dsKey.isBlank()) {
                throw new IllegalArgumentException("'key' is required");
            }

            DataSourceConfigEntity entity = repository.findByDsKey(dsKey)
                    .orElseThrow(() -> new IllegalArgumentException("Data source '" + dsKey + "' not found"));

            repository.delete(entity);

            // Remove from in-memory properties
            dataSourceProperties.getDataSources().remove(dsKey);

            // Invalidate the resolver cache so any cached connections are closed
            dataSourceResolver.invalidateCache(dsKey);

            log.info("Deleted external data source '{}'", dsKey);
            auditLogService.auditSuccess("destroy", "dataSource", dsKey, Map.of("key", dsKey));
        } catch (Exception e) {
            auditLogService.auditFailure("destroy", "dataSource", dsKey != null ? dsKey : "unknown",
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Test the connection for a data source configuration.
     * Returns a success/failure result without exposing credentials.
     *
     * <p>P0-D: Applies same URL/driver/dialect validation as create/update.
     */
    @Transactional
    public Map<String, Object> testConnection(Map<String, Object> body) {
        try {
            String url = (String) body.get("url");
            String driverClassName = (String) body.get("driverClassName");
            String username = (String) body.get("username");
            String password = (String) body.get("password");
            String dialect = (String) body.get("dialect");

            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("'url' is required for testing connection");
            }

            // P0-D: Validate URL/driver whitelist
            validateUrlAndDriver(url, driverClassName);

            // P0-D: Validate dialect if provided
            if (dialect != null && !dialect.isBlank()) {
                if (!"h2".equalsIgnoreCase(dialect) && !"postgresql".equalsIgnoreCase(dialect)) {
                    throw new IllegalArgumentException(
                            "Dialect must be 'h2' or 'postgresql', but was '" + dialect + "'");
                }
            }

            // P0-D: Validate driver is in allowed list (no arbitrary Class.forName)
            if (driverClassName != null && !driverClassName.isBlank()) {
                if (!ALLOWED_DRIVERS.contains(driverClassName)) {
                    return Map.of(
                            "success", false,
                            "message", "Driver not supported: " + driverClassName
                    );
                }
                try {
                    Class.forName(driverClassName);
                } catch (ClassNotFoundException e) {
                    return Map.of(
                            "success", false,
                            "message", "JDBC driver class not found"
                    );
                }
            }

            try (Connection conn = DriverManager.getConnection(url, username, password)) {
                boolean valid = conn.isValid(5);
                String sanitizedUrl = sanitizeUrlForResponse(url);
                auditLogService.auditSuccess("testConnection", "dataSource", sanitizedUrl,
                        Map.of("success", true));
                return Map.of(
                        "success", valid,
                        "message", valid ? "Connection successful" : "Connection validation failed"
                );
            } catch (SQLException e) {
                String category = categorizeConnectionError(e);
                String sanitizedUrl = sanitizeUrlForResponse(url != null ? url : "unknown");
                log.warn("Connection test failed for {}: {}", sanitizedUrl, category);
                auditLogService.auditFailure("testConnection", "dataSource", sanitizedUrl,
                        Map.of("success", false, "error", category));
                return Map.of(
                        "success", false,
                        "message", "Connection failed: " + category
                );
            }
        } catch (Exception e) {
            String rawUrl = (String) body.get("url");
            String sanitizedUrl = sanitizeUrlForResponse(rawUrl != null ? rawUrl : "unknown");
            String category = categorizeConnectionError(e);
            auditLogService.auditFailure("testConnection", "dataSource", sanitizedUrl,
                    Map.of("error", category));
            throw e;
        }
    }

    // -- private helpers -------------------------------------------------

    private void validateKey(String dsKey) {
        if ("main".equals(dsKey) || "default".equals(dsKey)) {
            throw new IllegalArgumentException(
                    "Data source key '" + dsKey + "' is reserved");
        }
        if (!KEY_PATTERN.matcher(dsKey).matches()) {
            throw new IllegalArgumentException(
                    "Invalid data source key '" + dsKey + "'. "
                    + "Key must match pattern: " + KEY_PATTERN.pattern());
        }
    }

    private void validateConfigFields(DataSourceConfigEntity entity) {
        if (entity.getUrl() == null || entity.getUrl().isBlank()) {
            throw new IllegalArgumentException("'url' is required for external data sources");
        }

        // P0-D: Validate URL/driver whitelist
        validateUrlAndDriver(entity.getUrl(), entity.getDriverClassName());

        if (entity.getDialect() != null && !entity.getDialect().isBlank()) {
            String dialect = entity.getDialect();
            if (!"h2".equalsIgnoreCase(dialect) && !"postgresql".equalsIgnoreCase(dialect)) {
                throw new IllegalArgumentException(
                        "Dialect must be 'h2' or 'postgresql', but was '" + dialect + "'");
            }
        }
        // External data sources must be read-only
        if (!entity.isReadOnly()) {
            throw new IllegalArgumentException(
                    "External data sources must be read-only");
        }
        // Derive driverClassName from URL if not set
        if (entity.getDriverClassName() == null || entity.getDriverClassName().isBlank()) {
            String derived = NocobaseDataSourceProperties.deriveDriverClassName(entity.getUrl());
            if (derived == null) {
                throw new IllegalArgumentException(
                        "'driverClassName' is required and could not be derived from the JDBC URL");
            }
            entity.setDriverClassName(derived);
        }
    }

    /**
     * P0-D: Validate that the JDBC URL and driver class name are in the allowed
     * whitelist. Rejects unknown URL prefixes, unknown drivers, and H2 URLs
     * containing dangerous parameters.
     */
    private void validateUrlAndDriver(String url, String driverClassName) {
        // P0-D: URL must start with an allowed prefix
        boolean validPrefix = false;
        for (String prefix : ALLOWED_URL_PREFIXES) {
            if (url.startsWith(prefix)) {
                validPrefix = true;
                break;
            }
        }
        if (!validPrefix) {
            throw new IllegalArgumentException(
                    "JDBC URL must start with 'jdbc:h2:' or 'jdbc:postgresql:', but was: "
                    + sanitizeUrlForResponse(url));
        }

        // P0-D: driverClassName must be in the allowed list
        if (driverClassName != null && !driverClassName.isBlank()) {
            if (!ALLOWED_DRIVERS.contains(driverClassName)) {
                throw new IllegalArgumentException(
                        "Driver class name must be org.h2.Driver or org.postgresql.Driver, "
                        + "but was: " + driverClassName);
            }
        }

        // P0-D: H2-specific dangerous parameter checks
        if (url.startsWith("jdbc:h2:")) {
            String upperUrl = url.toUpperCase();
            for (String token : FORBIDDEN_H2_TOKENS) {
                if (upperUrl.contains(token.toUpperCase())) {
                    throw new IllegalArgumentException(
                            "H2 URL contains forbidden parameter/option: " + token);
                }
            }
        }
    }

    /**
     * Map a connection-test exception to a safe, stable category string for
     * audit details, application logs, and API responses.
     *
     * <p>The raw exception message from a JDBC driver may contain hostnames,
     * ports, or quoted usernames (e.g. PostgreSQL's
     * {@code FATAL: password authentication failed for user "admin"}). It must
     * never be persisted, logged, or returned to the client. This method
     * inspects keywords only to pick a bucket and returns the bucket string.
     *
     * <p>Categories: {@code invalid-url}, {@code missing-url},
     * {@code unsupported-driver}, {@code invalid-dialect},
     * {@code connection-refused}, {@code unknown-host}, {@code auth-failed},
     * {@code connection-error} (fallback).
     */
    private String categorizeConnectionError(Exception e) {
        if (e == null || e.getMessage() == null) {
            return "connection-error";
        }
        String msg = e.getMessage().toLowerCase();
        if (e instanceof IllegalArgumentException) {
            if (msg.contains("'url' is required")) {
                return "missing-url";
            }
            if (msg.contains("must start with") || msg.contains("forbidden parameter")) {
                return "invalid-url";
            }
            if (msg.contains("driver class name must be")
                    || msg.contains("driver not supported")
                    || msg.contains("jdbc driver class not found")) {
                return "unsupported-driver";
            }
            if (msg.contains("dialect must be")) {
                return "invalid-dialect";
            }
        }
        if (msg.contains("no suitable driver")) {
            return "unsupported-driver";
        }
        if (msg.contains("unknown host") || msg.contains("unknownhostexception")) {
            return "unknown-host";
        }
        if (msg.contains("connection refused") || msg.contains("connection timed out")
                || msg.contains("connect failed") || msg.contains("connection error")) {
            return "connection-refused";
        }
        if (msg.contains("authentication") || msg.contains("password")
                || msg.contains("fatal") || msg.contains("denied")) {
            return "auth-failed";
        }
        return "connection-error";
    }

    private DataSourceConfigEntity buildEntity(String dsKey, Map<String, Object> body) {
        DataSourceConfigEntity entity = new DataSourceConfigEntity();
        entity.setDsKey(dsKey);
        entity.setDisplayName((String) body.getOrDefault("displayName", dsKey));

        String url = (String) body.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("'url' is required for external data sources");
        }
        entity.setUrl(url);

        entity.setDriverClassName((String) body.get("driverClassName"));
        entity.setUsername((String) body.get("username"));

        // P0-B: Encrypt password before storing
        String rawPassword = (String) body.get("password");
        entity.setPassword(passwordEncryptor.encrypt(rawPassword));

        // P0-D: Support boolean/string/"0"/"1" for enabled
        entity.setEnabled(parseBoolean(body.get("enabled")));
        entity.setDialect((String) body.get("dialect"));
        entity.setReadOnly(true); // always read-only for external

        validateConfigFields(entity);
        return entity;
    }

    /**
     * P0-B: Convert entity to in-memory properties config, decrypting the
     * password for runtime use.
     */
    private NocobaseDataSourceProperties.DataSourceConfig toPropertiesConfig(DataSourceConfigEntity entity) {
        NocobaseDataSourceProperties.DataSourceConfig config =
                new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl(entity.getUrl());
        config.setDriverClassName(entity.getDriverClassName());
        config.setUsername(entity.getUsername());
        // P0-B: Decrypt password for runtime connection use
        config.setPassword(passwordEncryptor.decrypt(entity.getPassword()));
        config.setEnabled(entity.isEnabled());
        config.setDialect(entity.getDialect());
        config.setReadOnly(true);
        config.setDisplayName(entity.getDisplayName());
        return config;
    }

    // Build a response map for a data source (passwords are NEVER included).
    //
    // P1-E: Minimal-exposure strategy:
    //   - password is NEVER returned. Use hasPassword instead.
    //   - url is sanitized to mask hostname and database name.
    //   - maskedUrl provides the same sanitized URL as url.
    //   - maskedUsername shows first 2 chars + "***".
    //   - hasPassword is a boolean (never reveals the value).
    private Map<String, Object> toResponseMap(String key, NocobaseDataSourceProperties.DataSourceConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("displayName", config.getDisplayName() != null ? config.getDisplayName() : key);
        String sanitizedForResponse = sanitizeUrlForResponse(config.getUrl());
        map.put("url", sanitizedForResponse);
        map.put("maskedUrl", sanitizedForResponse);
        map.put("driverClassName", config.getDriverClassName());
        map.put("maskedUsername", maskUsername(config.getUsername()));
        map.put("hasPassword", config.getPassword() != null && !config.getPassword().isEmpty());
        // password is NEVER returned
        map.put("enabled", config.isEnabled());
        map.put("dialect", config.getDialect() != null ? config.getDialect() : "");
        map.put("readOnly", config.isReadOnly());
        return map;
    }

    // -- P0-C: Exception classification helpers --------------------------

    /**
     * P0-C: Check whether the exception is a "table not found" / "first startup
     * before migration" condition. Only this specific case can downgrade to
     * a warning during loadFromDatabase; all other exceptions fail-fast.
     *
     * <p>Checks common patterns across H2, PostgreSQL, and other databases,
     * traversing the full cause chain.
     */
    public static boolean isTableNotFound(Throwable ex) {
        if (ex == null) {
            return false;
        }
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if ((msg.contains("table") || msg.contains("relation") || msg.contains("view"))
                && (msg.contains("not found") || msg.contains("does not exist")
                    || msg.contains("not exist") || msg.contains("doesn't exist")
                    || msg.contains("unknown"))) {
            return true;
        }
        // Also check for H2-specific error code 42102 (Table not found)
        if (msg.contains("42102")) {
            return true;
        }
        return isTableNotFound(ex.getCause());
    }

    // -- P0-D: type-compatibility helpers --------------------------------

    /**
     * Parse a boolean value from a map entry that might be a Boolean, a String,
     * or a Number (for compatibility with various JSON serialization formats).
     *
     * <p>Truthy values: {@code true}, {@code "true"} (case-insensitive),
     * {@code "1"}, non-zero numbers.
     * <p>Falsy values: {@code false}, {@code "false"} (case-insensitive),
     * {@code "0"}, zero.
     * <p>Default (null): {@code true}.
     */
    static boolean parseBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return true; // default when missing or unknown type
    }

    // -- P0-D: URL sanitization for error messages -----------------------

    /**
     * Mask credentials in a JDBC URL for safe inclusion in error messages.
     */
    public static String sanitizeUrl(String url) {
        if (url == null) {
            return null;
        }
        // Mask embedded credentials in JDBC URL: jdbc:postgresql://user:pass@host:5432/db
        return url.replaceAll("//([^@]*)@", "//***:***@");
    }

    // -- P1-E: sanitizeUrlForResponse -- masks hostname + database name ------

    // P1-E: Sanitize a JDBC URL for API responses, masking both embedded
    // credentials AND hostname/database name.
    // Strips sensitive query parameters (user, password, token, etc.)
    // while preserving non-sensitive params like sslmode, connectTimeout.
    // Example: jdbc:postgresql://db.example.com:5432/mydb?user=u&password=p&sslmode=require
    //       -> jdbc:postgresql://***:***/***?sslmode=require
    public static String sanitizeUrlForResponse(String url) {
        if (url == null) {
            return null;
        }
        // First mask embedded credentials (user:pass@)
        url = sanitizeUrl(url);

        // Mask hostname, port, and database name in PostgreSQL-style URLs.
        // Handles both //host:port/db and //***:***@host:port/db patterns.
        url = url.replaceAll("//(\\*\\*\\*:\\*\\*\\*@)?[^/:]+(:\\d+)?/[^?;&]+",
                "//$1***:***/***");

        // Mask H2 database name in mem: URLs
        // jdbc:h2:mem:dbname;options -> jdbc:h2:mem:***;options
        url = url.replaceAll("(:mem:)[^?;&]+", "$1***");

        // Mask H2 database path in file: URLs
        // jdbc:h2:file:./path/to/db -> jdbc:h2:file:***
        url = url.replaceAll("(:file:)[^?;&]+", "$1***");

        // Strip sensitive query parameters from both ? and ; separated params
        url = stripSensitiveQueryParams(url);

        return url;
    }

    /**
     * Strip sensitive query parameters from a JDBC URL.
     * Handles both {@code ?}-separated (PostgreSQL) and {@code ;}-separated (H2) params.
     * Non-sensitive params like {@code sslmode}, {@code connectTimeout} are preserved.
     *
     * @param url the URL to strip sensitive params from
     * @return the URL with sensitive params removed
     */
    public static String stripSensitiveQueryParams(String url) {
        if (url == null) {
            return null;
        }

        // Handle ?-separated params (PostgreSQL style)
        int questionMarkIdx = url.indexOf('?');
        if (questionMarkIdx >= 0) {
            String base = url.substring(0, questionMarkIdx);
            String query = url.substring(questionMarkIdx + 1);
            String filtered = filterParams(query, "&");
            return filtered.isEmpty() ? base : base + "?" + filtered;
        }

        // Handle ;-separated options (H2 style)
        // Only the first ; after the database identifier starts options
        int semicolonIdx = url.indexOf(';');
        if (semicolonIdx >= 0) {
            String base = url.substring(0, semicolonIdx);
            String options = url.substring(semicolonIdx + 1);
            String filtered = filterParams(options, ";");
            return filtered.isEmpty() ? base : base + ";" + filtered;
        }

        return url;
    }

    /**
     * Filter a param string, removing entries whose key is in
     * {@link #SENSITIVE_QUERY_PARAMS}.
     *
     * @param paramString the param string (e.g. "user=sa&password=secret&sslmode=require")
     * @param separator   the separator between params ("&" or ";")
     * @return the filtered param string
     */
    private static String filterParams(String paramString, String separator) {
        if (paramString == null || paramString.isEmpty()) {
            return "";
        }
        String[] params = paramString.split(separator, -1);
        List<String> kept = new ArrayList<>();
        for (String param : params) {
            String trimmed = param.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eqIdx = trimmed.indexOf('=');
            String key = (eqIdx >= 0) ? trimmed.substring(0, eqIdx) : trimmed;
            String keyLower = key.trim().toLowerCase();
            if (!SENSITIVE_QUERY_PARAMS.contains(keyLower)) {
                kept.add(trimmed);
            }
        }
        return String.join(separator, kept);
    }

    /**
     * P1-E: Mask a username for safe display in API responses.
     * Shows first 2 characters followed by {@code ***} for usernames longer
     * than 2 characters; returns {@code ***} for shorter usernames.
     */
    public static String maskUsername(String username) {
        if (username == null || username.isEmpty()) {
            return "";
        }
        if (username.length() <= 2) {
            return "***";
        }
        return username.substring(0, 2) + "***";
    }
}