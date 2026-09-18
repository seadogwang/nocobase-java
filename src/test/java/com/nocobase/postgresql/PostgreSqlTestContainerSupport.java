package com.nocobase.postgresql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class that auto-starts a PostgreSQL container via Testcontainers
 * and sets the required system properties and environment variables so that
 * {@link PostgreSqlIntegrationTest} can run against a real PostgreSQL instance.
 *
 * <p>Usage:
 * <pre>
 * class PostgreSqlIntegrationTest extends PostgreSqlTestContainerSupport { ... }
 * </pre>
 *
 * <p>The container is shared across all tests in the class (started once
 * before any test, stopped once after all tests).
 */
public abstract class PostgreSqlTestContainerSupport {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlTestContainerSupport.class);

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test");
    }

    private static final String EXTERNAL_PG_PROPERTY = "postgresql.external.pg";

    @BeforeAll
    static void startContainer() {
        boolean externalPg = "true".equals(System.getProperty(EXTERNAL_PG_PROPERTY));

        if (externalPg) {
            // External PG mode: validate all required env vars are present
            String pgUrl = System.getProperty("PG_URL");
            String pgUsername = System.getProperty("PG_USERNAME");
            String pgPassword = System.getProperty("PG_PASSWORD");

            if (pgUrl == null || pgUrl.isBlank()
                    || pgUsername == null || pgUsername.isBlank()
                    || pgPassword == null || pgPassword.isBlank()) {
                throw new IllegalStateException(
                    "External PostgreSQL mode requires PG_URL, PG_USERNAME, and PG_PASSWORD system properties. "
                    + "Missing: "
                    + (pgUrl == null || pgUrl.isBlank() ? "PG_URL " : "")
                    + (pgUsername == null || pgUsername.isBlank() ? "PG_USERNAME " : "")
                    + (pgPassword == null || pgPassword.isBlank() ? "PG_PASSWORD" : ""));
            }

            System.setProperty("postgresql.acceptance", "true");
            log.info("External PostgreSQL mode active (Testcontainers disabled)");
            return;
        }

        // Default Testcontainers mode
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();

            // Set system property so the acceptance check in PostgreSqlIntegrationTest passes
            System.setProperty("postgresql.acceptance", "true");

            // Set environment variables that PostgreSqlIntegrationTest reads
            System.setProperty("PG_URL", POSTGRES.getJdbcUrl());
            System.setProperty("PG_USERNAME", POSTGRES.getUsername());
            System.setProperty("PG_PASSWORD", POSTGRES.getPassword());

            log.info("PostgreSQL container started (image: {})",
                    POSTGRES.getDockerImageName());
        }
    }

    @AfterAll
    static void stopContainer() {
        // Container is stopped by the JVM shutdown hook automatically,
        // but we clean up system properties to avoid leakage.
        System.clearProperty("postgresql.acceptance");
        System.clearProperty("PG_URL");
        System.clearProperty("PG_USERNAME");
        System.clearProperty("PG_PASSWORD");
    }
}