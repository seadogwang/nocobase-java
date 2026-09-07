package com.nocobase.controller;

import com.nocobase.acl.CurrentUserContext;
import com.nocobase.entity.Role;
import com.nocobase.service.RoleManagementService;
import com.nocobase.web.ApiResponse;
import com.nocobase.web.ForbiddenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dedicated Roles controller for role management.
 * Delegates business logic to RoleManagementService.
 * Provides CRUD operations with protection for built-in roles (root/admin/member).
 * Only admin users can manage roles.
 */
@RestController
@RequestMapping("/api")
public class RolesController {

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private CurrentUserContext currentUserContext;

    // ========== Role CRUD ==========

    @GetMapping({"/roles:list", "/roles/list"})
    public ResponseEntity<?> listRoles(
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String sort) {
        requireAdmin();

        int pageNum = parsePage(page, 1);
        int pageSizeNum = parsePageSize(pageSize, 20);

        Page<Role> rolePage = roleManagementService.listRoles(pageNum, pageSizeNum, sort);
        List<Map<String, Object>> roleList = rolePage.getContent().stream()
                .map(roleManagementService::toRoleResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.list(roleList, rolePage.getTotalElements(), pageNum, pageSizeNum));
    }

    @GetMapping({"/roles:get", "/roles/get"})
    public ResponseEntity<?> getRole(@RequestParam(required = false) Long id,
                                      @RequestParam(required = false) String name) {
        requireAdmin();

        Role role;
        if (id != null) {
            role = roleManagementService.getRole(id);
        } else if (name != null) {
            role = roleManagementService.getRoleByName(name);
        } else {
            throw new IllegalArgumentException("id or name is required");
        }

        return ResponseEntity.ok(ApiResponse.success(roleManagementService.toRoleResponse(role)));
    }

    @PostMapping({"/roles:create", "/roles/create"})
    public ResponseEntity<?> createRole(@RequestBody Map<String, Object> body) {
        requireAdmin();

        String name = (String) body.get("name");
        String title = (String) body.get("title");
        Boolean isDefault = parseBoolean(body.get("isDefault"), "isDefault");

        Role role = roleManagementService.createRole(name, title, isDefault);
        return ResponseEntity.ok(ApiResponse.success(roleManagementService.toRoleResponse(role)));
    }

    @PostMapping({"/roles:update", "/roles/update"})
    public ResponseEntity<?> updateRole(@RequestBody Map<String, Object> body) {
        requireAdmin();

        Long roleId = parseIdFromBody(body, "id");
        if (roleId == null) {
            throw new IllegalArgumentException("id is required");
        }

        String name = (String) body.get("name");
        String title = (String) body.get("title");
        Boolean isDefault = body.containsKey("isDefault") ? parseBoolean(body.get("isDefault"), "isDefault") : null;

        Role role = roleManagementService.updateRole(roleId, name, title, isDefault);
        return ResponseEntity.ok(ApiResponse.success(roleManagementService.toRoleResponse(role)));
    }

    @PostMapping({"/roles:destroy", "/roles/destroy"})
    public ResponseEntity<?> destroyRole(@RequestBody(required = false) Map<String, Object> body,
                                          @RequestParam(required = false) Long id) {
        requireAdmin();

        Long roleId = id;
        if (roleId == null && body != null) {
            roleId = parseIdFromBody(body, "id");
        }
        if (roleId == null) {
            throw new IllegalArgumentException("id is required");
        }

        roleManagementService.destroyRole(roleId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", roleId)));
    }

    // ========== Helper methods ==========

    private void requireAdmin() {
        if (!currentUserContext.isAdmin()) {
            throw new ForbiddenException("Admin access required");
        }
    }

    /**
     * Parse a boolean from various input types: Boolean, "true"/"false" strings, 1/0 numbers.
     * Illegal values produce a 400 with a clean message.
     */
    private Boolean parseBoolean(Object value, String paramName) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String s = ((String) value).trim().toLowerCase();
            if ("true".equals(s)) return Boolean.TRUE;
            if ("false".equals(s)) return Boolean.FALSE;
            throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "'. Expected true, false, 1, or 0.");
        }
        if (value instanceof Number) {
            int n = ((Number) value).intValue();
            if (n == 1) return Boolean.TRUE;
            if (n == 0) return Boolean.FALSE;
            throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": " + n + ". Expected true, false, 1, or 0.");
        }
        throw new IllegalArgumentException(
                "Invalid value for " + paramName + ": expected boolean, got " + value.getClass().getSimpleName());
    }

    /**
     * Parse an id from a body parameter that could be Long, Integer, or numeric String.
     */
    private Long parseIdFromBody(Map<String, Object> body, String key) {
        Object val = body.get(key);
        if (val == null) return null;
        if (val instanceof Long) return (Long) val;
        if (val instanceof Integer) return ((Integer) val).longValue();
        if (val instanceof Number) return ((Number) val).longValue();
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid value for " + key + ": '" + val + "'. Expected a numeric id.");
        }
    }

    /**
     * Parse page number from string, with safe defaults for illegal values.
     */
    private int parsePage(String raw, int defaultVal) {
        if (raw == null || raw.trim().isEmpty()) return defaultVal;
        try {
            int val = Integer.parseInt(raw.trim());
            if (val < 1) return 1;
            if (val > 10000) return 10000; // cap overflow
            return val;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /**
     * Parse page size from string, with safe defaults for illegal values.
     */
    private int parsePageSize(String raw, int defaultVal) {
        if (raw == null || raw.trim().isEmpty()) return defaultVal;
        try {
            int val = Integer.parseInt(raw.trim());
            if (val < 1) return 1;
            if (val > 200) return 200; // cap at max
            return val;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}