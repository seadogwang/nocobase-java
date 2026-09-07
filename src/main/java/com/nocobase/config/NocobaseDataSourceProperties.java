package com.nocobase.config;

import com.nocobase.sql.SqlErrorSanitizer;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Multi-datasource configuration model.
 *
 * <p>Binds {@code nocobase.data-sources.*} entries. The "main" key is reserved
 * and auto-created from {@code spring.datasource} when not explicitly configured.
 * External datasource configurations do NOT affect JPA/Flyway metadata on main.
 *
 * <p><b>Validation rules:</b>
 * <ul>
 *   <li>Key format: {@code [A-Za-z][A-Za-z0-9_-]{0,63}}</li>
 *   <li>{@code url} is required for external data sources</li>
 *   <li>{@code dialect} must be {@code h2} or {@code postgresql}</li>
 *   <li>{@code driverClassName} is derivable from the JDBC URL or must be explicitly set;
 *       if neither, validation rejects the config</li>
 *   <li>External data sources are always {@code readOnly=true};
 *       setting {@code readOnly=false} is rejected</li>
 * </ul>
 *
 * <p>All error messages are sanitized via {@link SqlErrorSanitizer} to prevent
 * sensitive data (JDBC URLs, credentials) from leaking into startup logs.
 */
@ConfigurationProperties(prefix = "nocobase")
public class NocobaseDataSourceProperties implements EnvironmentAware {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    public static final String MAIN_KEY = "main";

    private Map<String, DataSourceConfig> dataSources = new LinkedHashMap<>();

    private Environment environment;

    // ── getters / setters ──────────────────────────────────────────────

    public Map<String, DataSourceConfig> getDataSources() {
        return dataSources;
    }

