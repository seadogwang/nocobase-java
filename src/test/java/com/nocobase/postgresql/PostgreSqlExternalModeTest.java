package com.nocobase.postgresql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic mode-selection and partial-config validation for PostgreSQL
 * acceptance (Phase-21 Agent B).
 *
 * <p>Tests {@link PostgreSqlMode} directly — a pure utility with no static
 * initializer, so no Docker/Testcontainers is required and the test does not
 * trigger container startup. Verifies:
 * <ul>
 *   <li>partial external configuration reports only the missing variable
 *       NAMES, never their values (no JDBC URL or credential leakage);</li>
 *   <li>complete configuration produces an empty missing list;</li>
 *   <li>the mode-selection predicate reads the external-PG system property
 *       consistently (default = false when unset).</li>
 * </ul>
 *
 * <p>This complements the end-to-end external-PG run recorded in
 * {@code RELEASE_GATE_RESULT.md} (mode = "External PostgreSQL"), which is
 * produced by {@code scripts/release-gate.ps1 -RequireExternalPg} against a
 * real PostgreSQL instance.
 */
class PostgreSqlExternalModeTest {

    private static final String SAMPLE_URL =
            "jdbc:postgresql://localhost:5432/nocobase_pg_acceptance";
    private static final String SAMPLE_USER = "postgres";
    private static final String SAMPLE_PASS = "postgres";

    @Test
    void allMissingReturnsAllThreeNames() {
        List<String> missing = PostgreSqlMode.missingExternalPgCredentials(null, null, null);
        assertEquals(3, missing.size());
        assertTrue(missing.contains("PG_URL"));
        assertTrue(missing.contains("PG_USERNAME"));
        assertTrue(missing.contains("PG_PASSWORD"));
    }

    @Test
    void partialConfigReportsOnlyMissingNames() {
        // Only PG_URL provided -> PG_USERNAME and PG_PASSWORD missing.
        List<String> missing = PostgreSqlMode.missingExternalPgCredentials(
                SAMPLE_URL, null, null);
        assertEquals(2, missing.size());
        assertTrue(missing.contains("PG_USERNAME"));
        assertTrue(missing.contains("PG_PASSWORD"));
        assertFalse(missing.contains("PG_URL"));
    }

    @Test
    void partialConfigOutputNeverLeadsValues() {
        // The missing list must contain only variable names — never the URL,
        // username, or password values that WERE provided.
        String url = "jdbc:postgresql://secret-host:9999/secret_db?user=leak&password=leak";
        List<String> missing = PostgreSqlMode.missingExternalPgCredentials(url, null, null);
        String serialized = missing.toString();
        assertFalse(serialized.contains("secret-host"), "host must not leak: " + serialized);
        assertFalse(serialized.contains("9999"), "port must not leak: " + serialized);
        assertFalse(serialized.contains("secret_db"), "db must not leak: " + serialized);
        assertFalse(serialized.contains("leak"), "credentials must not leak: " + serialized);
    }

    @Test
    void completeConfigReturnsEmpty() {
        List<String> missing = PostgreSqlMode.missingExternalPgCredentials(
                SAMPLE_URL, SAMPLE_USER, SAMPLE_PASS);
        assertTrue(missing.isEmpty(), "complete config must produce no missing vars: " + missing);
    }

    @Test
    void blankValuesCountAsMissing() {
        List<String> missing = PostgreSqlMode.missingExternalPgCredentials("  ", "", "  ");
        assertEquals(3, missing.size());
    }

    @Test
    void modeSelectionReadsSystemProperty() {
        // In the default test run (no -Dpostgresql.external.pg=true), external
        // mode is NOT selected — default Testcontainers mode is the baseline.
        // Save and restore the property to avoid cross-test leakage.
        String previous = System.getProperty(PostgreSqlMode.EXTERNAL_PG_PROPERTY);
        try {
            System.clearProperty(PostgreSqlMode.EXTERNAL_PG_PROPERTY);
            assertFalse(PostgreSqlMode.isExternalModeSelected(),
                    "external mode must not be selected when the property is unset");
            System.setProperty(PostgreSqlMode.EXTERNAL_PG_PROPERTY, "true");
            assertTrue(PostgreSqlMode.isExternalModeSelected(),
                    "external mode must be selected when the property is 'true'");
            System.setProperty(PostgreSqlMode.EXTERNAL_PG_PROPERTY, "false");
            assertFalse(PostgreSqlMode.isExternalModeSelected(),
                    "external mode must not be selected when the property is 'false'");
        } finally {
            if (previous == null) {
                System.clearProperty(PostgreSqlMode.EXTERNAL_PG_PROPERTY);
            } else {
                System.setProperty(PostgreSqlMode.EXTERNAL_PG_PROPERTY, previous);
            }
        }
    }
}
