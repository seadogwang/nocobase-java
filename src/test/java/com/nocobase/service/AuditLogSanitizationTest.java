package com.nocobase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Focused unit tests for {@link AuditLogService} resource-key and string
 * sanitization. These run without a Spring context and prove deterministically
 * that the values passed into log statements and persisted as resourceKey are
 * scrubbed of raw JDBC URLs, hostnames, ports, and query credentials.
 *
 * <p>This complements {@link AuditFailureIntegrationTest}, which asserts the
 * same property against persisted audit records end-to-end.
 */
class AuditLogSanitizationTest {

    private final AuditLogService service = new AuditLogService(null, null, new ObjectMapper());

    // -- sanitizeResourceKey: stable identifiers pass through ---------------

    @Test
    void sanitizeResourceKey_preservesStableIdentifier() {
        assertEquals("connection-test", service.sanitizeResourceKey("connection-test"));
        assertEquals("my_ds_key", service.sanitizeResourceKey("my_ds_key"));
        assertEquals("users", service.sanitizeResourceKey("users"));
    }

    @Test
    void sanitizeResourceKey_nullAndBlankPassThrough() {
        assertNull(service.sanitizeResourceKey(null));
        assertEquals("", service.sanitizeResourceKey(""));
        assertEquals("   ", service.sanitizeResourceKey("   "));
    }

    // -- sanitizeResourceKey: JDBC URLs are scrubbed -----------------------

    @Test
    void sanitizeResourceKey_scrubsJdbcUrlWithEmbeddedQueryCredentials() {
        String leaky = "jdbc:postgresql://localhost:5432/test_db?user=admin&password=secret123";
        String sanitized = service.sanitizeResourceKey(leaky);

        assertFalse(sanitized.contains("admin"), "username must not leak: " + sanitized);
        assertFalse(sanitized.contains("secret123"), "password must not leak: " + sanitized);
        assertFalse(sanitized.contains("localhost"), "hostname must not leak: " + sanitized);
        assertFalse(sanitized.contains("5432"), "port must not leak: " + sanitized);
        assertFalse(sanitized.contains("test_db"), "database name must not leak: " + sanitized);
    }

    @Test
    void sanitizeResourceKey_scrubsNonJdbcUrlWithEmbeddedCredentials() {
        // A resourceKey that is not a jdbc: URL but still carries user=/password=
        // patterns must also be scrubbed (defense-in-depth).
        String leaky = "custom://host:5432/db?user=admin&password=secret123";
        String sanitized = service.sanitizeResourceKey(leaky);

        assertFalse(sanitized.contains("admin"), "username must not leak: " + sanitized);
        assertFalse(sanitized.contains("secret123"), "password must not leak: " + sanitized);
    }

    @Test
    void sanitizeResourceKey_isIdempotent() {
        String leaky = "jdbc:mysql://localhost:3306/db?user=admin&password=secret123";
        String once = service.sanitizeResourceKey(leaky);
        String twice = service.sanitizeResourceKey(once);
        assertEquals(once, twice, "sanitizing an already-sanitized key must be stable");
    }

    // -- sanitizeStringValue: credentials scrubbed -------------------------

    @Test
    void sanitizeStringValue_scrubsPasswordAndSecretPatterns() {
        String value = "error connecting with password=secret123 and secret=s3cr3t";
        String sanitized = service.sanitizeStringValue(value);

        assertFalse(sanitized.contains("secret123"), "password value must not leak: " + sanitized);
        assertFalse(sanitized.contains("s3cr3t"), "secret value must not leak: " + sanitized);
    }

    @Test
    void sanitizeStringValue_scrubsJdbcUrl() {
        String value = "failed for jdbc:postgresql://db.host:5432/prod?user=sa&password=hunter2";
        String sanitized = service.sanitizeStringValue(value);

        assertFalse(sanitized.contains("db.host"), "host must not leak: " + sanitized);
        assertFalse(sanitized.contains("hunter2"), "password must not leak: " + sanitized);
        assertFalse(sanitized.contains("sa&"), "user must not leak: " + sanitized);
    }
}
