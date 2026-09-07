package com.nocobase.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NocobaseDataSourceProperties}:
 * <ul>
 *   <li>Only spring.datasource configured -- main exists</li>
 *   <li>Explicit "analytics" config -- properties readable</li>
 *   <li>Invalid key format -- rejected</li>
 *   <li>Disabled key -- marked unavailable</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "nocobase.data-sources.analytics.url=jdbc:h2:mem:analytics",
        "nocobase.data-sources.analytics.driver-class-name=org.h2.Driver",
        "nocobase.data-sources.analytics.username=sa",
        "nocobase.data-sources.analytics.password=",
        "nocobase.data-sources.analytics.dialect=h2",
        "nocobase.data-sources.analytics.enabled=true",
        "nocobase.data-sources.analytics.read-only=true"
})
class NocobaseDataSourcePropertiesTest {

    @Autowired
    private NocobaseDataSourceProperties properties;

    // ── Spring Boot integration tests ──────────────────────────────────

    @Test
    @DisplayName("Only spring.datasource -- main exists")
    void onlySpringDatasource_mainExists() {
        assertNotNull(properties.getMain(), "main datasource should be auto-created from spring.datasource");
        assertTrue(properties.getMain().isEnabled(), "main datasource should be enabled");
        assertFalse(properties.getMain().isReadOnly(), "main datasource should be writable");
        assertNotNull(properties.getMain().getUrl(), "main datasource URL should not be null");
        assertNotNull(properties.getMain().getDriverClassName(), "main datasource driver should not be null");
    }

    @Test
    @DisplayName("Configured analytics -- properties readable")
    void configuredAnalytics_propertiesReadable() {
        NocobaseDataSourceProperties.DataSourceConfig analytics = properties.getDataSource("analytics");
        assertNotNull(analytics, "analytics datasource should be configured");
        assertEquals("jdbc:h2:mem:analytics", analytics.getUrl());
        assertEquals("org.h2.Driver", analytics.getDriverClassName());
        assertEquals("sa", analytics.getUsername());
        assertEquals("h2", analytics.getDialect());
        assertTrue(analytics.isEnabled());
        assertTrue(analytics.isReadOnly());
    }

    // ── Unit tests (direct instantiation) ──────────────────────────────

