package com.nocobase.sql;

import com.nocobase.config.NocobaseDataSourceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SqlDataSourceResolver}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "nocobase.data-sources.h2-auto.url=jdbc:h2:mem:autods;DB_CLOSE_DELAY=-1",
        "nocobase.data-sources.h2-auto.driver-class-name=org.h2.Driver",
        "nocobase.data-sources.h2-auto.username=sa",
        "nocobase.data-sources.h2-auto.password=",
        "nocobase.data-sources.h2-auto.enabled=true",
        "nocobase.data-sources.unknown-auto.url=jdbc:unknown://localhost:9999/test",
        "nocobase.data-sources.unknown-auto.driver-class-name=com.example.Driver",
        "nocobase.data-sources.unknown-auto.username=sa",
        "nocobase.data-sources.unknown-auto.password=",
        "nocobase.data-sources.unknown-auto.enabled=true"
})
class SqlDataSourceResolverTest {

    @Autowired
    private SqlDataSourceResolver resolver;

    @Autowired
    private NocobaseDataSourceProperties dataSourceProperties;

    @Test
    @DisplayName("resolve 'main' key returns non-null JdbcTemplate")
    void resolveMainKeyReturnsJdbcTemplate() {
        assertNotNull(resolver.resolve("main"),
                "Resolving 'main' should return a non-null JdbcTemplate");
    }

    @Test
    @DisplayName("resolve null key defaults to main and returns non-null JdbcTemplate")
    void resolveNullKeyDefaultsToMain() {
        assertNotNull(resolver.resolve(null),
                "Resolving null should default to main and return a non-null JdbcTemplate");
    }

    @Test
    @DisplayName("resolve non-main key throws UnsupportedOperationException with clear message")
    void resolveNonMainKeyThrowsUnsupportedOperationException() {
        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> resolver.resolve("analytics"),
                "Resolving non-main key should throw UnsupportedOperationException");

        assertTrue(ex.getMessage().contains("analytics"),
                "Error message should contain the unsupported key name");
        assertTrue(ex.getMessage().contains("not configured"),
                "Error message should clearly state that the key is not configured");
    }

    @Test
    @DisplayName("resolve empty key defaults to main")
    void resolveEmptyKeyDefaultsToMain() {
        assertNotNull(resolver.resolve(""),
                "Resolving empty key should default to main and return a non-null JdbcTemplate");
    }

    @Test
    @DisplayName("resolve 'default' key is normalized to main (deprecated alias)")
    void resolveDefaultKeyNormalizedToMain() {
        assertNotNull(resolver.resolve("default"),
                "Resolving 'default' should be normalized to main and return a non-null JdbcTemplate");
    }

    // ═══════════════════════════════════════════════════════════════════
    // P1-F: Dialect selection tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1-F: dialect=mysql is rejected with clear error")
    void mysqlDialectIsRejected() {
        // Add a datasource with unsupported dialect at runtime
        NocobaseDataSourceProperties.DataSourceConfig config =
                new NocobaseDataSourceProperties.DataSourceConfig();
        config.setUrl("jdbc:mysql://localhost:3306/test");
        config.setDialect("mysql");
        config.setEnabled(true);
        dataSourceProperties.getDataSources().put("mysql-ds", config);

        try {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> resolver.resolveDialect("mysql-ds"),
                    "dialect=mysql should be rejected as unsupported");

            assertTrue(ex.getMessage().contains("mysql-ds"),
                    "Error message should contain the data source key");
            assertTrue(ex.getMessage().contains("unsupported dialect"),
                    "Error message should state the dialect is unsupported");
            assertTrue(ex.getMessage().contains("mysql"),
                    "Error message should mention the unsupported dialect name");
        } finally {
            dataSourceProperties.getDataSources().remove("mysql-ds");
        }
    }

    @Test
    @DisplayName("P1-F: no dialect but H2 URL derives H2 dialect")
    void h2UrlDerivesH2Dialect() {
        SqlDialect dialect = resolver.resolveDialect("h2-auto");
        assertNotNull(dialect, "Dialect should not be null");
        assertInstanceOf(H2SqlDialect.class, dialect,
                "H2 URL should derive H2SqlDialect");
    }

    @Test
    @DisplayName("P1-F: no dialect and unknown URL is rejected")
    void unknownUrlIsRejected() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveDialect("unknown-auto"),
                "Unknown URL without dialect should be rejected");

        assertTrue(ex.getMessage().contains("unknown-auto"),
                "Error message should contain the data source key");
        assertTrue(ex.getMessage().contains("dialect"),
                "Error message should guide user to configure dialect");
    }

    @Test
    @DisplayName("P1-F: main data source resolves dialect successfully")
    void mainDataSourceResolvesDialect() {
        SqlDialect dialect = resolver.resolveDialect("main");
        assertNotNull(dialect, "Main data source dialect should not be null");
        assertInstanceOf(H2SqlDialect.class, dialect,
                "Main data source (H2 test) should resolve to H2SqlDialect");
    }
}