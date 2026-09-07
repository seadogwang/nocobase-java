package com.nocobase.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProductionConfigGuard}.
 * <p>
 * Covers P0-A (data source encryption master key) and P0-B (tightened
 * guard coverage and startup wiring).
 */
class ProductionConfigGuardTest {

    // ── Constants ────────────────────────────────────────────────────────

    /** Valid 32-byte key: "test-key-for-AES-256-GCM-mode!!!" */
    private static final String VALID_32_BYTE_KEY =
            "dGVzdC1rZXktZm9yLUFFUy0yNTYtR0NNLW1vZGUhISE=";

    /** Old hardcoded dev default key (valid 32-byte base64, must be blocked in non-dev profiles) */
    private static final String KNOWN_DEV_DEFAULT_KEY =
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUy0yNTYhISEhISE=";

    /** Actual dev key from application-dev.yml (AES256, 32 bytes) */
    private static final String ACTUAL_DEV_KEY =
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhISE=";

    /** Old invalid dev key (AES256, 31 bytes) */
    private static final String OLD_INVALID_DEV_KEY =
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhIQ==";

    private static final String INVALID_BASE64_KEY = "this-is-not-valid-base64!!!";

    /** 16-byte key (too short): "1234567890123456" */
    private static final String SHORT_KEY_16_BYTES = "MTIzNDU2Nzg5MDEyMzQ1Ng==";

    private static final String STRONG_JWT_SECRET =
            "this-is-a-valid-jwt-secret-for-testing-minimum-32-bytes";

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Creates a mock event with no active profiles.
     */
    private static ApplicationEnvironmentPreparedEvent event(String... keyValuePairs) {
        return eventWithProfiles(new String[0], keyValuePairs);
    }

