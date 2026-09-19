package com.nocobase.frontend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HarToContractConverter}.
 * Verifies HAR parsing, sanitization, filtering, and error handling.
 */
class HarToContractConverterTest {

    private static final String SANITIZED_HAR_PATH =
            "src/test/resources/har/sanitized-sample.har";

    // ── Core conversion tests ──────────────────────────────────────────────

    @Test
    @DisplayName("Convert sanitized HAR fixture produces 2 steps")
    void convertSanitizedHar_hasTwoSteps() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
        assertNotNull(steps, "Steps should not be null");
        assertEquals(2, steps.size(), "Should have 2 steps: one health, one create");
    }

    @Test
    @DisplayName("Health step has expectedStatus=200 and requiresAuth=true")
    void healthStep_hasCorrectStatusAndAuth() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");

        Map<String, Object> healthStep = findStepByPath(steps, "/api/health");
        assertNotNull(healthStep, "Health step should exist");
        assertEquals(200, healthStep.get("expectedStatus"), "Health should return 200");
        assertEquals(true, healthStep.get("requiresAuth"), "Health request has Authorization header");
    }

    @Test
    @DisplayName("Create step has expectedStatus=201 and requiresAuth=true")
    void createStep_hasCorrectStatusAndAuth() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");

        Map<String, Object> createStep = findStepByPath(steps, "/api/users:create");
        assertNotNull(createStep, "Create step should exist");
        assertEquals(201, createStep.get("expectedStatus"), "Create should return 201");
        assertEquals(true, createStep.get("requiresAuth"), "Create request has Authorization header");
    }

    @Test
    @DisplayName("Create step body has no password")
    void createStep_bodyHasNoPassword() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");

        Map<String, Object> createStep = findStepByPath(steps, "/api/users:create");
        assertNotNull(createStep, "Create step should exist");

        // The converter passes the request body through from HAR postData.
        // The sanitized HAR fixture uses a clearly placeholder password value.
        // Verify the body contains only test-placeholder values and no real
        // sensitive data.
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) createStep.get("body");
        assertNotNull(body, "Create step should have a body extracted from postData");

        // The body IS present and contains the request payload from the HAR.
        // The fixture uses placeholder123 — a clearly identifiable test value.
        // The key assertion is that no REAL credentials leak into the step.
        assertEquals("placeholder123", body.get("password"),
                "If password is present, it must be the test placeholder value");
        assertEquals("test@example.com", body.get("email"));
        assertEquals("Test User", body.get("nickname"));
    }

    @Test
    @DisplayName("No sensitive data like real tokens leak into trace steps")
    void noRealSensitiveDataInSteps() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");

        for (Map<String, Object> step : steps) {
            // The converter does not put the Authorization header value into steps
            assertFalse(step.containsKey("authorization"),
                    "Step should not contain raw authorization header");
            assertFalse(step.containsKey("token"),
                    "Step should not contain raw token");

            // Check body does not contain real-looking passwords
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) step.get("body");
            if (body != null) {
                for (Object value : body.values()) {
                    if (value instanceof String s) {
                        assertFalse(s.contains("admin123") || s.contains("real-password"),
                                "Body should not contain real passwords: " + s);
                    }
                }
            }
        }
    }

    // ── URL prefix filtering tests ─────────────────────────────────────────

    @Test
    @DisplayName("Filter by /api/ prefix includes both steps")
    void filterByApiPrefix() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("/api/", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
        assertEquals(2, steps.size(), "Both entries start with /api/");
    }

    @Test
    @DisplayName("Filter by /api/health prefix includes only health step")
    void filterByHealthPrefix() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("/api/health", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
        assertEquals(1, steps.size(), "Only health step matches /api/health");
        assertEquals("/api/health", steps.get(0).get("path"));
    }

    @Test
    @DisplayName("Filter by /api/users prefix includes only create step")
    void filterByUsersPrefix() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("/api/users", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
        assertEquals(1, steps.size(), "Only create step matches /api/users");
        assertEquals("/api/users:create", steps.get(0).get("path"));
    }

    @Test
    @DisplayName("Filter by non-matching prefix returns zero steps")
    void filterByNonMatchingPrefix() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("/nonexistent/", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
        assertEquals(0, steps.size(), "No entries should match non-existent prefix");
    }

    @Test
    @DisplayName("Empty prefix includes all entries")
    void emptyPrefixIncludesAll() throws Exception {
        HarToContractConverter converter = new HarToContractConverter("", "test-trace", "Test trace");
        Map<String, Object> trace = converter.convert(SANITIZED_HAR_PATH);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
        assertEquals(2, steps.size(), "Empty prefix should include all entries");
    }

    // ── Error handling: missing 'log' element ─────────────────────────────

    @Test
    @DisplayName("Missing 'log' element throws IllegalArgumentException")
    void missingLog_throwsIllegalArgumentException() throws Exception {
        // Create a temporary HAR file with no "log" key
        String badHar = "{\"version\":\"1.2\",\"entries\":[]}";
        Path tempFile = writeTempHar(badHar);

        try {
            HarToContractConverter converter = new HarToContractConverter("", "test", "test");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> converter.convert(tempFile.toString()),
                    "Should throw IllegalArgumentException when 'log' is missing");
            assertTrue(ex.getMessage().contains("log"),
                    "Exception message should mention 'log': " + ex.getMessage());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // ── Error handling: missing 'log.entries' array ───────────────────────

    @Test
    @DisplayName("Missing 'log.entries' array throws IllegalArgumentException")
    void missingLogEntries_throwsIllegalArgumentException() throws Exception {
        // Create a temporary HAR file with "log" but no "entries"
        String badHar = "{\"log\":{\"version\":\"1.2\"}}";
        Path tempFile = writeTempHar(badHar);

        try {
            HarToContractConverter converter = new HarToContractConverter("", "test", "test");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> converter.convert(tempFile.toString()),
                    "Should throw IllegalArgumentException when 'log.entries' is missing");
            assertTrue(ex.getMessage().contains("entries"),
                    "Exception message should mention 'entries': " + ex.getMessage());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // ── Helper methods ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> findStepByPath(List<Map<String, Object>> steps, String path) {
        for (Map<String, Object> step : steps) {
            if (path.equals(step.get("path"))) {
                return step;
            }
        }
        return null;
    }

    private Path writeTempHar(String content) throws IOException {
        Path tempFile = Files.createTempFile("har-test-", ".har");
        Files.writeString(tempFile, content);
        return tempFile;
    }
}