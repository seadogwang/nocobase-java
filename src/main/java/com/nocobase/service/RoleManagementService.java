package com.nocobase.service;

import com.nocobase.entity.Role;
import com.nocobase.entity.UserRole;
import com.nocobase.repository.RoleRepository;
import com.nocobase.repository.UserRoleRepository;
import com.nocobase.web.ForbiddenException;
import com.nocobase.web.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Service layer for role management business logic.
 * Extracted from RolesController to separate concerns.
 * Validates role name format and uniqueness, protects built-in roles.
 */
@Service
public class RoleManagementService {

    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;

    /** Built-in roles that cannot be deleted or renamed */
    private static final Set<String> BUILT_IN_ROLES = Set.of("root", "admin", "member");

    /** Role name must start with a letter, contain only letters/numbers/underscores, max 50 chars */
    private static final Pattern ROLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,49}$");

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "name", "title", "isDefault", "createdAt", "updatedAt"
    );

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;

    public RoleManagementService(RoleRepository roleRepository,
                                  UserRoleRepository userRoleRepository,
                                  AuditLogService auditLogService) {
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.auditLogService = auditLogService;
    }

    // ========== Role CRUD ==========

    /**
     * List roles with DB-level pagination and sorting.
     */
    public Page<Role> listRoles(int page, int pageSize, String sort) {
        int effectivePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 1);
        Sort springSort = buildSort(sort);
        PageRequest pageRequest = PageRequest.of(effectivePage - 1, effectivePageSize, springSort);
        return roleRepository.findAll(pageRequest);
    }

    /**
     * Get a role by ID.
     */
    public Role getRole(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role", String.valueOf(id)));
    }

    /**
     * Get a role by name.
     */
    public Role getRoleByName(String name) {
        return roleRepository.findByName(name)
                .orElseThrow(() -> new ResourceNotFoundException("Role", name));
    }

    /**
     * Create a new role with name format validation and uniqueness check.
     */
    @Transactional
    public Role createRole(String name, String title, Boolean isDefault) {
        try {
            validateRoleName(name);

            if (roleRepository.existsByName(name)) {
                throw new IllegalArgumentException("Role already exists: " + name);
            }

            Role role = new Role();
            role.setName(name);
            role.setTitle(title != null ? title : name);
            role.setIsDefault(isDefault != null ? isDefault : false);
            Role saved = roleRepository.save(role);
            auditLogService.auditSuccess("create", "role", name,
                    Map.of("name", name, "title", saved.getTitle()));
            return saved;
        } catch (Exception e) {
            auditLogService.auditFailure("create", "role", name,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Update a role. Protects built-in roles from being renamed.
     */
    @Transactional
    public Role updateRole(Long roleId, String name, String title, Boolean isDefault) {
        try {
            Role role = roleRepository.findById(roleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Role", String.valueOf(roleId)));

            if (name != null && !name.isEmpty()) {
                // Cannot rename built-in roles
                if (BUILT_IN_ROLES.contains(role.getName())) {
                    throw new ForbiddenException("Cannot rename built-in role: " + role.getName());
                }
                validateRoleName(name);
                // Check uniqueness
                roleRepository.findByName(name).ifPresent(existing -> {
                    if (!existing.getId().equals(roleId)) {
                        throw new IllegalArgumentException("Role name already exists: " + name);
                    }
                });
                role.setName(name);
            }

            if (title != null) {
                role.setTitle(title);
            }

            if (isDefault != null) {
                role.setIsDefault(isDefault);
            }

            Role updated = roleRepository.save(role);
            auditLogService.auditSuccess("update", "role", String.valueOf(roleId),
                    Map.of("name", updated.getName(), "title", updated.getTitle()));
            return updated;
        } catch (Exception e) {
            auditLogService.auditFailure("update", "role", String.valueOf(roleId),
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Delete a role. Protects built-in roles and cascades user-role associations.
     */
    @Transactional
    public void destroyRole(Long roleId) {
        try {
            Role role = roleRepository.findById(roleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Role", String.valueOf(roleId)));

            // Cannot delete built-in roles
            if (BUILT_IN_ROLES.contains(role.getName())) {
                throw new ForbiddenException("Cannot delete built-in role: " + role.getName());
            }

            // Remove all user-role associations for this role
            List<UserRole> userRoles = userRoleRepository.findByRoleId(roleId);
            userRoleRepository.deleteAll(userRoles);

            roleRepository.delete(role);
            auditLogService.auditSuccess("destroy", "role", String.valueOf(roleId),
                    Map.of("name", role.getName()));
        } catch (Exception e) {
            auditLogService.auditFailure("destroy", "role", String.valueOf(roleId),
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    // ========== Response helpers ==========

    /**
     * Convert a Role entity to a response map.
     */
    public Map<String, Object> toRoleResponse(Role role) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", role.getId());
        map.put("name", role.getName());
        map.put("title", role.getTitle());
        map.put("isDefault", role.getIsDefault());
        map.put("createdAt", role.getCreatedAt());
        map.put("updatedAt", role.getUpdatedAt());
        return map;
    }

    // ========== Private helpers ==========

    /**
     * Validate role name format.
     * Must start with a letter, contain only letters/numbers/underscores, max 50 characters.
     */
    private void validateRoleName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Role name is required");
        }
        if (!ROLE_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid role name: '" + name + "'. " +
                    "Role name must start with a letter and contain only letters, numbers, and underscores (max 50 characters).");
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