    /**
     * Creates a mock event with the given active profiles.
     */
    private static ApplicationEnvironmentPreparedEvent eventWithProfiles(
            String[] activeProfiles, String... keyValuePairs) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            env.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(),
                new org.springframework.boot.SpringApplication(), new String[0], env);
    }

    private static ProductionConfigGuard guard() {
        return new ProductionConfigGuard();
    }

    // ── P0-B: Spring Factories loading ───────────────────────────────────

    @Test
    @DisplayName("P0-B: spring.factories loads ProductionConfigGuard as ApplicationListener")
    void springFactoriesLoadsGuard() throws Exception {
        InputStream is = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring.factories");
        assertNotNull(is, "META-INF/spring.factories must exist on classpath");

        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        boolean found = false;
        while ((line = reader.readLine()) != null) {
            if (line.contains("ProductionConfigGuard")) {
                found = true;
                break;
            }
        }
        reader.close();

        assertTrue(found, "META-INF/spring.factories must register ProductionConfigGuard");
    }

    // ── P0-A: Data source encryption master key ──────────────────────────

    @Nested
    @DisplayName("P0-A: Data source encryption master key checks")
    class DataSourceEncryptionMasterKey {

        @Test
        @DisplayName("Missing master key → fail-fast")
        void missingMasterKey_fails() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            assertTrue(ex.getMessage().contains("master key"),
                    "Error should mention master key");
            assertTrue(ex.getMessage().contains("not configured"),
                    "Error should indicate key is not configured");
        }

        @Test
        @DisplayName("Invalid base64 master key → fail-fast")
        void invalidBase64MasterKey_fails() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", INVALID_BASE64_KEY);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            assertTrue(ex.getMessage().contains("base64"),
                    "Error should mention base64 encoding");
        }

        @Test
        @DisplayName("Insufficient length master key (16 bytes) → fail-fast")
        void insufficientLengthMasterKey_fails() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", SHORT_KEY_16_BYTES);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            assertTrue(ex.getMessage().contains("32"),
                    "Error should mention expected 32 bytes");
            assertTrue(ex.getMessage().contains("16"),
                    "Error should mention actual byte count");
        }

        @Test
        @DisplayName("Known dev default master key in non-dev profile → fail-fast")
        void devDefaultKeyInNonDevProfile_fails() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", KNOWN_DEV_DEFAULT_KEY);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            // The known-default check fires before the length check for non-dev profiles
            assertTrue(ex.getMessage().contains("known default"),
                    "Error should mention known default");
        }

        @Test
        @DisplayName("Known dev default master key in dev profile → passes")
        void devDefaultKeyInDevProfile_passes() {
            // In dev profile, the known-default check is skipped, and the
            // old dev default key is a valid 32-byte key, so it passes.
            ApplicationEnvironmentPreparedEvent evt = eventWithProfiles(
                    new String[]{"dev"},
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", KNOWN_DEV_DEFAULT_KEY);

            assertDoesNotThrow(() -> guard().onApplicationEvent(evt));
        }

        @Test
        @DisplayName("Valid 32-byte key in non-dev profile → passes")
        void valid32ByteKey_passes() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", VALID_32_BYTE_KEY);

            assertDoesNotThrow(() -> guard().onApplicationEvent(evt));
        }

        @Test
        @DisplayName("P0-A: Actual dev key from application-dev.yml is 32 bytes")
        void actualDevKeyIs32Bytes() {
            byte[] decoded = Base64.getDecoder().decode(ACTUAL_DEV_KEY);
            assertEquals(32, decoded.length,
                    "Actual dev key from application-dev.yml must decode to 32 bytes");
        }

        @Test
        @DisplayName("P0-A: Actual dev key in non-dev profile → fail-fast")
        void actualDevKeyInNonDevProfile_fails() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", ACTUAL_DEV_KEY);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            assertTrue(ex.getMessage().contains("known default"),
                    "Error should mention known default for actual dev key");
        }

        @Test
        @DisplayName("P0-A: Actual dev key in dev profile → passes")
        void actualDevKeyInDevProfile_passes() {
            ApplicationEnvironmentPreparedEvent evt = eventWithProfiles(
                    new String[]{"dev"},
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", ACTUAL_DEV_KEY);

            assertDoesNotThrow(() -> guard().onApplicationEvent(evt));
        }

        @Test
        @DisplayName("P0-A: Old invalid dev key (31 bytes) → fail-fast (known-default or length error)")
        void oldInvalidDevKey_failsWithLengthError() {
            // The old 31-byte key is in KNOWN_DEFAULT_MASTER_KEYS, so the
            // known-default check fires before the length check. Accept either
            // error message.
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", OLD_INVALID_DEV_KEY);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            String msg = ex.getMessage();
            assertTrue(msg.contains("known default") || msg.contains("32") || msg.contains("31"),
                    "Error should mention known default or byte count. Actual: " + msg);
        }
    }

    // ── P0-B: Existing guard checks (JWT, H2 console, ddl-auto) ─────────

    @Nested
    @DisplayName("P0-B: Existing guard checks")
    class ExistingGuardChecks {

        @Test
        @DisplayName("Missing JWT secret → fail-fast")
        void missingJwtSecret_fails() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.data-source-encryption.master-key", VALID_32_BYTE_KEY);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            assertTrue(ex.getMessage().contains("JWT secret"),
                    "Error should mention JWT secret");
            assertTrue(ex.getMessage().contains("not configured"),
                    "Error should indicate secret is not configured");
        }

        @Test
        @DisplayName("Weak/known default JWT secret → fail-fast")
        void weakJwtSecret_fails() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret",
                    "nocobase-java-secret-key-change-in-production-must-be-at-least-256-bits-long",
                    "nocobase.data-source-encryption.master-key", VALID_32_BYTE_KEY);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            assertTrue(ex.getMessage().contains("default"),
                    "Error should mention default/weak value");
        }

        @Test
        @DisplayName("H2 Console enabled in non-dev profile → fail-fast")
        void h2ConsoleEnabledInNonDev_fails() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", VALID_32_BYTE_KEY,
                    "spring.h2.console.enabled", "true");

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            assertTrue(ex.getMessage().contains("H2 Console"),
                    "Error should mention H2 Console");
        }

        @Test
        @DisplayName("ddl-auto=update in non-test profile → fail-fast")
        void ddlAutoUpdate_fails() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", VALID_32_BYTE_KEY,
                    "spring.jpa.hibernate.ddl-auto", "update");

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> guard().onApplicationEvent(evt));
            assertTrue(ex.getMessage().contains("ddl-auto"),
                    "Error should mention ddl-auto");
            assertTrue(ex.getMessage().contains("update"),
                    "Error should mention the destructive value");
        }

        @Test
        @DisplayName("Test profile skips all checks")
        void testProfileSkipsAllChecks() {
            // No JWT secret, no master key, H2 console enabled, ddl-auto=update
            // All should be skipped because test profile is active
            ApplicationEnvironmentPreparedEvent evt = eventWithProfiles(
                    new String[]{"test"},
                    "spring.h2.console.enabled", "true",
                    "spring.jpa.hibernate.ddl-auto", "update");

            assertDoesNotThrow(() -> guard().onApplicationEvent(evt));
        }

        @Test
        @DisplayName("All valid config → passes")
        void allValidConfig_passes() {
            ApplicationEnvironmentPreparedEvent evt = event(
                    "nocobase.jwt.secret", STRONG_JWT_SECRET,
                    "nocobase.data-source-encryption.master-key", VALID_32_BYTE_KEY,
                    "spring.h2.console.enabled", "false",
                    "spring.jpa.hibernate.ddl-auto", "none");

            assertDoesNotThrow(() -> guard().onApplicationEvent(evt));
        }
    }
}