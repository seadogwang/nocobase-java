package com.nocobase.controller;

import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.service.DataSourceConfigService;
import com.nocobase.sql.SqlDataSourceResolver;
import com.nocobase.web.RequestIdContext;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Health check endpoint for monitoring and operations.
 * <p>
 * Accessible at GET /api/health without authentication. Returns the status of
 * core components: main database, Flyway migrations, runtime collection
 * registry, and external data sources.
 * <p>
 * No credentials, JDBC URLs, or internal details are exposed.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final DataSource dataSource;
    private final Flyway flyway;
    private final CollectionRuntimeService collectionRuntimeService;
    private final DataSourceConfigService dataSourceConfigService;
    private final SqlDataSourceResolver dataSourceResolver;

    @Autowired
    public HealthController(DataSource dataSource,
                            @Autowired(required = false) Flyway flyway,
                            CollectionRuntimeService collectionRuntimeService,
                            DataSourceConfigService dataSourceConfigService,
                            SqlDataSourceResolver dataSourceResolver) {
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.collectionRuntimeService = collectionRuntimeService;
        this.dataSourceConfigService = dataSourceConfigService;
        this.dataSourceResolver = dataSourceResolver;
    }

    /**
     * Health check endpoint.
     * GET /api/health
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> database = checkDatabase();
        Map<String, Object> flywayStatus = checkFlyway();
        Map<String, Object> runtimeRegistry = checkRuntimeRegistry();
        Map<String, Object> externalDataSources = checkExternalDataSources();

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("database", database);
        components.put("flyway", flywayStatus);
        components.put("runtimeRegistry", runtimeRegistry);
        components.put("externalDataSources", externalDataSources);

        boolean dbUp = "UP".equals(database.get("status"));
        boolean flywayHealthy = !"UNAVAILABLE".equals(flywayStatus.get("status"));
        boolean overallUp = dbUp && flywayHealthy;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", overallUp ? "UP" : "DOWN");
        result.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        result.put("components", components);
        return result;
    }

    /**
     * Liveness check -- lightweight, no database or external service access.
     * Intended for Kubernetes liveness probes.
     * GET /api/health/live
     */
    @GetMapping("/health/live")
    public Map<String, Object> live() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        // Lightweight observability fields (no db/flyway access for liveness).
        result.put("releaseVersion", releaseVersion());
        result.put("requestId", RequestIdContext.get());
        return result;
    }

    /**
     * Readiness check -- verifies core components required to serve traffic.
     * Intended for Kubernetes readiness probes. Returns HTTP 503 when DOWN.
     * GET /api/health/ready
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> database = checkDatabase();
        Map<String, Object> flywayStatus = checkFlyway();
        Map<String, Object> runtimeRegistry = checkRuntimeRegistry();

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("database", database);
        components.put("flyway", flywayStatus);
        components.put("runtimeRegistry", runtimeRegistry);

        boolean dbUp = "UP".equals(database.get("status"));
        boolean flywayHealthy = !"UNAVAILABLE".equals(flywayStatus.get("status"));
        boolean overallUp = dbUp && flywayHealthy;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", overallUp ? "UP" : "DOWN");
        result.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        // Structured observability fields (no secrets, SQL, JDBC URLs, or
        // internal hostnames are ever exposed).
        result.put("releaseVersion", releaseVersion());
        result.put("databaseMode", database.get("type"));
        result.put("migrationState", flywayStatus.get("status"));
        if (flywayStatus.get("version") != null) {
            result.put("migrationVersion", flywayStatus.get("version"));
        }
        result.put("requestId", RequestIdContext.get());
        result.put("components", components);

        return ResponseEntity.status(overallUp ? 200 : 503).body(result);
    }

    /**
     * Read the application release version from the JAR manifest
     * ({@code Implementation-Version}), falling back to "unknown". Never null.
     */
    private String releaseVersion() {
        Package pkg = getClass().getPackage();
        String v = pkg != null ? pkg.getImplementationVersion() : null;
        return v != null ? v : "unknown";
    }

    // ------------------------------------------------------------------
    // component checks
    // ------------------------------------------------------------------

    /**
     * Check the main datasource by executing {@code SELECT 1}.
     */
    private Map<String, Object> checkDatabase() {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            rs.next();
            result.put("status", "UP");
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            result.put("status", "DOWN");
        }
        result.put("type", detectDatabaseType());
        return result;
    }

    /**
     * Detect the database type (H2 or PostgreSQL) from the JDBC connection
     * metadata. Falls back to "H2" if detection fails.
     */
    private String detectDatabaseType() {
        try (Connection conn = dataSource.getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName();
            if (productName != null && productName.toLowerCase().contains("postgresql")) {
                return "PostgreSQL";
            }
        } catch (Exception e) {
            log.warn("Could not detect database type: {}", e.getMessage());
        }
        return "H2";
    }

    /**
     * Check Flyway migration state.
     * <ul>
     *   <li>MIGRATED -- current version exists and no pending migrations</li>
     *   <li>PENDING -- current version exists but pending migrations remain, or
     *       no migrations have been applied yet</li>
     *   <li>UNAVAILABLE -- Flyway bean is not present or an error occurred</li>
     * </ul>
     */
    private Map<String, Object> checkFlyway() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (flyway == null) {
            result.put("status", "UNAVAILABLE");
            return result;
        }
        try {
            MigrationInfo current = flyway.info().current();
            MigrationInfo[] pending = flyway.info().pending();
            if (current != null) {
                if (pending.length == 0) {
                    result.put("status", "MIGRATED");
                } else {
                    result.put("status", "PENDING");
                }
                result.put("version", current.getVersion().getVersion());
            } else {
                MigrationInfo[] applied = flyway.info().applied();
                if (applied.length > 0) {
                    result.put("status", "PENDING");
                    result.put("version", applied[applied.length - 1].getVersion().getVersion());
                } else {
                    result.put("status", "PENDING");
                }
            }
        } catch (Exception e) {
            log.warn("Flyway health check failed: {}", e.getMessage());
            result.put("status", "UNAVAILABLE");
        }
        return result;
    }

    /**
     * Check the runtime collection registry.
     */
    private Map<String, Object> checkRuntimeRegistry() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int count = collectionRuntimeService.getCollectionNames().size();
            result.put("collectionCount", count);
            if (count > 0) {
                result.put("status", "LOADED");
            } else {
                result.put("status", "EMPTY");
            }
        } catch (Exception e) {
            log.warn("Runtime registry health check failed: {}", e.getMessage());
            result.put("status", "UNAVAILABLE");
            result.put("collectionCount", 0);
        }
        return result;
    }

    /**
     * Summarise external data sources. Never exposes URLs, usernames, or passwords.
     */
    private Map<String, Object> checkExternalDataSources() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> allSources = dataSourceConfigService.listAll();
            List<Map<String, Object>> sources = new ArrayList<>();
            boolean allAvailable = true;

            for (Map<String, Object> ds : allSources) {
                String key = (String) ds.get("key");
                boolean enabled = Boolean.TRUE.equals(ds.get("enabled"));
                boolean available = enabled && dataSourceResolver.isAvailable(key);

                Map<String, Object> sourceInfo = new LinkedHashMap<>();
                sourceInfo.put("key", key);
                sourceInfo.put("displayName", ds.get("displayName"));
                sourceInfo.put("type", dialectToType((String) ds.get("dialect")));
                sourceInfo.put("enabled", enabled);
                sourceInfo.put("available", available);
                sources.add(sourceInfo);

                if (enabled && !available) {
                    allAvailable = false;
                }
            }

            result.put("status", allAvailable ? "UP" : "DOWN");
            result.put("count", sources.size());
            result.put("sources", sources);
        } catch (Exception e) {
            log.warn("External data sources health check failed: {}", e.getMessage());
            result.put("status", "UNAVAILABLE");
            result.put("count", 0);
            result.put("sources", List.of());
        }
        return result;
    }

    private String dialectToType(String dialect) {
        if (dialect == null) {
            return "unknown";
        }
        return switch (dialect.toLowerCase()) {
            case "postgresql" -> "PostgreSQL";
            case "h2" -> "H2";
            default -> dialect;
        };
    }
}