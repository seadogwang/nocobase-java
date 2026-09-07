package com.nocobase.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.RoleResource;
import com.nocobase.entity.RoleResourceAction;
import com.nocobase.entity.RoleResourceScope;
import com.nocobase.repository.RoleRepository;
import com.nocobase.repository.RoleResourceActionRepository;
import com.nocobase.repository.RoleResourceRepository;
import com.nocobase.repository.RoleResourceScopeRepository;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.sql.SqlErrorSanitizer;
import com.nocobase.web.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for ACL management.
 * Encapsulates all repository access — controllers must NOT directly inject repositories.
 * Transaction boundaries are in the service layer.
 */
@Service
public class AclManagementService {

    private static final Set<String> SYSTEM_RESOURCES = Set.of(
            "users", "roles", "collections", "acl", "systemSettings",
            "uiSchemas", "plugins", "applicationPlugins", "auth"
    );

    private final RoleResourceRepository roleResourceRepository;
    private final RoleResourceActionRepository roleResourceActionRepository;
    private final RoleResourceScopeRepository roleResourceScopeRepository;
    private final RoleRepository roleRepository;
    private final CollectionRuntimeService runtimeService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public AclManagementService(RoleResourceRepository roleResourceRepository,
                                RoleResourceActionRepository roleResourceActionRepository,
                                RoleResourceScopeRepository roleResourceScopeRepository,
                                RoleRepository roleRepository,
                                CollectionRuntimeService runtimeService,
                                ObjectMapper objectMapper,
                                AuditLogService auditLogService) {
        this.roleResourceRepository = roleResourceRepository;
        this.roleResourceActionRepository = roleResourceActionRepository;
        this.roleResourceScopeRepository = roleResourceScopeRepository;
        this.roleRepository = roleRepository;
        this.runtimeService = runtimeService;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    // ========================================================================
    // Role Resource CRUD
    // ========================================================================

    public List<Map<String, Object>> listRoleResources(String roleName) {
        List<RoleResource> resources;
        if (roleName != null && !roleName.isEmpty()) {
            resources = roleResourceRepository.findByRoleName(roleName);
        } else {
            resources = roleResourceRepository.findAll();
        }
        return resources.stream()
                .map(this::toRoleResourceResponse)
                .collect(Collectors.toList());
    }

    public Map<String, Object> getRoleResource(Long id) {
        RoleResource rr = roleResourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoleResource", String.valueOf(id)));
        return toRoleResourceResponse(rr);
    }

    @Transactional
    public Map<String, Object> createRoleResource(String roleName, String resourceName) {
        try {
            // Validate role exists
            if (!roleRepository.existsByName(roleName)) {
                throw new IllegalArgumentException("Role not found: " + roleName);
            }

            // Validate resource exists
            if (!runtimeService.exists(resourceName) && !SYSTEM_RESOURCES.contains(resourceName)) {
                throw new IllegalArgumentException(
                        "Resource not found: " + resourceName
                        + ". Must be an existing collection or system resource.");
            }

            // Check for duplicate
            if (roleResourceRepository.findByRoleNameAndResourceName(roleName, resourceName).isPresent()) {
                throw new IllegalArgumentException(
                    "RoleResource already exists for role '" + roleName + "' and resource '" + resourceName + "'");
            }

            RoleResource rr = new RoleResource(roleName, resourceName);
            rr = roleResourceRepository.save(rr);
            auditLogService.auditSuccess("create", "acl", String.valueOf(rr.getId()),
                    Map.of("roleName", roleName, "resourceName", resourceName));
            return toRoleResourceResponse(rr);
        } catch (Exception e) {
            auditLogService.auditFailure("create", "acl", resourceName,
                    Map.of("roleName", roleName, "resourceName", resourceName,
                            "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    @Transactional
    public Map<String, Object> updateRoleResource(Map<String, Object> body) {
        try {
            RoleResource rr = getRoleResourceFromBody(body);

            String newRoleName = (String) body.get("roleName");
            String newResourceName = (String) body.get("resourceName");

            String effectiveRoleName = newRoleName != null ? newRoleName : rr.getRoleName();
            String effectiveResourceName = newResourceName != null ? newResourceName : rr.getResourceName();

            if (newRoleName != null && !newRoleName.equals(rr.getRoleName())) {
                if (!roleRepository.existsByName(newRoleName)) {
                    throw new IllegalArgumentException("Role not found: " + newRoleName);
                }
            }

            if (newResourceName != null && !newResourceName.equals(rr.getResourceName())) {
                if (!runtimeService.exists(newResourceName) && !SYSTEM_RESOURCES.contains(newResourceName)) {
                    throw new IllegalArgumentException(
                            "Resource not found: " + newResourceName
                            + ". Must be an existing collection or system resource.");
                }
            }

            if ((newRoleName != null && !newRoleName.equals(rr.getRoleName()))
                    || (newResourceName != null && !newResourceName.equals(rr.getResourceName()))) {
                final String effRoleName = effectiveRoleName;
                final String effResourceName = effectiveResourceName;
                final RoleResource currentRr = rr;
                roleResourceRepository
                        .findByRoleNameAndResourceName(effRoleName, effResourceName)
                        .ifPresent(duplicate -> {
                            if (!duplicate.getId().equals(currentRr.getId())) {
                                throw new IllegalArgumentException(
                                        "RoleResource already exists for role '" + effRoleName
                                        + "' and resource '" + effResourceName + "'");
                            }
                        });
            }

            if (newRoleName != null) rr.setRoleName(newRoleName);
            if (newResourceName != null) rr.setResourceName(newResourceName);

            rr = roleResourceRepository.save(rr);
            auditLogService.auditSuccess("update", "acl", String.valueOf(rr.getId()),
                    Map.of("roleName", rr.getRoleName(), "resourceName", rr.getResourceName()));
            return toRoleResourceResponse(rr);
        } catch (Exception e) {
            Long id = getLong(body, "id");
            auditLogService.auditFailure("update", "acl", id != null ? String.valueOf(id) : "unknown",
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    @Transactional
    public void destroyRoleResource(Long id) {
        try {
            RoleResource rr = roleResourceRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("RoleResource", String.valueOf(id)));

            List<RoleResourceAction> actions = roleResourceActionRepository.findByRoleResourceId(id);
            roleResourceActionRepository.deleteAll(actions);
            List<RoleResourceScope> scopes = roleResourceScopeRepository.findByRoleResourceId(id);
            roleResourceScopeRepository.deleteAll(scopes);

            roleResourceRepository.delete(rr);
            auditLogService.auditSuccess("destroy", "acl", String.valueOf(id),
                    Map.of("roleName", rr.getRoleName(), "resourceName", rr.getResourceName()));
        } catch (Exception e) {
            auditLogService.auditFailure("destroy", "acl", String.valueOf(id),
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    // ========================================================================
    // Role Resource Action CRUD
    // ========================================================================

    public List<Map<String, Object>> listActions(Long roleResourceId) {
        List<RoleResourceAction> actions = roleResourceActionRepository.findByRoleResourceId(roleResourceId);
        return actions.stream()
                .map(this::toActionResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> createAction(Long roleResourceId, String action, String fields) {
        try {
            RoleResource rr = roleResourceRepository.findById(roleResourceId)
                    .orElseThrow(() -> new ResourceNotFoundException("RoleResource", String.valueOf(roleResourceId)));

            if (!Set.of("list", "get", "create", "update", "destroy").contains(action)) {
                throw new IllegalArgumentException("Invalid action: " + action
                        + ". Must be one of: list, get, create, update, destroy");
            }

            List<RoleResourceAction> existingActions = roleResourceActionRepository
                    .findByRoleResourceId(roleResourceId);
            boolean hasDuplicate = existingActions.stream()
                    .anyMatch(a -> a.getAction().equals(action));
            if (hasDuplicate) {
                throw new IllegalArgumentException(
                        "Action '" + action + "' already exists for this roleResource");
            }

            validateFields(fields, rr.getResourceName());

            RoleResourceAction rra = new RoleResourceAction(roleResourceId, action, fields);
            rra = roleResourceActionRepository.save(rra);
            auditLogService.auditSuccess("create", "aclAction", String.valueOf(rra.getId()),
                    Map.of("roleResourceId", roleResourceId, "action", action));
            return toActionResponse(rra);
        } catch (Exception e) {
            auditLogService.auditFailure("create", "aclAction", String.valueOf(roleResourceId),
                    Map.of("roleResourceId", roleResourceId, "action", action,
                            "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    @Transactional
    public Map<String, Object> updateAction(Map<String, Object> body) {
        Long id = getLong(body, "id");
        if (id == null) throw new IllegalArgumentException("id is required");

        RoleResourceAction rra = roleResourceActionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoleResourceAction", String.valueOf(id)));

        if (body.containsKey("action")) {
            String newAction = (String) body.get("action");
            if (!Set.of("list", "get", "create", "update", "destroy").contains(newAction)) {
                throw new IllegalArgumentException("Invalid action: " + newAction
                        + ". Must be one of: list, get, create, update, destroy");
            }
            if (!newAction.equals(rra.getAction())) {
                List<RoleResourceAction> existingActions = roleResourceActionRepository
                        .findByRoleResourceId(rra.getRoleResourceId());
                boolean hasDuplicate = existingActions.stream()
                        .anyMatch(a -> !a.getId().equals(rra.getId()) && a.getAction().equals(newAction));
                if (hasDuplicate) {
                    throw new IllegalArgumentException(
                            "Action '" + newAction + "' already exists for this roleResource");
                }
            }
            rra.setAction(newAction);
        }
        if (body.containsKey("fields")) {
            String newFields = (String) body.get("fields");
            RoleResource rr = roleResourceRepository.findById(rra.getRoleResourceId())
                    .orElseThrow(() -> new ResourceNotFoundException("RoleResource",
                            String.valueOf(rra.getRoleResourceId())));
            validateFields(newFields, rr.getResourceName());
            rra.setFields(newFields);
        }

        RoleResourceAction updated = roleResourceActionRepository.save(rra);
        auditLogService.auditSuccess("update", "aclAction", String.valueOf(updated.getId()),
                Map.of("roleResourceId", updated.getRoleResourceId(), "action", updated.getAction()));
        return toActionResponse(updated);
    }

    @Transactional
    public void destroyAction(Long id) {
        RoleResourceAction rra = roleResourceActionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoleResourceAction", String.valueOf(id)));
        roleResourceActionRepository.delete(rra);
        auditLogService.auditSuccess("destroy", "aclAction", String.valueOf(id),
                Map.of("roleResourceId", rra.getRoleResourceId(), "action", rra.getAction()));
    }

    // ========================================================================
    // Role Resource Scope CRUD
    // ========================================================================

    public List<Map<String, Object>> listScopes(Long roleResourceId) {
        List<RoleResourceScope> scopes = roleResourceScopeRepository.findByRoleResourceId(roleResourceId);
        return scopes.stream()
                .map(this::toScopeResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> createScope(Long roleResourceId, String scopeJson, String action) {
        validateScopeJson(scopeJson);

        if (action != null && !action.isEmpty()) {
            if (!Set.of("list", "get", "create", "update", "destroy").contains(action)) {
                throw new IllegalArgumentException("Invalid action: " + action
                        + ". Must be one of: list, get, create, update, destroy");
            }
        }

        if (!roleResourceRepository.existsById(roleResourceId)) {
            throw new ResourceNotFoundException("RoleResource", String.valueOf(roleResourceId));
        }

        List<RoleResourceScope> existingScopes = roleResourceScopeRepository
                .findByRoleResourceId(roleResourceId);
        String targetAction = (action != null && !action.isEmpty()) ? action : null;
        boolean hasDuplicate = existingScopes.stream()
                .anyMatch(s -> Objects.equals(s.getAction(), targetAction));
        if (hasDuplicate) {
            throw new IllegalArgumentException(
                    "Scope already exists for this roleResource"
                    + (targetAction != null ? " and action '" + targetAction + "'" : ""));
        }

        RoleResourceScope scope = new RoleResourceScope(roleResourceId, scopeJson, action);
        scope = roleResourceScopeRepository.save(scope);
        auditLogService.auditSuccess("create", "aclScope", String.valueOf(scope.getId()),
                Map.of("roleResourceId", roleResourceId, "action", action != null ? action : ""));
        return toScopeResponse(scope);
    }

    @Transactional
    public Map<String, Object> updateScope(Map<String, Object> body) {
        Long id = getLong(body, "id");
        if (id == null) throw new IllegalArgumentException("id is required");

        RoleResourceScope scope = roleResourceScopeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoleResourceScope", String.valueOf(id)));

        if (body.containsKey("scope")) {
            String newScope = (String) body.get("scope");
            validateScopeJson(newScope);
            scope.setScope(newScope);
        }
        if (body.containsKey("action")) {
            String newAction = (String) body.get("action");
            if (newAction != null && !newAction.isEmpty()) {
                if (!Set.of("list", "get", "create", "update", "destroy").contains(newAction)) {
                    throw new IllegalArgumentException("Invalid action: " + newAction
                            + ". Must be one of: list, get, create, update, destroy");
                }
            }
            String targetAction = (newAction != null && !newAction.isEmpty()) ? newAction : null;
            if (!Objects.equals(targetAction, scope.getAction())) {
                final String tgtAction = targetAction;
                final RoleResourceScope currentScope = scope;
                List<RoleResourceScope> existingScopes = roleResourceScopeRepository
                        .findByRoleResourceId(currentScope.getRoleResourceId());
                boolean hasDuplicate = existingScopes.stream()
                        .anyMatch(s -> !s.getId().equals(currentScope.getId())
                                && Objects.equals(s.getAction(), tgtAction));
                if (hasDuplicate) {
                    throw new IllegalArgumentException(
                            "Scope already exists for this roleResource"
                            + (tgtAction != null ? " and action '" + tgtAction + "'" : ""));
                }
            }
            scope.setAction(newAction);
        }

        scope = roleResourceScopeRepository.save(scope);
        auditLogService.auditSuccess("update", "aclScope", String.valueOf(scope.getId()),
                Map.of("roleResourceId", scope.getRoleResourceId(), "action",
                        scope.getAction() != null ? scope.getAction() : ""));
        return toScopeResponse(scope);
    }

    @Transactional
    public void destroyScope(Long id) {
        RoleResourceScope scope = roleResourceScopeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoleResourceScope", String.valueOf(id)));
        roleResourceScopeRepository.delete(scope);
        auditLogService.auditSuccess("destroy", "aclScope", String.valueOf(id),
                Map.of("roleResourceId", scope.getRoleResourceId()));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    public void validateScopeJson(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            String sanitized = SqlErrorSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : "Unknown error");
            throw new IllegalArgumentException("Invalid scope JSON: " + sanitized);
        }
    }

    public void validateFields(String fields, String resourceName) {
        if (fields == null || fields.isEmpty()) return;
        if (SYSTEM_RESOURCES.contains(resourceName)) return;
        if (!runtimeService.exists(resourceName)) return;

        var def = runtimeService.get(resourceName);
        String[] fieldNames = fields.split(",");
        for (String fieldName : fieldNames) {
            fieldName = fieldName.trim();
            if (fieldName.isEmpty()) continue;
            if (!def.hasField(fieldName) && !isSystemField(fieldName)) {
                throw new IllegalArgumentException(
                        "Field '" + fieldName + "' not found in collection '" + resourceName + "'");
            }
        }
    }

    private boolean isSystemField(String name) {
        return "id".equals(name) || "created_at".equals(name) || "updated_at".equals(name)
                || "created_at_time".equals(name) || "updated_at_time".equals(name);
    }

    private RoleResource getRoleResourceFromBody(Map<String, Object> body) {
        Long id = getLong(body, "id");
        if (id == null) throw new IllegalArgumentException("id is required");
        return roleResourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RoleResource", String.valueOf(id)));
    }

    public Long getLong(Map<String, Object> body, String key) {
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

    Map<String, Object> toRoleResourceResponse(RoleResource rr) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rr.getId());
        map.put("roleName", rr.getRoleName());
        map.put("resourceName", rr.getResourceName());
        map.put("createdAt", rr.getCreatedAt());
        map.put("updatedAt", rr.getUpdatedAt());

        List<Map<String, Object>> actions = roleResourceActionRepository
                .findByRoleResourceId(rr.getId()).stream()
                .map(this::toActionResponse)
                .collect(Collectors.toList());
        map.put("actions", actions);

        List<Map<String, Object>> scopes = roleResourceScopeRepository
                .findByRoleResourceId(rr.getId()).stream()
                .map(this::toScopeResponse)
                .collect(Collectors.toList());
        map.put("scopes", scopes);

        return map;
    }

    Map<String, Object> toActionResponse(RoleResourceAction rra) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rra.getId());
        map.put("roleResourceId", rra.getRoleResourceId());
        map.put("action", rra.getAction());
        map.put("fields", rra.getFields());
        map.put("createdAt", rra.getCreatedAt());
        return map;
    }

    Map<String, Object> toScopeResponse(RoleResourceScope scope) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", scope.getId());
        map.put("roleResourceId", scope.getRoleResourceId());
        map.put("scope", scope.getScope());
        map.put("action", scope.getAction());
        map.put("createdAt", scope.getCreatedAt());

        if (scope.getScope() != null && !scope.getScope().isEmpty()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(
                        scope.getScope(), new TypeReference<Map<String, Object>>() {});
                map.put("parsedScope", parsed);
            } catch (Exception e) {
                map.put("parsedScope", null);
            }
        }

        return map;
    }
}