    public void setDataSources(Map<String, DataSourceConfig> dataSources) {
        this.dataSources = dataSources != null ? dataSources : new LinkedHashMap<>();
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    // ── convenience accessors ──────────────────────────────────────────

    /**
     * Returns the main data source config (auto-created from {@code spring.datasource}).
     */
    public DataSourceConfig getMain() {
        return dataSources.get(MAIN_KEY);
    }

    /**
     * Returns the config for a named data source, or {@code null} if not found.
     */
    public DataSourceConfig getDataSource(String key) {
        return dataSources.get(key);
    }

    /**
     * Returns {@code true} if the named data source is configured and enabled.
     * An enabled data source can be used by SQL collections, a disabled one cannot.
     */
    public boolean isEnabled(String key) {
        DataSourceConfig config = dataSources.get(key);
        return config != null && config.isEnabled();
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    /**
     * Auto-creates the "main" entry from {@code spring.datasource} (if not explicitly
     * configured) and validates all data-source keys and external datasource configs.
     */
    @PostConstruct
    public void init() {
        // "main" is reserved and cannot be explicitly configured
        if (dataSources.containsKey(MAIN_KEY)) {
            throw new IllegalStateException(
                    "Data source key '" + MAIN_KEY + "' is reserved and cannot be configured explicitly. "
                            + "The main data source is auto-created from spring.datasource.");
        }
        // Auto-create "main" from spring.datasource
        ensureMainDataSource();
        // Validate all non-main keys
        for (String key : dataSources.keySet()) {
            if (MAIN_KEY.equals(key)) {
                continue; // auto-created, skip
            }
            if (!KEY_PATTERN.matcher(key).matches()) {
                throw new IllegalStateException(
                        "Invalid data source key '" + key + "'. "
                                + "Key must match pattern: " + KEY_PATTERN.pattern());
            }
        }
        // Validate external datasource config fields
        validateExternalDataSourceConfigs();
    }

    /**
     * Validates all data-source keys: format check and reserved-key protection.
     * Public so it can be called directly in unit tests.
     * Does NOT auto-create "main" -- use init() for the full lifecycle.
     */
    public void validateKeys() {
        for (String key : dataSources.keySet()) {
            if (MAIN_KEY.equals(key)) {
                throw new IllegalStateException(
                        "Data source key '" + MAIN_KEY + "' is reserved and cannot be configured explicitly. "
                                + "The main data source is auto-created from spring.datasource.");
            }
            if (!KEY_PATTERN.matcher(key).matches()) {
                throw new IllegalStateException(
                        "Invalid data source key '" + key + "'. "
                                + "Key must match pattern: " + KEY_PATTERN.pattern());
            }
        }
    }

    /**
     * Validates external datasource config fields: url required, dialect must be
     * h2 or postgresql if specified, readOnly must be true, driverClassName
     * must be derivable or explicitly set.
     * Public so it can be called directly in unit tests.
     *
     * @throws IllegalStateException if any external datasource has invalid config
     */
    public void validateConfigFields() {
        for (var entry : dataSources.entrySet()) {
            String key = entry.getKey();
            if (MAIN_KEY.equals(key)) {
                continue; // skip auto-created main
            }
            DataSourceConfig config = entry.getValue();

            // url is required for external datasources
            if (config.getUrl() == null || config.getUrl().isBlank()) {
                throw new IllegalStateException(
                        sanitize("Data source '" + key + "': 'url' is required for external data sources."));
            }

            // dialect must be h2 or postgresql if specified
            String dialect = config.getDialect();
            if (dialect != null && !dialect.isBlank()) {
                if (!"h2".equalsIgnoreCase(dialect) && !"postgresql".equalsIgnoreCase(dialect)) {
                    throw new IllegalStateException(
                            sanitize("Data source '" + key + "': 'dialect' must be 'h2' or 'postgresql', but was '"
                                    + dialect + "'."));
                }
            }

            // External data sources are always read-only; reject readOnly=false
            if (!config.isReadOnly()) {
                throw new IllegalStateException(
                        sanitize("Data source '" + key + "': external data sources must be read-only. "
                                + "Set read-only=true or remove the read-only property."));
            }

            // driverClassName: must be derivable from URL or explicitly set
            String driverClassName = config.getDriverClassName();
            String url = config.getUrl();
            if (driverClassName == null || driverClassName.isBlank()) {
                String derived = deriveDriverClassName(url);
                if (derived == null) {
                    throw new IllegalStateException(
                            sanitize("Data source '" + key + "': 'driver-class-name' is required. "
                                    + "It could not be derived from the JDBC URL. "
                                    + "Supported URL prefixes: jdbc:h2:, jdbc:postgresql:"));
                }
            } else {
                // Validate that the configured driverClassName is consistent with the URL
                String derived = deriveDriverClassName(url);
                if (derived != null && !derived.equals(driverClassName)) {
                    throw new IllegalStateException(
                            sanitize("Data source '" + key + "': 'driver-class-name' '" + driverClassName
                                    + "' does not match the JDBC URL. Expected: " + derived));
                }
            }
        }
    }

    private void ensureMainDataSource() {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null) {
            throw new IllegalStateException(
                    "spring.datasource.url is not configured; cannot auto-create the 'main' data source.");
        }
        DataSourceConfig mainConfig = new DataSourceConfig();
        mainConfig.setUrl(url);
        mainConfig.setDriverClassName(environment.getProperty("spring.datasource.driver-class-name"));
        mainConfig.setUsername(environment.getProperty("spring.datasource.username"));
        mainConfig.setPassword(environment.getProperty("spring.datasource.password"));
        mainConfig.setEnabled(true);
        mainConfig.setReadOnly(false); // main is writable
        dataSources.put(MAIN_KEY, mainConfig);
    }

    private void validateExternalDataSourceConfigs() {
        validateConfigFields();
    }

    /**
     * Derive the JDBC driver class name from a JDBC URL.
     * Returns null if the URL does not match a known pattern.
     */
    public static String deriveDriverClassName(String url) {
        if (url == null) return null;
        if (url.startsWith("jdbc:h2:")) return "org.h2.Driver";
        if (url.startsWith("jdbc:postgresql:")) return "org.postgresql.Driver";
        return null;
    }

    /**
     * Sanitize an error message to prevent sensitive data leakage.
     */
    private static String sanitize(String message) {
        return SqlErrorSanitizer.sanitizeForLog(message);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Inner class: DataSourceConfig
    // ═══════════════════════════════════════════════════════════════════

    public static class DataSourceConfig {

        private String url;
        private String driverClassName;
        private String username;
        private String password;
        private boolean enabled = true;
        private String dialect;
        private boolean readOnly = true;
        private String displayName;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDialect() {
            return dialect;
        }

        public void setDialect(String dialect) {
            this.dialect = dialect;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public void setReadOnly(boolean readOnly) {
            this.readOnly = readOnly;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }
}