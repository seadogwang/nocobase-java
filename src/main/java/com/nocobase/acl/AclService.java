package com.nocobase.acl;

import com.nocobase.entity.RoleResource;
import com.nocobase.entity.RoleResourceAction;
import com.nocobase.repository.RoleResourceActionRepository;
import com.nocobase.repository.RoleResourceRepository;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.FieldDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ACL Service for action and field-level permission checks.
 * Admin/root users bypass all checks.
 */
@Service
public class AclService {

    private static final Logger log = LoggerFactory.getLogger(AclService.class);

    private final RoleResourceRepository roleResourceRepository;
    private final RoleResourceActionRepository roleResourceActionRepository;
    private final CurrentUserContext currentUserContext;

    private static final Set<String> ALL_ACTIONS = Set.of("list", "get", "create", "update", "destroy");

    public AclService(RoleResourceRepository roleResourceRepository,
                      RoleResourceActionRepository roleResourceActionRepository,
                      CurrentUserContext currentUserContext) {
        this.roleResourceRepository = roleResourceRepository;
        this.roleResourceActionRepository = roleResourceActionRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Check if the current user can perform an action on a resource.
     * Admin/root users bypass all checks.
     */
    public boolean canAction(String resourceName, String action) {
        if (currentUserContext.isAdmin()) {
            return true;
        }

        List<String> roles = currentUserContext.getCurrentUserRoles();
        if (roles.isEmpty()) {
            return false;
        }

        for (String roleName : roles) {
            Optional<RoleResource> roleResource = roleResourceRepository
                    .findByRoleNameAndResourceName(roleName, resourceName);
            if (roleResource.isPresent()) {
                List<RoleResourceAction> actions = roleResourceActionRepository
                        .findByRoleResourceId(roleResource.get().getId());
                boolean hasAction = actions.stream()
                        .anyMatch(a -> a.getAction().equals(action));
                if (hasAction) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get readable fields permission for a resource.
     * Returns FieldPermission.all() for admin/root or when no field restriction is configured.
     * Returns FieldPermission.none() when user has no read permissions at all.
     */
    public FieldPermission getReadableFields(String resourceName) {
        if (currentUserContext.isAdmin()) {
            return FieldPermission.all();
        }

        List<String> roles = currentUserContext.getCurrentUserRoles();
        if (roles.isEmpty()) {
            return FieldPermission.none();
        }

        Set<String> allReadableFields = new HashSet<>();
        boolean hasAnyReadPermission = false;
        for (String roleName : roles) {
            Optional<RoleResource> roleResource = roleResourceRepository
                    .findByRoleNameAndResourceName(roleName, resourceName);
            if (roleResource.isPresent()) {
                List<RoleResourceAction> actions = roleResourceActionRepository
                        .findByRoleResourceId(roleResource.get().getId());
                for (RoleResourceAction action : actions) {
                    if ("list".equals(action.getAction()) || "get".equals(action.getAction())) {
                        hasAnyReadPermission = true;
                        if (action.getFields() == null || action.getFields().isEmpty()) {
                            return FieldPermission.all(); // explicit all-fields for this read action
                        }
                        String[] fields = action.getFields().split(",");
                        for (String f : fields) {
                            allReadableFields.add(f.trim());
                        }
                    }
                }
            }
        }

        if (!hasAnyReadPermission) {
            return FieldPermission.none(); // no read permission at all
        }
        return FieldPermission.only(allReadableFields);
    }

    /**
     * Get writable fields permission for a resource and action.
     */
    public FieldPermission getWritableFields(String resourceName, String action) {
        if (currentUserContext.isAdmin()) {
            return FieldPermission.all();
        }

        List<String> roles = currentUserContext.getCurrentUserRoles();
        if (roles.isEmpty()) {
            return FieldPermission.none();
        }

        Set<String> allWritableFields = new HashSet<>();
        boolean hasAnyWritePermission = false;
        for (String roleName : roles) {
            Optional<RoleResource> roleResource = roleResourceRepository
                    .findByRoleNameAndResourceName(roleName, resourceName);
            if (roleResource.isPresent()) {
                List<RoleResourceAction> actions = roleResourceActionRepository
                        .findByRoleResourceId(roleResource.get().getId());
                for (RoleResourceAction ra : actions) {
                    if (ra.getAction().equals(action)) {
                        hasAnyWritePermission = true;
                        if (ra.getFields() == null || ra.getFields().isEmpty()) {
                            return FieldPermission.all(); // explicit all-fields for this action
                        }
                        String[] fields = ra.getFields().split(",");
                        for (String f : fields) {
                            allWritableFields.add(f.trim());
                        }
                    }
                }
            }
        }

        if (!hasAnyWritePermission) {
            return FieldPermission.none();
        }
        return FieldPermission.only(allWritableFields);
    }

    /**
     * Filter a row's fields to only include readable fields.
     */
    public Map<String, Object> filterReadableFields(String resourceName, Map<String, Object> row) {
        FieldPermission readable = getReadableFields(resourceName);
        if (readable.isAll()) return row;
        if (readable.isNone()) {
            // Return minimal result: include primary key if present, otherwise empty
            Map<String, Object> minimal = new LinkedHashMap<>();
            if (row.containsKey("id") && row.get("id") != null) {
                minimal.put("id", row.get("id"));
            }
            return minimal;
        }

        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (readable.allows(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        // Always include primary key if present
        if (!filtered.containsKey("id") && row.containsKey("id") && row.get("id") != null) {
            filtered.put("id", row.get("id"));
        }
        return filtered;
    }

    /**
     * Validate that a field is writable for the given action.
     */
    public void checkWritableField(String resourceName, String action, String fieldName) {
        FieldPermission writable = getWritableFields(resourceName, action);
        if (writable.isAll()) return;
        if (!writable.allows(fieldName)) {
            throw new com.nocobase.web.ForbiddenException(
                "Field '" + fieldName + "' is not writable for action '" + action + "'");
        }
    }
}