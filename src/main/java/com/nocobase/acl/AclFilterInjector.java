package com.nocobase.acl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.RoleResource;
import com.nocobase.entity.RoleResourceScope;
import com.nocobase.repository.RoleResourceRepository;
import com.nocobase.repository.RoleResourceScopeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Injects ACL scope filters into queries.
 * Merges role-based data range filters with user-provided filters using $and.
 */
@Service
public class AclFilterInjector {

    private static final Logger log = LoggerFactory.getLogger(AclFilterInjector.class);

    private final RoleResourceRepository roleResourceRepository;
    private final RoleResourceScopeRepository roleResourceScopeRepository;
    private final CurrentUserContext currentUserContext;
    private final ObjectMapper objectMapper;

    public AclFilterInjector(RoleResourceRepository roleResourceRepository,
                             RoleResourceScopeRepository roleResourceScopeRepository,
                             CurrentUserContext currentUserContext,
                             ObjectMapper objectMapper) {
        this.roleResourceRepository = roleResourceRepository;
        this.roleResourceScopeRepository = roleResourceScopeRepository;
        this.currentUserContext = currentUserContext;
        this.objectMapper = objectMapper;
    }

    /**
     * Merge ACL scope filters with the user's filter.
     * @param resourceName the collection/resource name
     * @param action the action being performed (list, get, create, update, destroy)
     * @param userFilter the user-provided filter, may be null
     * @return the merged filter map, or the original if no scope applies
     */
    public Map<String, Object> mergeScopeFilter(String resourceName, String action, Map<String, Object> userFilter) {
        // Admin bypass
        if (currentUserContext.isAdmin()) {
            return userFilter;
        }

        Map<String, Object> scopeFilter = buildScopeFilter(resourceName, action);
        if (scopeFilter == null || scopeFilter.isEmpty()) {
            return userFilter;
        }

        if (userFilter == null || userFilter.isEmpty()) {
            return scopeFilter;
        }

        // Merge with $and
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("$and", List.of(userFilter, scopeFilter));
        return merged;
    }

    /**
     * Build the scope filter for the current user on a resource and action.
     * Multiple role scopes are combined with $or (least restrictive).
     */
    private Map<String, Object> buildScopeFilter(String resourceName, String action) {
        List<String> roles = currentUserContext.getCurrentUserRoles();
        if (roles.isEmpty()) {
            return Map.of("$alwaysFalse", true); // force no results via universal false condition
        }

        List<Map<String, Object>> scopeFilters = new ArrayList<>();
        for (String roleName : roles) {
            Optional<RoleResource> roleResource = roleResourceRepository
                    .findByRoleNameAndResourceName(roleName, resourceName);
            if (roleResource.isPresent()) {
                // Query scopes for the specific action
                List<RoleResourceScope> scopes = roleResourceScopeRepository
                        .findByRoleResourceIdAndAction(roleResource.get().getId(), action);

                // If no action-specific scopes, fall back to scopes without action (legacy)
                if (scopes.isEmpty()) {
                    scopes = roleResourceScopeRepository
                            .findByRoleResourceId(roleResource.get().getId());
                    scopes = scopes.stream()
                            .filter(s -> s.getAction() == null || s.getAction().isEmpty())
                            .toList();
                }

                for (RoleResourceScope scope : scopes) {
                    if (scope.getScope() != null && !scope.getScope().isEmpty()) {
                        try {
                            Map<String, Object> parsed = objectMapper.readValue(
                                    scope.getScope(), new TypeReference<Map<String, Object>>() {});
                            scopeFilters.add(parsed);
                        } catch (Exception e) {
                            log.error("Failed to parse scope filter for role {} action {}: {}",
                                    roleName, action, e.getMessage());
                            throw new com.nocobase.web.ForbiddenException(
                                "Invalid scope configuration for role " + roleName);
                        }
                    }
                }
            }
        }

        if (scopeFilters.isEmpty()) {
            return null; // no scope restriction
        }
        if (scopeFilters.size() == 1) {
            return scopeFilters.get(0);
        }
        // Multiple scopes: use $or (least restrictive combination across roles)
        return Map.of("$or", scopeFilters);
    }
}