    @Test
    @DisplayName("Invalid key format -- rejected")
    void invalidKeyFormat_rejected() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        dataSources.put("invalid key!", new NocobaseDataSourceProperties.DataSourceConfig());
        props.setDataSources(dataSources);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.validateKeys());
        assertTrue(ex.getMessage().contains("invalid key!"),
                "Error message should mention the invalid key");
    }

    @Test
    @DisplayName("Reserved key 'main' -- rejected")
    void reservedKeyMain_rejected() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        dataSources.put("main", new NocobaseDataSourceProperties.DataSourceConfig());
        props.setDataSources(dataSources);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.validateKeys());
        assertTrue(ex.getMessage().contains("main"),
                "Error message should mention 'main'");
        assertTrue(ex.getMessage().contains("reserved"),
                "Error message should mention 'reserved'");
    }

    @Test
    @DisplayName("Disabled key -- marked unavailable")
    void disabledKey_markedUnavailable() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig disabledConfig =
                new NocobaseDataSourceProperties.DataSourceConfig();
        disabledConfig.setEnabled(false);
        dataSources.put("disabled-ds", disabledConfig);
        props.setDataSources(dataSources);

        // Validation should pass (disabled is a valid configuration)
        assertDoesNotThrow(() -> props.validateKeys());

        // isEnabled() should return false
        assertFalse(props.isEnabled("disabled-ds"),
                "disabled-ds should not be considered enabled");
        assertNotNull(props.getDataSource("disabled-ds"),
                "disabled-ds should still be accessible via getDataSource");
        assertFalse(props.getDataSource("disabled-ds").isEnabled(),
                "disabled-ds config should have enabled=false");
    }

    @Test
    @DisplayName("Key with numbers and hyphens -- accepted")
    void keyWithNumbersAndHyphens_accepted() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        dataSources.put("my-ds-2", new NocobaseDataSourceProperties.DataSourceConfig());
        dataSources.put("reporting_2024", new NocobaseDataSourceProperties.DataSourceConfig());
        props.setDataSources(dataSources);

        assertDoesNotThrow(() -> props.validateKeys());
    }

    @Test
    @DisplayName("Key starting with number -- rejected")
    void keyStartingWithNumber_rejected() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        dataSources.put("1invalid", new NocobaseDataSourceProperties.DataSourceConfig());
        props.setDataSources(dataSources);

        assertThrows(IllegalStateException.class, () -> props.validateKeys());
    }

    // ── P0-D: External datasource config validation ──────────────────────

    @Test
    @DisplayName("P0-D: Missing URL -- rejected")
    void missingUrl_rejected() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        // URL is null/empty
        config.setDriverClassName("org.h2.Driver");
        config.setDialect("h2");
        dataSources.put("no-url", config);
        props.setDataSources(dataSources);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.validateConfigFields());
        assertTrue(ex.getMessage().contains("url"),
                "Error message should mention 'url'");
        assertTrue(ex.getMessage().contains("no-url"),
                "Error message should contain the data source key");
    }

    @Test
    @DisplayName("P0-D: Unknown dialect -- rejected")
    void unknownDialect_rejected() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:mysql://localhost:3306/db");
        config.setDialect("mysql");
        dataSources.put("mysql-ds", config);
        props.setDataSources(dataSources);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.validateConfigFields());
        assertTrue(ex.getMessage().contains("mysql"),
                "Error message should contain the unsupported dialect value");
        assertTrue(ex.getMessage().contains("h2") || ex.getMessage().contains("postgresql"),
                "Error message should mention supported dialects");
        assertTrue(ex.getMessage().contains("mysql-ds"),
                "Error message should contain the data source key");
    }

    @Test
    @DisplayName("P0-D: Valid h2 dialect -- accepted")
    void validH2Dialect_accepted() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:h2:mem:test");
        config.setDialect("h2");
        config.setDriverClassName("org.h2.Driver");
        dataSources.put("h2-ds", config);
        props.setDataSources(dataSources);

        assertDoesNotThrow(() -> props.validateConfigFields());
    }

    @Test
    @DisplayName("P0-D: Valid postgresql dialect -- accepted")
    void validPostgresqlDialect_accepted() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:postgresql://localhost:5432/db");
        config.setDialect("postgresql");
        config.setDriverClassName("org.postgresql.Driver");
        dataSources.put("pg-ds", config);
        props.setDataSources(dataSources);

        assertDoesNotThrow(() -> props.validateConfigFields());
    }

    // ── P1-G: readOnly enforcement ──────────────────────────────────────

    @Test
    @DisplayName("P1-G: External datasource with readOnly=false -- rejected")
    void externalDataSourceReadOnlyFalse_rejected() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:h2:mem:test");
        config.setDriverClassName("org.h2.Driver");
        config.setDialect("h2");
        config.setReadOnly(false); // should be rejected
        dataSources.put("readonly-test", config);
        props.setDataSources(dataSources);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.validateConfigFields());
        assertTrue(ex.getMessage().contains("read-only"),
                "Error message should mention read-only");
    }

    @Test
    @DisplayName("P1-G: External datasource with readOnly=true -- accepted")
    void externalDataSourceReadOnlyTrue_accepted() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:h2:mem:test");
        config.setDriverClassName("org.h2.Driver");
        config.setDialect("h2");
        config.setReadOnly(true); // default, should be accepted
        dataSources.put("readonly-ok", config);
        props.setDataSources(dataSources);

        assertDoesNotThrow(() -> props.validateConfigFields());
    }

    // ── P1-G: driverClassName validation ────────────────────────────────

    @Test
    @DisplayName("P1-G: Missing driverClassName with recognizable URL -- accepted (derivable)")
    void missingDriverClassNameWithRecognizableUrl_accepted() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:h2:mem:test");
        config.setDialect("h2");
        // driverClassName not set but derivable from URL — should be accepted
        dataSources.put("derived-driver", config);
        props.setDataSources(dataSources);

        assertDoesNotThrow(() -> props.validateConfigFields());
    }

    @Test
    @DisplayName("P1-G: Missing driverClassName with unrecognizable URL -- rejected")
    void missingDriverClassNameWithUnrecognizableUrl_rejected() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:unknown://localhost:9999/db");
        config.setDialect("h2");
        // driverClassName not set and not derivable — should be rejected
        dataSources.put("unknown-driver", config);
        props.setDataSources(dataSources);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.validateConfigFields());
        assertTrue(ex.getMessage().contains("driver-class-name"),
                "Error message should mention driver-class-name");
    }

    @Test
    @DisplayName("P1-G: Mismatched driverClassName -- rejected")
    void mismatchedDriverClassName_rejected() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:postgresql://localhost:5432/db");
        config.setDriverClassName("org.h2.Driver"); // mismatch with postgresql URL
        config.setDialect("postgresql");
        dataSources.put("mismatched-driver", config);
        props.setDataSources(dataSources);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.validateConfigFields());
        assertTrue(ex.getMessage().contains("driver-class-name"),
                "Error message should mention driver-class-name");
    }

    // ── P1-G: deriveDriverClassName ─────────────────────────────────────

    @Test
    @DisplayName("P1-G: deriveDriverClassName from H2 URL")
    void deriveDriverClassNameH2() {
        assertEquals("org.h2.Driver",
                NocobaseDataSourceProperties.deriveDriverClassName("jdbc:h2:mem:test"));
        assertEquals("org.h2.Driver",
                NocobaseDataSourceProperties.deriveDriverClassName("jdbc:h2:file:./db"));
    }

    @Test
    @DisplayName("P1-G: deriveDriverClassName from PostgreSQL URL")
    void deriveDriverClassNamePostgresql() {
        assertEquals("org.postgresql.Driver",
                NocobaseDataSourceProperties.deriveDriverClassName("jdbc:postgresql://localhost:5432/db"));
    }

    @Test
    @DisplayName("P1-G: deriveDriverClassName from unknown URL returns null")
    void deriveDriverClassNameUnknown() {
        assertNull(NocobaseDataSourceProperties.deriveDriverClassName("jdbc:mysql://localhost:3306/db"));
        assertNull(NocobaseDataSourceProperties.deriveDriverClassName(null));
        assertNull(NocobaseDataSourceProperties.deriveDriverClassName("not-a-jdbc-url"));
    }

    @Test
    @DisplayName("P0-D: Error messages do NOT contain JDBC URL, username, or password")
    void errorMessagesDoNotContainSensitiveData() {
        NocobaseDataSourceProperties props = new NocobaseDataSourceProperties();
        Map<String, NocobaseDataSourceProperties.DataSourceConfig> dataSources = new LinkedHashMap<>();
        NocobaseDataSourceProperties.DataSourceConfig config = new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:postgresql://secret-host:5432/db");
        config.setUsername("admin");
        config.setPassword("super-secret");
        config.setDialect("mysql"); // invalid dialect to trigger error
        dataSources.put("sensitive-ds", config);
        props.setDataSources(dataSources);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props.validateConfigFields());
        String msg = ex.getMessage();
        assertFalse(msg.contains("jdbc:"), "Error should not contain JDBC URL");
        assertFalse(msg.contains("secret-host"), "Error should not contain hostname");
        assertFalse(msg.contains("admin"), "Error should not contain username");
        assertFalse(msg.contains("super-secret"), "Error should not contain password");
        assertFalse(msg.contains("password"), "Error should not contain the word 'password'");
    }
}