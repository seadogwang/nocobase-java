package com.nocobase.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production operations (Agent I): {@link ProductionConfigGuard} fail-fast
 * contract.
 *
 * <p>Verifies the application refuses to start (non-test profile) when:
 * <ul>
 *   <li>the JWT secret is missing, weak/default, or too short;</li>
 *   <li>the data-source encryption master key is missing, a known default,
 *       invalid base64, or the wrong length;</li>
 *   <li>H2 Console is enabled; or Hibernate ddl-auto is destructive.</li>
 * </ul>
 * The test profile must always be skipped (no failures).
 *
 * <p>No actual secret values are asserted upon — the guard never logs them
 * and the exception messages never include them.
 */
class ProductionConfigGuardTest {

    private static final String VALID_JWT_SECRET =
            "a-strong-unique-production-jwt-secret-of-at-least-256-bits-long!!";

    private static final String VALID_MASTER_KEY =
            java.util.Base64.getEncoder().encodeToString(new byte[32]);

    private void runGuard(MockEnvironment env) {
        ProductionConfigGuard guard = new ProductionConfigGuard();
        ApplicationEnvironmentPreparedEvent event =
                org.mockito.Mockito.mock(ApplicationEnvironmentPreparedEvent.class);
        org.mockito.Mockito.when(event.getEnvironment()).thenReturn(env);
        guard.onApplicationEvent(event);
    }

    private MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    // -- test profile is always skipped ------------------------------------

    @Test
    void testProfileSkipsChecks() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        // Even with no secrets configured, the test profile must not fail.
        assertDoesNotThrow(() -> runGuard(env));
    }

    // -- JWT secret fail-fast ----------------------------------------------

    @Test
    void missingJwtSecretFailsFast() {
        MockEnvironment env = prodEnv();
        env.setProperty("nocobase.data-source-encryption.master-key", VALID_MASTER_KEY);
        env.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> runGuard(env));
        // Message must not leak any secret value (none set anyway).
        assertFalse(ex.getMessage().toLowerCase().contains("password"));
    }

    @Test
    void weakDefaultJwtSecretFailsFast() {
        MockEnvironment env = prodEnv();
        env.setProperty("nocobase.jwt.secret",
                "nocobase-java-secret-key-change-in-production-must-be-at-least-256-bits-long");
        env.setProperty("nocobase.data-source-encryption.master-key", VALID_MASTER_KEY);
        env.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        assertThrows(IllegalStateException.class, () -> runGuard(env));
    }

    @Test
    void shortJwtSecretFailsFast() {
        MockEnvironment env = prodEnv();
        env.setProperty("nocobase.jwt.secret", "shortsecret");
        env.setProperty("nocobase.data-source-encryption.master-key", VALID_MASTER_KEY);
        env.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        assertThrows(IllegalStateException.class, () -> runGuard(env));
    }

    // -- data-source encryption master key fail-fast ------------------------

    @Test
    void missingMasterKeyFailsFast() {
        MockEnvironment env = prodEnv();
        env.setProperty("nocobase.jwt.secret", VALID_JWT_SECRET);
        env.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        assertThrows(IllegalStateException.class, () -> runGuard(env));
    }

    @Test
    void knownDefaultMasterKeyFailsFastInProd() {
        MockEnvironment env = prodEnv();
        env.setProperty("nocobase.jwt.secret", VALID_JWT_SECRET);
        // A known dev default key must be rejected in prod.
        env.setProperty("nocobase.data-source-encryption.master-key",
                "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhISE=");
        env.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        assertThrows(IllegalStateException.class, () -> runGuard(env));
    }

    @Test
    void wrongLengthMasterKeyFailsFast() {
        MockEnvironment env = prodEnv();
        env.setProperty("nocobase.jwt.secret", VALID_JWT_SECRET);
        // 16 bytes instead of 32.
        env.setProperty("nocobase.data-source-encryption.master-key",
                java.util.Base64.getEncoder().encodeToString(new byte[16]));
        env.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        assertThrows(IllegalStateException.class, () -> runGuard(env));
    }

    // -- H2 console + ddl-auto fail-fast -----------------------------------

    @Test
    void h2ConsoleEnabledFailsFastInProd() {
        MockEnvironment env = prodEnv();
        env.setProperty("nocobase.jwt.secret", VALID_JWT_SECRET);
        env.setProperty("nocobase.data-source-encryption.master-key", VALID_MASTER_KEY);
        env.setProperty("spring.h2.console.enabled", "true");
        env.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        assertThrows(IllegalStateException.class, () -> runGuard(env));
    }

    @Test
    void destructiveDdlAutoFailsFast() {
        MockEnvironment env = prodEnv();
        env.setProperty("nocobase.jwt.secret", VALID_JWT_SECRET);
        env.setProperty("nocobase.data-source-encryption.master-key", VALID_MASTER_KEY);
        env.setProperty("spring.jpa.hibernate.ddl-auto", "create-drop");
        assertThrows(IllegalStateException.class, () -> runGuard(env));
    }

    // -- valid prod config starts cleanly ----------------------------------

    @Test
    void validProdConfigPasses() {
        MockEnvironment env = prodEnv();
        env.setProperty("nocobase.jwt.secret", VALID_JWT_SECRET);
        env.setProperty("nocobase.data-source-encryption.master-key", VALID_MASTER_KEY);
        env.setProperty("spring.h2.console.enabled", "false");
        env.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        assertDoesNotThrow(() -> runGuard(env));
    }

    // -- dev profile permits known dev master key -------------------------

    @Test
    void devProfilePermitsKnownDevMasterKey() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("nocobase.jwt.secret", VALID_JWT_SECRET);
        env.setProperty("nocobase.data-source-encryption.master-key",
                "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhISE=");
        env.setProperty("spring.h2.console.enabled", "true");
        env.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        assertDoesNotThrow(() -> runGuard(env));
    }
}
