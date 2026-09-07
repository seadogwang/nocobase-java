package com.nocobase.controller;

import com.nocobase.acl.CurrentUserContext;
import com.nocobase.entity.User;
import com.nocobase.service.UserManagementService;
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
 * Dedicated Users controller for user management.
 * Delegates business logic to UserManagementService.
 * Provides CRUD operations with password protection, role-based access control,
 * and admin-only management for sensitive operations.
 * Passwords are NEVER returned to the frontend.
 */
@RestController
@RequestMapping("/api")
public class UsersController {

    @Autowired
    private UserManagementService userManagementService;

    @Autowired
    private CurrentUserContext currentUserContext;

    // ========== User CRUD ==========

    @GetMapping({"/users:list", "/users/list"})
    public ResponseEntity<?> listUsers(
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String sort) {
        requireAdmin();

        int pageNum = parsePage(page, 1);
        int pageSizeNum = parsePageSize(pageSize, 20);

        Page<User> userPage = userManagementService.listUsers(pageNum, pageSizeNum, sort);
        List<Map<String, Object>> userList = userPage.getContent().stream()
                .map(userManagementService::toUserResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.list(userList, userPage.getTotalElements(), pageNum, pageSizeNum));
    }

    @GetMapping({"/users:get", "/users/get"})
    public ResponseEntity<?> getUser(@RequestParam(required = false) Long id,
                                     @RequestParam(required = false) String filterByTk) {
        Long userId = id != null ? id : parseIdFromString(filterByTk, "filterByTk");
        if (userId == null) {
            throw new IllegalArgumentException("id or filterByTk is required");
        }

        // Admin can get any user; regular users can only get their own info
        if (!currentUserContext.isAdmin() && !currentUserContext.requireUserId().equals(userId)) {
            throw new ForbiddenException("You can only view your own profile");
        }

        User user = userManagementService.getUser(userId);
        return ResponseEntity.ok(ApiResponse.success(userManagementService.toUserResponse(user)));
    }

    @PostMapping({"/users:create", "/users/create"})
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> body) {
        requireAdmin();

        String email = (String) body.get("email");
        String nickname = (String) body.get("nickname");
        String password = (String) body.get("password");

        @SuppressWarnings("unchecked")
        List<Object> roleIdsRaw = (List<Object>) body.get("roles");
        List<Long> roleIds = parseRolesList(roleIdsRaw);

        User user = userManagementService.createUser(email, nickname, password, roleIds);
        return ResponseEntity.ok(ApiResponse.success(userManagementService.toUserResponse(user)));
    }

    @PostMapping({"/users:update", "/users/update"})
    public ResponseEntity<?> updateUser(@RequestBody Map<String, Object> body) {
        Long userId = getUserIdFromBody(body);

        // Admin can update any user; regular users can only update their own profile
        if (!currentUserContext.isAdmin() && !currentUserContext.requireUserId().equals(userId)) {
            throw new ForbiddenException("You can only update your own profile");
        }

        String email = (String) body.get("email");
        String nickname = (String) body.get("nickname");
        String password = (String) body.get("password");

        User user = userManagementService.updateUser(userId, email, nickname, password);
        return ResponseEntity.ok(ApiResponse.success(userManagementService.toUserResponse(user)));
    }

    @PostMapping({"/users:destroy", "/users/destroy"})
    public ResponseEntity<?> destroyUser(@RequestBody(required = false) Map<String, Object> body,
                                         @RequestParam(required = false) Long id,
                                         @RequestParam(required = false) String filterByTk) {
        requireAdmin();

        Long userId = id != null ? id : parseIdFromString(filterByTk, "filterByTk");
        if (userId == null && body != null) {
            userId = parseIdFromBodyParam(body, "id");
            if (userId == null) {
                userId = parseIdFromBodyParam(body, "filterByTk");
            }
        }
        if (userId == null) {
            throw new IllegalArgumentException("id or filterByTk is required");
        }

        userManagementService.destroyUser(userId, currentUserContext.requireUserId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", userId)));
    }

    // ========== User-Role Assignment ==========

    @GetMapping({"/users/{userId}/roles:list", "/users/{userId}/roles/list"})
    public ResponseEntity<?> listUserRoles(@PathVariable Long userId) {
        // Admin can list any user's roles; regular users can only list their own
        if (!currentUserContext.isAdmin() && !currentUserContext.requireUserId().equals(userId)) {
            throw new ForbiddenException("You can only view your own roles");
        }

        List<Map<String, Object>> roleList = userManagementService.listUserRoles(userId);
        return ResponseEntity.ok(ApiResponse.success(roleList));
    }

    @PostMapping({"/users/{userId}/roles:update", "/users/{userId}/roles/update"})
    public ResponseEntity<?> updateUserRoles(@PathVariable Long userId,
                                              @RequestBody Map<String, Object> body) {
        requireAdmin();

        @SuppressWarnings("unchecked")
        List<Object> roleIdsRaw = (List<Object>) body.get("roles");
        if (roleIdsRaw == null) {
            throw new IllegalArgumentException("roles list is required");
        }

        List<Long> roleIds = parseRolesList(roleIdsRaw);

        userManagementService.updateUserRoles(userId, roleIds);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "ok")));
    }

    // ========== Helper methods ==========

    private void requireAdmin() {
        if (!currentUserContext.isAdmin()) {
            throw new ForbiddenException("Admin access required");
        }
    }

    /**
     * Parse an id from a numeric string parameter. Supports both number and numeric string.
     * Illegal values produce a 400 with a clean message (never a raw NumberFormatException).
     */
    private Long parseIdFromString(String value, String paramName) {
        if (value == null) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid value for " + paramName + ": '" + value + "'. Expected a numeric id.");
        }
    }

    /**
     * Parse an id from a body parameter that could be Long, Integer, or numeric String.
     */
    private Long parseIdFromBodyParam(Map<String, Object> body, String key) {
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
     * Parse a roles list from raw objects. Each element must be a valid numeric id.
     * Illegal elements produce a 400 with a clean message.
     */
    private List<Long> parseRolesList(List<Object> raw) {
        if (raw == null) return null;
        List<Long> result = new java.util.ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Object obj = raw.get(i);
            try {
                if (obj instanceof Long) {
                    result.add((Long) obj);
                } else if (obj instanceof Integer) {
                    result.add(((Integer) obj).longValue());
                } else if (obj instanceof Number) {
                    result.add(((Number) obj).longValue());
                } else if (obj instanceof String) {
                    result.add(Long.parseLong(((String) obj).trim()));
                } else {
                    throw new IllegalArgumentException(
                            "Invalid role id at index " + i + ": expected a numeric id, got " + obj.getClass().getSimpleName());
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid role id at index " + i + ": '" + obj + "' is not a valid numeric id.");
            }
        }
        return result;
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

    private Long getUserIdFromBody(Map<String, Object> body) {
        Long uid = parseIdFromBodyParam(body, "id");
        if (uid != null) return uid;
        Long filterByTk = parseIdFromBodyParam(body, "filterByTk");
        if (filterByTk != null) return filterByTk;
        throw new IllegalArgumentException("id or filterByTk is required");
    }
}