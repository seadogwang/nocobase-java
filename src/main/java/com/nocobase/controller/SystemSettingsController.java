package com.nocobase.controller;

import com.nocobase.acl.CurrentUserContext;
import com.nocobase.service.SystemSettingsService;
import com.nocobase.web.ForbiddenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * System Settings Controller.
 * Provides frontend-compatible system settings API.
 * GET returns all settings as a flat object.
 * POST update supports partial updates, preserves unknown fields,
 * and is immediately readable with transactional consistency.
 * Sensitive config (database credentials, JWT secrets) is NOT exposed.
 * All repository access is delegated to SystemSettingsService.
 */
@RestController
@RequestMapping("/api")
public class SystemSettingsController {

    @Autowired
    private SystemSettingsService settingsService;

    @Autowired
    private CurrentUserContext currentUserContext;

    /**
     * GET /api/systemSettings:get
     * Returns all system settings as a frontend-compatible object.
     * Sensitive keys are excluded from the response.
     * JSON strings are parsed back to objects/arrays.
     */
    @GetMapping({"/systemSettings:get", "/systemSettings/get"})
    public ResponseEntity<?> get() {
        Map<String, Object> settings = settingsService.get();
        return ResponseEntity.ok(Map.of("data", settings));
    }

    /**
     * POST /api/systemSettings:update
     * Partial update: accepts a map of key-value pairs.
     * Requires admin permission.
     * Preserves existing settings that are not in the request.
     * Sensitive keys are rejected (case-insensitive).
     * Object/array values are serialized as JSON strings.
     * Update is immediately readable and transactionally consistent.
     */
    @PostMapping({"/systemSettings:update", "/systemSettings/update"})
    public ResponseEntity<?> update(@RequestBody Map<String, Object> body) {
        // Require admin permission
        if (!currentUserContext.isAdmin()) {
            throw new ForbiddenException("Admin permission required to update system settings");
        }

        Map<String, Object> settings = settingsService.update(body);

        return ResponseEntity.ok(Map.of("data", settings));
    }
}