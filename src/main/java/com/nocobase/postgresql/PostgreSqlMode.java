package com.nocobase.postgresql;

import java.util.ArrayList;
import java.util.List;

/**
 * Mode-selection and partial-config validation for PostgreSQL acceptance
 * tests. This is a pure utility class with NO static initializer, so it can
 * be unit-tested without triggering Testcontainer startup (unlike
 * {@link PostgreSqlTestContainerSupport}, whose static block starts a
 * container in default mode).
 *
 * <p>The decision and validation logic lives here so the support class and
 * the release-gate tooling share one source of truth, and so default and
 * external modes cannot silently switch.
 */
public final class PostgreSqlMode {

    /** System property that selects external-PG mode (vs default Testcontainers). */
    public static final String EXTERNAL_PG_PROPERTY = "postgresql.external.pg";

    private PostgreSqlMode() {
        // utility
    }

    /**
     * Whether the external-PG system property selects external mode.
     */
    public static boolean isExternalModeSelected() {
        return "true".equals(System.getProperty(EXTERNAL_PG_PROPERTY));
    }

    /**
     * Determine which external-PG credentials are missing. Returns the list of
     * missing variable NAMES — never their values — so failure output cannot
     * leak a partial JDBC URL or credential.
     *
     * @param url      the PG_URL value (may be null/blank)
     * @param username the PG_USERNAME value (may be null/blank)
     * @param password the PG_PASSWORD value (may be null/blank)
     * @return missing variable names in fixed order PG_URL, PG_USERNAME, PG_PASSWORD
     */
    public static List<String> missingExternalPgCredentials(String url, String username, String password) {
        List<String> missing = new ArrayList<>();
        if (url == null || url.isBlank()) {
            missing.add("PG_URL");
        }
        if (username == null || username.isBlank()) {
            missing.add("PG_USERNAME");
        }
        if (password == null || password.isBlank()) {
            missing.add("PG_PASSWORD");
        }
        return missing;
    }
}
