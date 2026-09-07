package com.nocobase.controller;

import com.nocobase.acl.CurrentUserContext;
import com.nocobase.service.AclManagementService;
import com.nocobase.web.ApiResponse;
import com.nocobase.web.ForbiddenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * ACL Management controller.
 * Exposes role resource CRUD, action permission configuration,
 * scope filter configuration, and field configuration.
 * All operations require admin access.
 * Config changes immediately affect DynamicRepository at next query.
 * All repository access is delegated to AclManagementService.
 */
@RestController
@RequestMapping("/api")
public class AclController {

    @Autowired
    private AclManagementService aclService;

    @Autowired
    private CurrentUserContext currentUserContext;

    // ========================================================================
    // Role Resource CRUD
    // ========================================================================

    @GetMapping({"/acl/roleResources:list", "/acl/roleResources/list"})
    public ResponseEntity<?> listRoleResources(@RequestParam(required = false) String roleName) {
        requireAdmin();
        var result = aclService.listRoleResources(roleName);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping({"/acl/roleResources:get", "/acl/roleResources/get"})
    public ResponseEntity<?> getRoleResource(@RequestParam Long id) {
        requireAdmin();
        var result = aclService.getRoleResource(id);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/acl/roleResources:create", "/acl/roleResources/create"})
    public ResponseEntity<?> createRoleResource(@RequestBody Map<String, Object> body) {
        requireAdmin();
        String roleName = (String) body.get("roleName");
        String resourceName = (String) body.get("resourceName");
        if (roleName == null || resourceName == null) {
            throw new IllegalArgumentException("roleName and resourceName are required");
        }
        var result = aclService.createRoleResource(roleName, resourceName);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/acl/roleResources:update", "/acl/roleResources/update"})
    public ResponseEntity<?> updateRoleResource(@RequestBody Map<String, Object> body) {
        requireAdmin();
        var result = aclService.updateRoleResource(body);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/acl/roleResources:destroy", "/acl/roleResources/destroy"})
    public ResponseEntity<?> destroyRoleResource(@RequestBody Map<String, Object> body,
                                                  @RequestParam(required = false) Long id) {
        requireAdmin();
        Long rrId = id != null ? id : (body != null && body.get("id") != null
                ? (body.get("id") instanceof Long ? (Long) body.get("id") : Long.parseLong(body.get("id").toString()))
                : null);
        if (rrId == null) {
            throw new IllegalArgumentException("id is required");
        }
        aclService.destroyRoleResource(rrId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", rrId)));
    }

    // ========================================================================
    // Role Resource Action CRUD
    // ========================================================================

    @GetMapping({"/acl/roleResourceActions:list", "/acl/roleResourceActions/list"})
    public ResponseEntity<?> listActions(@RequestParam Long roleResourceId) {
        requireAdmin();
        var result = aclService.listActions(roleResourceId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/acl/roleResourceActions:create", "/acl/roleResourceActions/create"})
    public ResponseEntity<?> createAction(@RequestBody Map<String, Object> body) {
        requireAdmin();
        Long roleResourceId = aclService.getLong(body, "roleResourceId");
        String action = (String) body.get("action");
        String fields = (String) body.get("fields");

        if (roleResourceId == null || action == null) {
            throw new IllegalArgumentException("roleResourceId and action are required");
        }

        var result = aclService.createAction(roleResourceId, action, fields);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/acl/roleResourceActions:update", "/acl/roleResourceActions/update"})
    public ResponseEntity<?> updateAction(@RequestBody Map<String, Object> body) {
        requireAdmin();
        var result = aclService.updateAction(body);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/acl/roleResourceActions:destroy", "/acl/roleResourceActions/destroy"})
    public ResponseEntity<?> destroyAction(@RequestParam(required = false) Long id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        requireAdmin();
        Long actionId = id != null ? id : (body != null && body.get("id") != null
                ? aclService.getLong(body, "id") : null);
        if (actionId == null) throw new IllegalArgumentException("id is required");

        aclService.destroyAction(actionId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", actionId)));
    }

    // ========================================================================
    // Role Resource Scope CRUD
    // ========================================================================

    @GetMapping({"/acl/roleResourceScopes:list", "/acl/roleResourceScopes/list"})
    public ResponseEntity<?> listScopes(@RequestParam Long roleResourceId) {
        requireAdmin();
        var result = aclService.listScopes(roleResourceId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/acl/roleResourceScopes:create", "/acl/roleResourceScopes/create"})
    public ResponseEntity<?> createScope(@RequestBody Map<String, Object> body) {
        requireAdmin();
        Long roleResourceId = aclService.getLong(body, "roleResourceId");
        String scopeJson = (String) body.get("scope");
        String action = (String) body.get("action");

        if (roleResourceId == null || scopeJson == null) {
            throw new IllegalArgumentException("roleResourceId and scope are required");
        }

        var result = aclService.createScope(roleResourceId, scopeJson, action);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/acl/roleResourceScopes:update", "/acl/roleResourceScopes/update"})
    public ResponseEntity<?> updateScope(@RequestBody Map<String, Object> body) {
        requireAdmin();
        var result = aclService.updateScope(body);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/acl/roleResourceScopes:destroy", "/acl/roleResourceScopes/destroy"})
    public ResponseEntity<?> destroyScope(@RequestParam(required = false) Long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        requireAdmin();
        Long scopeId = id != null ? id : (body != null && body.get("id") != null
                ? aclService.getLong(body, "id") : null);
        if (scopeId == null) throw new IllegalArgumentException("id is required");

        aclService.destroyScope(scopeId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", scopeId)));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private void requireAdmin() {
        if (!currentUserContext.isAdmin()) {
            throw new ForbiddenException("Admin access required");
        }
    }
}