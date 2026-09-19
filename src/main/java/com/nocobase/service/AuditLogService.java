package com.nocobase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.acl.CurrentUserContext;
import com.nocobase.entity.AuditLog;
import com.nocobase.repository.AuditLogRepository;
import com.nocobase.web.RequestIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Audit log service for recording all sensitive write operations.
 *
 * <h3>Design principles</h3>
 * <ul>
 *   <li><b>Fail fast:</b> If the audit log write fails, the calling transaction
 *       MUST roll back. Audit writes use the same transaction as the business
 *       operation (REQUIRED propagation). Any exception propagates to the caller.</li>
 *   <li><b>No secrets:</b> Details are sanitized to remove password, token,
 *       secret, SQL, JDBC URL, and datasource password before persistence.</li>
 *   <li><b>Admin-only query:</b> The query API is restricted to admin/root users.</li>
 * </ul>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final CurrentUserContext currentUserContext;
    private final ObjectMapper objectMapper;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "action", "resource", "resourceKey", "status", "createdAt", "actorUserId"
    );

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;

    public AuditLogService(AuditLogRepository auditLogRepository,
                           CurrentUserContext currentUserContext,
                           ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.currentUserContext = currentUserContext;
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    // Audit write API
    // ========================================================================

    /**
     * Record a successful audit event.
     * Uses REQUIRED propagation so the audit write is part of the caller's
     * transaction. If this write fails, the caller's business operation also
     * rolls back (fail-fast).
     *
     * @param action      the action performed (e.g., "create", "update", "destroy")
     * @param resource    the resource type (e.g., "collection", "field", "user")
     * @param resourceKey the resource identifier (e.g., collection name, user ID)
     * @param details     optional details map (will be serialized to JSON and sanitized)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void auditSuccess(String action, String resource, String resourceKey, Map<String, Object> details) {
        String safeKey = sanitizeResourceKey(resourceKey);
        try {
            AuditLog entry = buildAuditLog(action, resource, safeKey, details, "success");
            auditLogRepository.save(entry);
            log.debug("Audit: {} {} {} - success", action, resource, safeKey);
        } catch (Exception e) {
            log.error("Failed to write audit log for {} {} {}: {}",
                    action, resource, safeKey, sanitizeExceptionMessage(e));
            throw new AuditLogWriteException(
                    "Audit log write failed for " + action + " " + resource + "/" + safeKey, e);
        }
    }

    /**
     * Record a failed audit event.
     * Uses REQUIRED propagation; the caller's transaction still commits (the
     * business operation already failed), but we record the attempt.
     * If this write also fails, we log and swallow -- we don't want to compound
     * the original error.
     *
     * @param action      the action attempted
     * @param resource    the resource type
     * @param resourceKey the resource identifier
     * @param details     optional details map
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditFailure(String action, String resource, String resourceKey, Map<String, Object> details) {
        String safeKey = sanitizeResourceKey(resourceKey);
        try {
            AuditLog entry = buildAuditLog(action, resource, safeKey, details, "failure");
            auditLogRepository.save(entry);
            log.debug("Audit: {} {} {} - failure", action, resource, safeKey);
        } catch (Exception e) {
            log.error("Failed to write failure audit log for {} {} {}: {}",
                    action, resource, safeKey, sanitizeExceptionMessage(e));
            // Swallow: we don't want to compound the original error
        }
    }

    // ========================================================================
    // Admin-only query API
    // ========================================================================

    /**
     * List audit logs with pagination (admin/root only).
     */
    public Page<AuditLog> listAuditLogs(int page, int pageSize, String sort) {
        int effectivePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 1);
        Sort springSort = buildSort(sort);
        PageRequest pageRequest = PageRequest.of(effectivePage - 1, effectivePageSize, springSort);
        return auditLogRepository.findAll(pageRequest);
    }

    /**
     * List audit logs filtered by resource type.
     */
    public Page<AuditLog> listByResource(String resource, int page, int pageSize, String sort) {
        int effectivePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 1);
        Sort springSort = buildSort(sort);
        PageRequest pageRequest = PageRequest.of(effectivePage - 1, effectivePageSize, springSort);
        return auditLogRepository.findByResource(resource, pageRequest);
    }

    /**
     * List audit logs filtered by resource and resource key.
     */
    public Page<AuditLog> listByResourceAndKey(String resource, String resourceKey,
                                                int page, int pageSize, String sort) {
        int effectivePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 1);
        Sort springSort = buildSort(sort);
        PageRequest pageRequest = PageRequest.of(effectivePage - 1, effectivePageSize, springSort);
        return auditLogRepository.findByResourceAndResourceKey(resource, resourceKey, pageRequest);
    }

    /**
     * List audit logs filtered by actor user ID.
     */
    public Page<AuditLog> listByActor(Long actorUserId, int page, int pageSize, String sort) {
        int effectivePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 1);
        Sort springSort = buildSort(sort);
        PageRequest pageRequest = PageRequest.of(effectivePage - 1, effectivePageSize, springSort);
        return auditLogRepository.findByActorUserId(actorUserId, pageRequest);
    }

    /**
     * List audit logs filtered by date range.
     */
    public Page<AuditLog> listByDateRange(LocalDateTime start, LocalDateTime end,
                                           int page, int pageSize, String sort) {
        int effectivePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 1);
        Sort springSort = buildSort(sort);
        PageRequest pageRequest = PageRequest.of(effectivePage - 1, effectivePageSize, springSort);
        return auditLogRepository.findByCreatedAtBetween(start, end, pageRequest);
    }

    /**
     * Convert an AuditLog entity to a response map.
     */
    public Map<String, Object> toResponse(AuditLog entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("actorUserId", entry.getActorUserId());
        map.put("action", entry.getAction());
        map.put("resource", entry.getResource());
        map.put("resourceKey", entry.getResourceKey());
        map.put("status", entry.getStatus());
        map.put("createdAt", entry.getCreatedAt());
        map.put("requestId", entry.getRequestId());

        // Parse details JSON
        if (entry.getDetails() != null && !entry.getDetails().isEmpty()) {
            try {
                map.put("details", objectMapper.readValue(entry.getDetails(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                map.put("details", entry.getDetails());
            }
        } else {
            map.put("details", null);
        }

        return map;
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    /**
     * Build an AuditLog entity with sanitized details.
     */
    private AuditLog buildAuditLog(String action, String resource, String resourceKey,
                                    Map<String, Object> details, String status) {
        Long actorUserId = currentUserContext.getCurrentUserId().orElse(null);
        String sanitizedDetails = sanitizeDetails(details);
        String requestId = RequestIdContext.get();
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        return new AuditLog(actorUserId, action, resource, resourceKey,
                sanitizedDetails, status, requestId);
    }

    /**
     * Sanitize details to remove sensitive information.
     * Recursively handles Map, List, arrays, and string values.
     * Never includes: password, token, secret, SQL, JDBC URL, datasource password.
     */
    String sanitizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            Object sanitized = sanitizeValue(details);
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit details: {}", sanitizeExceptionMessage(e));
            return null;
        }
    }

    /**
     * Recursively sanitize any value.
     * Handles: null, Map, List, array, String, other (return as-is).
     */
    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                String key = entry.getKey();
                if (isSensitiveKey(key.toLowerCase())) {
                    sanitized.put(key, "[REDACTED]");
                } else {
                    sanitized.put(key, sanitizeValue(entry.getValue()));
                }
            }
            return sanitized;
        }
        if (value instanceof List) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : (List<?>) value) {
                sanitized.add(sanitizeValue(item));
            }
            return sanitized;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> sanitized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                sanitized.add(sanitizeValue(java.lang.reflect.Array.get(value, i)));
            }
            return sanitized;
        }
        if (value instanceof String) {
            return sanitizeStringValue((String) value);
        }
        return value;
    }

    /**
     * Sanitize a string value to remove sensitive content.
     * Strips: JDBC URLs, SQL fragments, token-like patterns, password/secret patterns.
     */
    String sanitizeStringValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = value;
        // Strip JDBC URLs
        result = result.replaceAll("jdbc:[a-zA-Z]+://[^\\s,;}\"]*", "[JDBC_URL_REDACTED]");
        // Strip SQL fragments (SELECT, INSERT, UPDATE, DELETE, DROP, CREATE, ALTER, TRUNCATE)
        result = result.replaceAll("(?i)\\b(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|EXEC|EXECUTE|UNION)\\b[^;]*", "[SQL_REDACTED]");
        // Strip token-like patterns (long alphanumeric strings > 40 chars)
        result = result.replaceAll("[A-Za-z0-9_\\-]{40,}", "[TOKEN_REDACTED]");
        // Strip password= patterns
        result = result.replaceAll("(?i)password\\s*[=:]\\s*[^\\s,;}\"]*", "password=[REDACTED]");
        // Strip user= patterns (username is a credential)
        result = result.replaceAll("(?i)user\\s*[=:]\\s*[^\\s,;}\"]*", "user=[REDACTED]");
        // Strip secret= patterns
        result = result.replaceAll("(?i)secret\\s*[=:]\\s*[^\\s,;}\"]*", "secret=[REDACTED]");
        // Strip Bearer token patterns
        result = result.replaceAll("(?i)Bearer\\s+[A-Za-z0-9_\\-]+", "Bearer [REDACTED]");
        return result;
    }

    /**
     * Sanitize an exception message to remove sensitive data.
     * Strips: JDBC URLs, SQL fragments, tokens, passwords, secrets.
     */
    private String sanitizeExceptionMessage(Exception e) {
        if (e == null) return "null";
        String msg = e.getMessage();
        if (msg == null) return "null";
        return sanitizeStringValue(msg);
    }

    /**
     * Sanitize a resource key before it is logged, persisted, or returned.
     * <p>Stable identifiers (collection names, data-source keys, the literal
     * {@code "connection-test"}) pass through unchanged so audit records stay
     * useful. A value that looks like a JDBC URL or contains credential
     * patterns ({@code user=}, {@code password=}, {@code secret=},
     * {@code ://}) is scrubbed so the raw URL and credentials never reach
     * application logs, the audit row, or the query API.
     */
    String sanitizeResourceKey(String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            return resourceKey;
        }
        String lower = resourceKey.toLowerCase();
        boolean looksSensitive = resourceKey.contains("jdbc:")
                || resourceKey.contains("://")
                || lower.contains("user=")
                || lower.contains("password=")
                || lower.contains("secret=");
        return looksSensitive ? sanitizeStringValue(resourceKey) : resourceKey;
    }

    /**
     * Check if a key name indicates sensitive data.
     */
    private boolean isSensitiveKey(String key) {
        return key.contains("password") || key.contains("token") || key.contains("secret")
                || key.contains("privatekey") || key.contains("credential") || key.contains("jdbc")
                || key.contains("sql") || key.contains("driver") || key.contains("masterkey")
                || key.contains("apikey") || key.contains("authkey") || key.contains("encryption");
    }

    /**
     * Build Spring Sort from a sort parameter string.
     */
    private Sort buildSort(String sortParam) {
        if (sortParam == null || sortParam.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }

        List<Sort.Order> orders = new ArrayList<>();
        for (String field : sortParam.split(",")) {
            field = field.trim();
            if (field.isEmpty()) continue;

            Sort.Direction direction = Sort.Direction.ASC;
            if (field.startsWith("-")) {
                direction = Sort.Direction.DESC;
                field = field.substring(1);
            }

            if (!ALLOWED_SORT_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Invalid sort field: " + field);
            }

            orders.add(new Sort.Order(direction, field));
        }

        if (orders.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }

        return Sort.by(orders);
    }

    /**
     * Exception thrown when audit log write fails, causing the business
     * transaction to fail fast.
     */
    public static class AuditLogWriteException extends RuntimeException {
        public AuditLogWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}