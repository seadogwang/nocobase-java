package com.nocobase.web;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified API response wrapper compatible with NocoBase frontend protocol.
 * <p>
 * Success: {@code { "data": ... }}
 * <br>
 * List success: {@code { "data": [...], "meta": { "count": N, "page": N, "pageSize": N } }}
 * <br>
 * Error: {@code { "errors": [{ "message": "..." }] }}
 */
public final class ApiResponse {

    private ApiResponse() {
        // utility class
    }

    /**
     * Build a success response with data.
     */
    public static Map<String, Object> success(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("data", data);
        return response;
    }

    /**
     * Build a list success response with pagination metadata.
     */
    public static Map<String, Object> list(Object data, long count, int page, int pageSize) {
        Map<String, Object> response = new HashMap<>();
        response.put("data", data);

        Map<String, Object> meta = new HashMap<>();
        meta.put("count", count);
        meta.put("page", page);
        meta.put("pageSize", pageSize);
        response.put("meta", meta);

        return response;
    }

    /**
     * Build an error response with one or more error messages.
     */
    public static Map<String, Object> error(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("errors", java.util.List.of(Map.of("message", message)));
        return response;
    }

    /**
     * Build an error response with multiple error messages.
     */
    public static Map<String, Object> errors(java.util.List<Map<String, String>> errorList) {
        Map<String, Object> response = new HashMap<>();
        response.put("errors", errorList);
        return response;
    }
}