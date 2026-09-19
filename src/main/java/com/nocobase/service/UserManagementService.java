package com.nocobase.service;

import com.nocobase.entity.Role;
import com.nocobase.entity.User;
import com.nocobase.entity.UserRole;
import com.nocobase.repository.RoleRepository;
import com.nocobase.repository.UserRepository;
import com.nocobase.repository.UserRoleRepository;
import com.nocobase.web.ForbiddenException;
import com.nocobase.web.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service layer for user management business logic.
 * Extracted from UsersController to separate concerns.
 * Passwords are NEVER returned to callers.
 * Protects against losing the last admin/root login-capable account.
 */
@Service
public class UserManagementService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "email", "nickname", "createdAt", "updatedAt"
    );

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;

    public UserManagementService(UserRepository userRepository,
                                  RoleRepository roleRepository,
                                  UserRoleRepository userRoleRepository,
                                  AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.auditLogService = auditLogService;
    }

    // ========== User CRUD ==========

    /**
     * List users with DB-level pagination and sorting.
     */
    public Page<User> listUsers(int page, int pageSize, String sort) {
        int effectivePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 1);
        Sort springSort = buildSort(sort);
        PageRequest pageRequest = PageRequest.of(effectivePage - 1, effectivePageSize, springSort);
        return userRepository.findAll(pageRequest);
    }

    /**
     * Get a user by ID.
     */
    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", String.valueOf(userId)));
    }

    /**
     * Create a new user with hashed password.
     * Password is never returned in the response.
     */
    @Transactional
    public User createUser(String email, String nickname, String password, List<Long> roleIds) {
        try {
            if (email == null || email.isEmpty()) {
                throw new IllegalArgumentException("Email is required");
            }
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException("Password is required");
            }
            if (userRepository.existsByEmail(email)) {
                throw new IllegalArgumentException("Email already exists: " + email);
            }

            User user = new User();
            user.setEmail(email);
            user.setNickname(nickname != null ? nickname : email);
            user.setPassword(passwordEncoder.encode(password));
            user = userRepository.save(user);

            // Assign roles if specified
            if (roleIds != null) {
                for (Long roleId : roleIds) {
                    Role role = roleRepository.findById(roleId)
                            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
                    UserRole ur = new UserRole();
                    ur.setUserId(user.getId());
                    ur.setRoleId(role.getId());
                    userRoleRepository.save(ur);
                }
            }
            auditLogService.auditSuccess("create", "user", String.valueOf(user.getId()),
                    Map.of("email", user.getEmail(), "nickname", user.getNickname()));

            return user;
        } catch (Exception e) {
            auditLogService.auditFailure("create", "user",
                    email != null ? email : "unknown",
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Update a user. Password is never included in error messages or responses.
     */
    @Transactional
    public User updateUser(Long userId, String email, String nickname, String password) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", String.valueOf(userId)));

            if (email != null && !email.isEmpty()) {
                userRepository.findByEmail(email).ifPresent(existing -> {
                    if (!existing.getId().equals(userId)) {
                        throw new IllegalArgumentException("Email already exists: " + email);
                    }
                });
                user.setEmail(email);
            }
            if (nickname != null) {
                user.setNickname(nickname);
            }
            if (password != null && !password.isEmpty()) {
                user.setPassword(passwordEncoder.encode(password));
            }

            userRepository.save(user);
            auditLogService.auditSuccess("update", "user", String.valueOf(userId),
                    Map.of("email", user.getEmail(), "nickname", user.getNickname()));
            return user;
        } catch (Exception e) {
            auditLogService.auditFailure("update", "user", String.valueOf(userId),
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Delete a user. Protects against deleting the last admin/root.
     * Never allows self-deletion.
     */
    @Transactional
    public void destroyUser(Long userId, Long currentUserId) {
        try {
            if (currentUserId.equals(userId)) {
                throw new ForbiddenException("You cannot delete your own account");
            }

            if (!userRepository.existsById(userId)) {
                throw new ResourceNotFoundException("User", String.valueOf(userId));
            }

            // Check that we're not deleting the last admin/root
            ensureNotLastAdminOrRoot(userId);

            // Remove all role associations
            userRoleRepository.deleteByUserId(userId);

            userRepository.deleteById(userId);
            auditLogService.auditSuccess("destroy", "user", String.valueOf(userId), Map.of());
        } catch (Exception e) {
            auditLogService.auditFailure("destroy", "user", String.valueOf(userId),
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    // ========== User-Role Assignment ==========

    /**
     * List roles for a user.
     */
    public List<Map<String, Object>> listUserRoles(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", String.valueOf(userId));
        }

        List<UserRole> userRoles = userRoleRepository.findByUserId(userId);
        return userRoles.stream()
                .map(ur -> {
                    Role role = roleRepository.findById(ur.getRoleId()).orElse(null);
                    if (role == null) return null;
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", role.getId());
                    map.put("name", role.getName());
                    map.put("title", role.getTitle());
                    return map;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Update user roles. Protects against removing the last admin/root.
     */
    @Transactional
    public void updateUserRoles(Long userId, List<Long> newRoleIds) {
        try {
            if (!userRepository.existsById(userId)) {
                throw new ResourceNotFoundException("User", String.valueOf(userId));
            }

            if (newRoleIds == null) {
                throw new IllegalArgumentException("roles list is required");
            }

            // Check if we're about to remove the last admin/root
            ensureNotRemovingLastAdminOrRoot(userId, newRoleIds);

            // Remove all existing role assignments
            userRoleRepository.deleteByUserId(userId);

            // Add new role assignments
            for (Long roleId : newRoleIds) {
                Role role = roleRepository.findById(roleId)
                        .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
                UserRole ur = new UserRole();
                ur.setUserId(userId);
                ur.setRoleId(role.getId());
                userRoleRepository.save(ur);
            }

            auditLogService.auditSuccess("updateRoles", "user", String.valueOf(userId),
                    Map.of("newRoleIdsCount", newRoleIds.size()));
        } catch (Exception e) {
            auditLogService.auditFailure("updateRoles", "user", String.valueOf(userId),
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    // ========== Response helpers ==========

    /**
     * Convert a User entity to a response map, NEVER including the password.
     */
    public Map<String, Object> toUserResponse(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("email", user.getEmail());
        map.put("nickname", user.getNickname());
        map.put("createdAt", user.getCreatedAt());
        map.put("updatedAt", user.getUpdatedAt());

        // Include role names
        List<UserRole> userRoles = userRoleRepository.findByUserId(user.getId());
        List<Map<String, Object>> roleList = userRoles.stream()
                .map(ur -> {
                    Role role = roleRepository.findById(ur.getRoleId()).orElse(null);
                    if (role == null) return null;
                    Map<String, Object> roleMap = new LinkedHashMap<>();
                    roleMap.put("id", role.getId());
                    roleMap.put("name", role.getName());
                    roleMap.put("title", role.getTitle());
                    return roleMap;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        map.put("roles", roleList);

        // NEVER include password
        return map;
    }

    // ========== Private helpers ==========

    /**
     * Ensure the user being deleted is not the last admin/root login-capable account.
     */
    private void ensureNotLastAdminOrRoot(Long excludeUserId) {
        List<Role> adminRoles = roleRepository.findByNameIn(Set.of("admin", "root"));
        if (adminRoles.isEmpty()) {
            return; // No admin/root roles exist, nothing to protect
        }

        List<Long> adminRoleIds = adminRoles.stream().map(Role::getId).toList();

        // Check if the user being deleted has admin or root role
        List<UserRole> userRoles = userRoleRepository.findByUserId(excludeUserId);
        boolean hasAdminRole = userRoles.stream()
                .anyMatch(ur -> adminRoleIds.contains(ur.getRoleId()));

        if (!hasAdminRole) {
            return; // User doesn't have admin/root role, safe to delete
        }

        // Count other users who have admin/root roles
        List<UserRole> allAdminAssignments = userRoleRepository.findByRoleIdIn(adminRoleIds);
        Set<Long> otherAdminUserIds = allAdminAssignments.stream()
                .map(UserRole::getUserId)
                .filter(uid -> !uid.equals(excludeUserId))
                .collect(Collectors.toSet());

        if (otherAdminUserIds.isEmpty()) {
            throw new ForbiddenException("Cannot remove the last admin/root user");
        }
    }

    /**
     * Ensure that updating roles doesn't remove the last admin/root.
     */
    private void ensureNotRemovingLastAdminOrRoot(Long userId, List<Long> newRoleIds) {
        List<Role> adminRoles = roleRepository.findByNameIn(Set.of("admin", "root"));
        if (adminRoles.isEmpty()) {
            return;
        }

        List<Long> adminRoleIds = adminRoles.stream().map(Role::getId).toList();

        // Check if the user currently has admin/root role
        List<UserRole> currentUserRoles = userRoleRepository.findByUserId(userId);
        boolean currentlyHasAdmin = currentUserRoles.stream()
                .anyMatch(ur -> adminRoleIds.contains(ur.getRoleId()));

        if (!currentlyHasAdmin) {
            return; // User doesn't have admin/root, nothing to protect
        }

        // Check if the new roles still include admin/root
        boolean stillHasAdmin = newRoleIds.stream().anyMatch(adminRoleIds::contains);
        if (stillHasAdmin) {
            return; // User still has admin/root, safe
        }

        // User is about to lose admin/root -- check there are other admin/root users
        List<UserRole> allAdminAssignments = userRoleRepository.findByRoleIdIn(adminRoleIds);
        Set<Long> otherAdminUserIds = allAdminAssignments.stream()
                .map(UserRole::getUserId)
                .filter(uid -> !uid.equals(userId))
                .collect(Collectors.toSet());

        if (otherAdminUserIds.isEmpty()) {
            throw new ForbiddenException(
                    "Cannot remove the last admin/root role. At least one admin or root user must remain.");
        }
    }

    /**
     * Build Spring Sort from a NocoBase-style sort parameter.
     * Format: "-field" for descending, "field" for ascending, comma-separated for multiple.
     */
    private Sort buildSort(String sortParam) {
        if (sortParam == null || sortParam.isEmpty()) {
            return Sort.by(Sort.Direction.ASC, "id");
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
            return Sort.by(Sort.Direction.ASC, "id");
        }

        return Sort.by(orders);
    }
}