package com.nocobase.postgresql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

/**
 * Base class that provisions a real PostgreSQL instance and wires the Spring
 * context's main datasource to it, so that:
 * <ul>
 *   <li>Flyway migrations run against real PostgreSQL (not H2), validating
 *       the migrations and producing the metadata schema the acceptance tests
 *       assert on;</li>
 *   <li>JPA/JdbcTemplate operate on PostgreSQL;</li>
 *   <li>{@link PostgreSqlIntegrationTest} can connect via {@code DriverManager}
 *       using the same {@code PG_URL}/{@code PG_USERNAME}/{@code PG_PASSWORD}
 *       it always has.</li>
 * </ul>
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>External</b> ({@code -Dpostgresql.external.pg=true} with
 *       {@code PG_URL}/{@code PG_USERNAME}/{@code PG_PASSWORD} system
 *       properties): use a pre-provisioned PostgreSQL (e.g. a local install).
 *       No Docker required.</li>
 *   <li><b>Testcontainers</b> (default): start {@code postgres:15-alpine}.
 *       Requires Docker.</li>
 * </ul>
 *
 * <p>Connection info is resolved in a static initializer (and the container, if
 * any, is started there) so that it is available to {@link DynamicPropertySource}
 * <em>before</em> the Spring context is built — Flyway then migrates the real
 * PostgreSQL during context startup.
 */
public abstract class PostgreSqlTestContainerSupport {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlTestContainerSupport.class);

    private static final String EXTERNAL_PG_PROPERTY = "postgresql.external.pg";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    private static final String RESOLVED_URL;
    private static final String RESOLVED_USERNAME;
    private static final String RESOLVED_PASSWORD;

    static {
        boolean externalPg = "true".equals(System.getProperty(EXTERNAL_PG_PROPERTY));
        if (externalPg) {
            String pgUrl = System.getProperty("PG_URL", System.getenv("PG_URL"));
            String pgUsername = System.getProperty("PG_USERNAME", System.getenv("PG_USERNAME"));
            String pgPassword = System.getProperty("PG_PASSWORD", System.getenv("PG_PASSWORD"));
            List<String> missing = PostgreSqlMode.missingExternalPgCredentials(pgUrl, pgUsername, pgPassword);
            if (!missing.isEmpty()) {
                // Report only the missing variable NAMES — never their values.
                throw new IllegalStateException(
                        "External PostgreSQL mode requires PG_URL, PG_USERNAME, and PG_PASSWORD. "
                                + "Missing: " + String.join(", ", missing));
            }
            RESOLVED_URL = pgUrl;
            RESOLVED_USERNAME = pgUsername;
            RESOLVED_PASSWORD = pgPassword;
            System.setProperty("postgresql.acceptance", "true");
            log.info("External PostgreSQL mode active (Testcontainers disabled)");
        } else {
            // Default Testcontainers mode — start the container in the static
            // initializer so its JDBC URL is available to @DynamicPropertySource
            // before the Spring context is built.
            if (!POSTGRES.isRunning()) {
                POSTGRES.start();
            }
            RESOLVED_URL = POSTGRES.getJdbcUrl();
            RESOLVED_USERNAME = POSTGRES.getUsername();
            RESOLVED_PASSWORD = POSTGRES.getPassword();
            System.setProperty("postgresql.acceptance", "true");
            System.setProperty("PG_URL", RESOLVED_URL);
            System.setProperty("PG_USERNAME", RESOLVED_USERNAME);
            System.setProperty("PG_PASSWORD", RESOLVED_PASSWORD);
            log.info("PostgreSQL container started (image: {})", POSTGRES.getDockerImageName());
        }
    }

    /**
     * Wire the Spring main datasource to the resolved PostgreSQL so Flyway
     * migrates it and JPA operates on real PostgreSQL during acceptance.
     */
    @DynamicPropertySource
    static void registerPostgresDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> RESOLVED_URL);
        registry.add("spring.datasource.username", () -> RESOLVED_USERNAME);
        registry.add("spring.datasource.password", () -> RESOLVED_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway owns the schema; Hibernate must not create/drop.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
    }

    @BeforeAll
    static void startContainer() {
        // Container (if any) is started in the static initializer; the
        // postgresql.acceptance flag and PG_* properties are already set.
        // This method is retained for the test lifecycle and any subclass hooks.
    }

    @AfterAll
    static void stopContainer() {
        // The Testcontainers JVM shutdown hook stops the container.
        // Clean up the system properties we set to avoid leakage between suites.
        System.clearProperty("postgresql.acceptance");
        // Only clear in container mode; in external mode the caller owns these.
        if (!"true".equals(System.getProperty(EXTERNAL_PG_PROPERTY))) {
            System.clearProperty("PG_URL");
            System.clearProperty("PG_USERNAME");
            System.clearProperty("PG_PASSWORD");
        }
    }
}
