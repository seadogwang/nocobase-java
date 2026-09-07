package com.nocobase.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Base64;
import java.util.Arrays;
import java.util.Set;

/**
 * Production configuration guard.
 * <p>
 * Runs early during Spring Boot bootstrap (via
 * {@link ApplicationEnvironmentPreparedEvent}) so the application fails fast
 * before the Spring context is created if the configuration is insecure.
 * Checks include:
 * <ul>
 *   <li>JWT secret is not a known default/weak value</li>
 *   <li>H2 Console is not enabled outside dev/test profiles</li>
 *   <li>Hibernate ddl-auto is not set to a destructive value (update, create, create-drop)</li>
 *   <li>Data source encryption master key is configured and valid</li>
 *   <li>Data source encryption master key is not a known dev default (non-dev profiles)</li>
 * </ul>
 * <p>
 * In the test profile all checks are skipped, allowing test-specific configuration
 * (e.g. a short test secret, in-memory H2) to be used.
 * <p>
 * Registered in {@code META-INF/spring.factories} so it runs before the
 * application context is created.
 */
public class ProductionConfigGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigGuard.class);

    private static final Set<String> DESTRUCTIVE_DDL_VALUES = Set.of("update", "create", "create-drop");

    private static final String[] KNOWN_DEFAULT_SECRETS = {
            "nocobase-java-secret-key-change-in-production-must-be-at-least-256-bits-long",
            "default-secret-key-for-nocobase-java-backend-must-be-at-least-256-bits"
    };

    private static final String[] KNOWN_DEFAULT_MASTER_KEYS = {
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUy0yNTYhISEhISE=", // old dev default (AES-256 with hyphen)
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhIQ==",  // old dev default (AES256, 31 bytes, invalid)
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhISE="  // current dev default (AES256, 32 bytes)
    };

    private static final int AES_KEY_LENGTH = 32; // 256 bits

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();

        String[] activeProfiles = env.getActiveProfiles();
        boolean isTestProfile = Arrays.asList(activeProfiles).contains("test");

        if (isTestProfile) {
            log.info("Test profile active — skipping production config guard checks");
            return;
        }

        log.info("Running production config guard checks (active profiles: {})",
                Arrays.toString(activeProfiles));

        checkJwtSecret(env);
        checkDataSourceEncryptionMasterKey(env);
        checkH2Console(env);
        checkDdlAuto(env);
    }

    /**
     * Logs a generic error message that does NOT expose the actual secret value.
     * Fails fast with an IllegalStateException to prevent startup with insecure config.
     */
    private void checkJwtSecret(ConfigurableEnvironment env) {
        String jwtSecret = env.getProperty("nocobase.jwt.secret");

        if (jwtSecret == null || jwtSecret.isBlank()) {
            log.error("JWT secret is not configured. "
                    + "Set nocobase.jwt.secret to a strong, unique value in production.");
            throw new IllegalStateException(
                    "JWT secret is not configured in a non-test profile. "
                    + "Set nocobase.jwt.secret to a strong, unique value of at least 256 bits.");
        }

        for (String knownDefault : KNOWN_DEFAULT_SECRETS) {
            if (knownDefault.equals(jwtSecret)) {
                log.error("JWT secret is set to a known default/weak value. "
                        + "This is insecure for production. "
                        + "Set nocobase.jwt.secret to a strong, unique value.");
                throw new IllegalStateException(
                        "JWT secret is set to a default/weak value in a non-test profile. "
                        + "Set nocobase.jwt.secret to a strong, unique value of at least 256 bits.");
            }
        }

        if (jwtSecret.length() < 32) {
            log.error("JWT secret is too short ({} characters, minimum 32). "
                    + "Use a strong secret of at least 256 bits.", jwtSecret.length());
            throw new IllegalStateException(
                    "JWT secret is too short in a non-test profile (minimum 32 characters / 256 bits). "
                    + "Set nocobase.jwt.secret to a strong, unique value.");
        }
    }

    /**
     * Check that the data source encryption master key is configured and valid.
     * <p>
     * In dev profiles, the known dev default key is permitted (but the key must
     * still be valid base64 of the correct length). In non-dev, non-test
     * profiles, the known dev default key is rejected.
     * <p>
     * Never logs or includes the actual key value in error messages.
     */
    private void checkDataSourceEncryptionMasterKey(ConfigurableEnvironment env) {
        String masterKey = env.getProperty("nocobase.data-source-encryption.master-key");

        boolean isDevProfile = Arrays.asList(env.getActiveProfiles()).contains("dev");

        if (masterKey == null || masterKey.isBlank()) {
            log.error("Data source encryption master key is not configured. "
                    + "Set nocobase.data-source-encryption.master-key "
                    + "to a base64-encoded 256-bit (32-byte) key.");
            throw new IllegalStateException(
                    "Data source encryption master key is not configured in a non-test profile. "
                    + "Set nocobase.data-source-encryption.master-key "
                    + "to a base64-encoded 256-bit (32-byte) key.");
        }

        // Check for known dev default keys (only in non-dev profiles)
        if (!isDevProfile) {
            for (String knownDefault : KNOWN_DEFAULT_MASTER_KEYS) {
                if (knownDefault.equals(masterKey)) {
                    log.error("Data source encryption master key is set to a known default/weak value. "
                            + "This is insecure for production.");
                    throw new IllegalStateException(
                            "Data source encryption master key is set to a known default in a non-dev, non-test profile. "
                            + "Set nocobase.data-source-encryption.master-key "
                            + "to a securely generated base64-encoded 256-bit key.");
                }
            }
        }

        // Validate base64 and length
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(masterKey);
        } catch (IllegalArgumentException e) {
            log.error("Data source encryption master key is not valid base64.");
            throw new IllegalStateException(
                    "Data source encryption master key must be a valid base64-encoded string.", e);
        }

        if (keyBytes.length != AES_KEY_LENGTH) {
            log.error("Data source encryption master key is not 256 bits (decoded to {} bytes, expected {}).",
                    keyBytes.length, AES_KEY_LENGTH);
            throw new IllegalStateException(
                    "Data source encryption master key must decode to " + AES_KEY_LENGTH
                    + " bytes (256 bits), got " + keyBytes.length + " bytes.");
        }
    }

    /**
     * Check that H2 Console is not enabled outside dev/test profiles.
     */
    private void checkH2Console(ConfigurableEnvironment env) {
        boolean isDevProfile = Arrays.asList(env.getActiveProfiles()).contains("dev");
        boolean h2ConsoleEnabled = Boolean.parseBoolean(
                env.getProperty("spring.h2.console.enabled", "false"));

        if (h2ConsoleEnabled && !isDevProfile) {
            log.error("H2 Console is enabled in a non-dev/non-test profile. "
                    + "Disable spring.h2.console.enabled in production.");
            throw new IllegalStateException(
                    "H2 Console is enabled in a non-dev/non-test profile. "
                    + "Set spring.h2.console.enabled=false in production.");
        }
    }

    /**
     * Check that Hibernate ddl-auto is not set to a destructive value.
     */
    private void checkDdlAuto(ConfigurableEnvironment env) {
        String ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto");

        if (ddlAuto != null && DESTRUCTIVE_DDL_VALUES.contains(ddlAuto.toLowerCase())) {
            log.error("Hibernate ddl-auto is set to '{}' in a non-test profile. "
                    + "This can cause destructive schema changes in production. "
                    + "Set spring.jpa.hibernate.ddl-auto to 'none' or 'validate' and use Flyway migrations.",
                    ddlAuto);
            throw new IllegalStateException(
                    "Hibernate ddl-auto is set to '" + ddlAuto + "' in a non-test profile. "
                    + "Set spring.jpa.hibernate.ddl-auto to 'none' or 'validate' and use Flyway migrations.");
        }
    }
}