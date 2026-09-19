package com.nocobase.web;

import com.nocobase.sql.DataSourceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-level exact-contract tests for {@link GlobalExceptionHandler}.
 *
 * <p>These complement the end-to-end {@link com.nocobase.api.ErrorEnvelopeTest}
 * by pinning the exact HTTP status and envelope shape for each error category
 * deterministically (no routing ambiguity), and by verifying that messages
 * are scrubbed of credentials, JDBC URLs, SQL, and class names before they
 * reach the response body.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> resp) {
        return resp.getBody();
    }

    private static void assertEnvelope(ResponseEntity<Map<String, Object>> resp) {
        assertNotNull(resp.getBody(), "response body must not be null");
        Object errors = resp.getBody().get("errors");
        assertTrue(errors instanceof List, "errors must be a list");
        List<Map<String, Object>> list = (List<Map<String, Object>>) errors;
        assertEquals(1, list.size(), "errors must contain exactly one entry");
        assertTrue(list.get(0).containsKey("message"), "error entry must have a message");
    }

    private static void assertNoSensitive(Map<String, Object> body) {
        String s = body.toString();
        assertFalse(s.contains("jdbc:"), "no JDBC URLs: " + s);
        assertFalse(s.toLowerCase().contains("password"), "no password: " + s);
        assertFalse(s.toLowerCase().contains("secret"), "no secret: " + s);
        assertFalse(s.toLowerCase().contains("select "), "no SQL: " + s);
        assertFalse(s.contains("\tat "), "no stack trace: " + s);
        assertFalse(s.contains("Bearer "), "no bearer token: " + s);
    }

    @SuppressWarnings("unchecked")
    private static String message(ResponseEntity<Map<String, Object>> resp) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("errors");
        return (String) list.get(0).get("message");
    }

    // -- 400: malformed JSON ------------------------------------------------

    @Test
    void handleMalformedJsonReturns400() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleMalformedJson(new HttpMessageNotReadableException("boom; SQL: SELECT * FROM users; jdbc:postgresql://h:5432/p?user=x&password=y"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEnvelope(resp);
        assertEquals("Malformed JSON request body", message(resp));
        assertNoSensitive(body(resp));
    }

    // -- 400: missing required request parameter ----------------------------

    @Test
    void handleMissingParamReturns400() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("name", "String", false);
        ResponseEntity<Map<String, Object>> resp = handler.handleMissingParam(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEnvelope(resp);
        assertNoSensitive(body(resp));
        // Param name is safe to echo; value is never included.
        assertTrue(message(resp).contains("name"), "message should mention the param name: " + message(resp));
    }

    // -- 404: no handler / no resource --------------------------------------

    @Test
    void handleNoResourceReturns404() throws Exception {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/unknown/route");
        ResponseEntity<Map<String, Object>> resp = handler.handleNoResource(ex);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEnvelope(resp);
        assertNoSensitive(body(resp));
    }

    // -- 503: data source unavailable ---------------------------------------

    @Test
    void handleDataSourceUnavailableReturns503() {
        DataSourceUnavailableException ex =
                new DataSourceUnavailableException("ext-pg", "Connection to jdbc:postgresql://h:5432/p?user=admin&password=secret refused");
        ResponseEntity<Map<String, Object>> resp = handler.handleDataSourceUnavailable(ex);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEnvelope(resp);
        // The handler returns ex.getMessage() which is the safe generic text
        // "Data source '<key>' is unavailable" -- the raw reason must NOT leak.
        assertNoSensitive(body(resp));
        String serialized = body(resp).toString();
        assertFalse(serialized.contains("secret"), "raw reason must not leak: " + serialized);
        assertFalse(serialized.contains("admin"), "username must not leak: " + serialized);
        assertFalse(serialized.contains("jdbc:postgresql://h"), "host/url must not leak: " + serialized);
    }

    // -- 500: generic internal error ----------------------------------------

    @Test
    void handleGeneralReturns500() {
        ResponseEntity<Map<String, Object>> resp = handler.handleGeneral(new RuntimeException("jdbc:postgresql://h:5432/p?user=admin&password=secret"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEnvelope(resp);
        assertEquals("Internal server error", message(resp));
        assertNoSensitive(body(resp));
    }

    // -- scrubMessage on IllegalArgumentException ----------------------------

    @Test
    void handleIllegalArgumentScrubsCredentials() {
        IllegalArgumentException ex = new IllegalArgumentException(
                "bad value: jdbc:postgresql://localhost:5432/db?user=admin&password=secret123");
        ResponseEntity<Map<String, Object>> resp = handler.handleIllegalArgument(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNoSensitive(body(resp));
        String serialized = body(resp).toString();
        assertFalse(serialized.contains("secret123"), "password must be redacted: " + serialized);
        assertFalse(serialized.contains("admin"), "username must be redacted: " + serialized);
        assertFalse(serialized.contains("localhost:5432"), "host/port must be redacted: " + serialized);
    }

    // -- conflict (409) sanitizes internal state text ----------------------

    @Test
    void handleIllegalStateReturns409() {
        IllegalStateException ex = new IllegalStateException("Collection already exists: my_coll");
        ResponseEntity<Map<String, Object>> resp = handler.handleIllegalState(ex);
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEnvelope(resp);
        assertNoSensitive(body(resp));
    }
}
