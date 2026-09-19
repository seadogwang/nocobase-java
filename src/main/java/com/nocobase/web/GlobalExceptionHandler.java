package com.nocobase.web;

import com.nocobase.sql.DataSourceUnavailableException;
import com.nocobase.sql.SqlCollectionExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Global exception handler for consistent NocoBase-compatible error responses.
 * All errors are returned as {@code { "errors": [{ "message": "..." }] }}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("Conflict: {}", scrubMessage(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(scrubMessage(ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", scrubMessage(ex.getMessage()));
        return ResponseEntity.badRequest().body(ApiResponse.error(scrubMessage(ex.getMessage())));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", scrubMessage(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(scrubMessage(ex.getMessage())));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized: {}", scrubMessage(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(scrubMessage(ex.getMessage())));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        log.warn("Forbidden: {}", scrubMessage(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(scrubMessage(ex.getMessage())));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", scrubMessage(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Access denied"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        Object value = ex.getValue();
        String safeValue = scrubMessage(value != null ? value.toString() : "");
        log.warn("Type mismatch for parameter '{}': value='{}'", paramName, safeValue);
        return ResponseEntity.badRequest().body(
                ApiResponse.error("Invalid value for parameter '" + paramName + "': '" + safeValue + "'"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException ex) {
        log.warn("Malformed JSON request body");
        return ResponseEntity.badRequest().body(ApiResponse.error("Malformed JSON request body"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        String paramName = ex.getParameterName();
        log.warn("Missing required request parameter '{}'", paramName);
        return ResponseEntity.badRequest().body(
                ApiResponse.error("Required request parameter '" + paramName + "' is missing"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        log.warn("No handler found: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Resource not found"));
    }

    @ExceptionHandler(SqlCollectionExecutionException.class)
    public ResponseEntity<Map<String, Object>> handleSqlCollectionExecution(SqlCollectionExecutionException ex) {
        log.warn("SQL collection execution error: {}", ex.getSanitizedReason());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ex.getClientMessage()));
    }

    @ExceptionHandler(DataSourceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleDataSourceUnavailable(DataSourceUnavailableException ex) {
        log.warn("Data source unavailable: dataSourceKey='{}', reason={}",
                ex.getDataSourceKey(), ex.getSanitizedReason());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Unwrap Spring's DataAccess exception translation. A
     * {@link DataSourceUnavailableException} thrown from a @Transactional /
     * @Repository proxy is translated by Spring's JPA dialect into a
     * {@link DataAccessException} (e.g. InvalidDataAccessApiUsageException,
     * because DataSourceUnavailableException extends IllegalStateException).
     * Re-surface the original 503 instead of a generic 500 so clients can
     * distinguish transient datasource failure from a real query error.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof DataSourceUnavailableException dsx) {
                log.warn("Data source unavailable (unwrapped from DataAccess translation): dataSourceKey='{}', reason={}",
                        dsx.getDataSourceKey(), dsx.getSanitizedReason());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error(scrubMessage(dsx.getMessage())));
            }
            cause = cause.getCause();
        }
        log.error("Data access error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal server error"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Internal server error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal server error"));
    }

    /**
     * Scrub a message before it is logged or returned to the client. Strips
     * JDBC URLs, {@code user=}/{@code password=}/{@code secret=} credential
     * patterns, and Bearer tokens. Service-layer validation messages are
     * already sanitized at source; this is a defense-in-depth net so that
     * any exception echoing user input cannot leak credentials, SQL, or
     * connection strings to the API response or application logs.
     */
    private String scrubMessage(String msg) {
        if (msg == null) {
            return "";
        }
        String r = msg;
        r = r.replaceAll("jdbc:[a-zA-Z]+://[^\\s,;}\"]*", "[JDBC_URL_REDACTED]");
        r = r.replaceAll("(?i)password\\s*[=:]\\s*[^\\s,;}\"]*", "password=[REDACTED]");
        r = r.replaceAll("(?i)user\\s*[=:]\\s*[^\\s,;}\"]*", "user=[REDACTED]");
        r = r.replaceAll("(?i)secret\\s*[=:]\\s*[^\\s,;}\"]*", "secret=[REDACTED]");
        r = r.replaceAll("(?i)Bearer\\s+[A-Za-z0-9_\\-]+", "Bearer [REDACTED]");
        return r;
    }
}