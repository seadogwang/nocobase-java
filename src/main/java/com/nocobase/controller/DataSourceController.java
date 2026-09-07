package com.nocobase.controller;

import com.nocobase.service.DataSourceConfigService;
import com.nocobase.sql.SqlDataSourceResolver;
import com.nocobase.sql.SqlErrorSanitizer;
import com.nocobase.web.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * External data source management API.
 *
 * <p>Provides CRUD operations for external data sources and connection testing.
 * Secrets (passwords) are NEVER returned to the frontend. All log output is
 * sanitized via {@link SqlErrorSanitizer}.
 *
 * <p>External data sources are always read-only and only used for SQL
 * collection queries. They never participate in JPA, Flyway, or DDL operations.
 */
@RestController
@RequestMapping("/api")
public class DataSourceController {

    @Autowired
    private DataSourceConfigService configService;

    @Autowired
    private SqlDataSourceResolver dataSourceResolver;

    /**
     * List all external data sources.
     * GET /api/dataSources:list or /api/dataSources/list
     * Passwords are never returned.
     */
    @GetMapping({"/dataSources:list", "/dataSources/list"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> list() {
        List<Map<String, Object>> dataSources = configService.listAll();
        return ResponseEntity.ok(ApiResponse.success(dataSources));
    }

    /**
     * Get a single external data source by key.
     * GET /api/dataSources:get?key=xxx or /api/dataSources/get?key=xxx
     * Password is never returned.
     */
    @GetMapping({"/dataSources:get", "/dataSources/get"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> get(@RequestParam String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("'key' parameter is required");
        }
        Map<String, Object> ds = configService.getByKey(key);
        return ResponseEntity.ok(ApiResponse.success(ds));
    }

    /**
     * Create a new external data source.
     * POST /api/dataSources:create or /api/dataSources/create
     * Body: { key, url, [driverClassName], [username], [password], [enabled], [dialect], [displayName] }
     * Password is NEVER returned in the response.
     */
    @PostMapping({"/dataSources:create", "/dataSources/create"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = configService.create(body);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Update an existing external data source.
     * POST /api/dataSources:update or /api/dataSources/update
     * Body: { key, [url], [driverClassName], [username], [password], [enabled], [dialect], [displayName] }
     * Password is NEVER returned in the response.
     */
    @PostMapping({"/dataSources:update", "/dataSources/update"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> update(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = configService.update(body);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Delete an external data source.
     * POST /api/dataSources:destroy or /api/dataSources/destroy
     * Body: { key }
     */
    @PostMapping({"/dataSources:destroy", "/dataSources/destroy"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> destroy(@RequestBody Map<String, Object> body) {
        String key = (String) body.get("key");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("'key' is required");
        }
        configService.delete(key);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "key", key,
                "message", "Data source deleted successfully"
        )));
    }

    /**
     * Test a database connection without saving the configuration.
     * POST /api/dataSources:testConnection or /api/dataSources/testConnection
     * Body: { url, [driverClassName], [username], [password] }
     * Result NEVER contains credentials or raw connection details.
     */
    @PostMapping({"/dataSources:testConnection", "/dataSources/testConnection"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> testConnection(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = configService.testConnection(body);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}