package com.nocobase.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.UiSchema;
import com.nocobase.entity.UiSchemaTemplate;
import com.nocobase.repository.UiSchemaRepository;
import com.nocobase.repository.UiSchemaTemplateRepository;
import com.nocobase.sql.SqlErrorSanitizer;
import com.nocobase.web.ApiResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for UI Schema operations with transactional guarantees.
 * Encapsulates all repository access -- controllers must NOT directly inject repositories.
 * Transaction boundaries are in the service layer.
 */
@Service
public class UiSchemaService {

    private final UiSchemaRepository uiSchemaRepository;
    private final UiSchemaTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public UiSchemaService(UiSchemaRepository uiSchemaRepository,
                           UiSchemaTemplateRepository templateRepository,
                           ObjectMapper objectMapper,
                           AuditLogService auditLogService) {
        this.uiSchemaRepository = uiSchemaRepository;
        this.templateRepository = templateRepository;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    // ========================================================================
    // Repository access methods
    // ========================================================================

    public Optional<UiSchema> findByUid(String uid) {
        return uiSchemaRepository.findByUid(uid);
    }

    public Optional<UiSchema> findBySchemaUid(String schemaUid) {
        return uiSchemaRepository.findBySchemaUid(schemaUid);
    }

    public List<UiSchema> findByParentUid(String parentUid) {
        return uiSchemaRepository.findByParentUid(parentUid);
    }

    public List<UiSchema> findByParentUidOrderBySortOrderAsc(String parentUid) {
        return uiSchemaRepository.findByParentUidOrderBySortOrderAsc(parentUid);
    }

    public boolean existsByUid(String uid) {
        return uiSchemaRepository.existsByUid(uid);
    }

    public UiSchema save(UiSchema node) {
        return uiSchemaRepository.save(node);
    }

    // ========================================================================
    // Tree-building methods
    // ========================================================================

    public Map<String, Object> getTree() {
        List<UiSchema> roots = findByParentUid(null);

        if (roots.isEmpty()) {
            return Map.of(
                    "type", "void",
                    "x-component", "AdminLayout",
                    "properties", Map.of()
            );
        }

        roots.sort(Comparator.comparing(UiSchema::getSortOrder,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return buildTreeFromNode(roots.get(0));
    }

    public Map<String, Object> getTreeByUid(String uid) {
        UiSchema node = findByUid(uid)
                .orElseThrow(() -> new com.nocobase.web.ResourceNotFoundException("UI Schema", uid));
        return buildTreeFromNode(node);
    }

    public Map<String, Object> getTreeBySchemaUid(String schemaUid) {
        UiSchema node = findBySchemaUid(schemaUid)
                .orElseThrow(() -> new com.nocobase.web.ResourceNotFoundException("UI Schema", schemaUid));
        return buildTreeFromNode(node);
    }

    public Map<String, Object> getJsonSchema(String uid) {
        return findByUid(uid)
                .map(node -> parseSchema(node.getSchema()))
                .orElseThrow(() -> new com.nocobase.web.ResourceNotFoundException("UI Schema", uid));
    }

    public Map<String, Object> getParentJsonSchema(String uid) {
        return findByUid(uid)
                .<Map<String, Object>>map(node -> {
                    if (node.getParentUid() == null) {
                        return new LinkedHashMap<>();
                    }
                    return findByUid(node.getParentUid())
                            .map(parent -> parseSchema(parent.getSchema()))
                            .orElseThrow(() -> new com.nocobase.web.ResourceNotFoundException(
                                    "Parent UI Schema", node.getParentUid()));
                })
                .orElseThrow(() -> new com.nocobase.web.ResourceNotFoundException("UI Schema", uid));
    }

    // ========================================================================
    // Mutation methods
    // ========================================================================

    @Transactional
    public Map<String, Object> insertAdjacent(String targetUid, String position, Map<String, Object> schema) {
        try {
            if (position == null || !Set.of("beforeBegin", "afterBegin", "beforeEnd", "afterEnd").contains(position)) {
                throw new IllegalArgumentException(
                    "position must be one of: beforeBegin, afterBegin, beforeEnd, afterEnd");
            }

            String newUid = (String) schema.getOrDefault("x-uid", UUID.randomUUID().toString().replace("-", ""));

            if (existsByUid(newUid)) {
                throw new IllegalArgumentException(
                    "UI Schema with uid '" + newUid + "' already exists. uid must be unique.");
            }

            String newSchemaUid = (String) schema.getOrDefault("x-schema-uid", newUid);

            UiSchema target = findByUid(targetUid)
                    .orElseThrow(() -> new com.nocobase.web.ResourceNotFoundException("UI Schema", targetUid));

            String parentUid = target.getParentUid();

            UiSchema newNode = new UiSchema();
            newNode.setUid(newUid);
            newNode.setSchemaUid(newSchemaUid);
            newNode.setSchema(toJsonString(schema));

            if ("beforeBegin".equals(position) || "afterEnd".equals(position)) {
                newNode.setParentUid(parentUid);
                List<UiSchema> siblings = findByParentUidOrderBySortOrderAsc(parentUid);

                if ("beforeBegin".equals(position)) {
                    newNode.setSortOrder(target.getSortOrder());
                    for (UiSchema sib : siblings) {
                        if (sib.getSortOrder() >= target.getSortOrder()) {
                            sib.setSortOrder(sib.getSortOrder() + 1);
                            save(sib);
                        }
                    }
                } else {
                    newNode.setSortOrder(target.getSortOrder() + 1);
                    for (UiSchema sib : siblings) {
                        if (sib.getSortOrder() > target.getSortOrder()) {
                            sib.setSortOrder(sib.getSortOrder() + 1);
                            save(sib);
                        }
                    }
                }
            } else if ("afterBegin".equals(position)) {
                newNode.setParentUid(targetUid);
                List<UiSchema> children = findByParentUidOrderBySortOrderAsc(targetUid);
                newNode.setSortOrder(0);
                for (UiSchema child : children) {
                    child.setSortOrder(child.getSortOrder() + 1);
                    save(child);
                }
            } else {
                newNode.setParentUid(targetUid);
                List<UiSchema> children = findByParentUidOrderBySortOrderAsc(targetUid);
                newNode.setSortOrder(children.size());
            }

            save(newNode);
            auditLogService.auditSuccess("create", "uiSchema", newUid,
                    Map.of("position", position, "targetUid", targetUid));
            return Map.of("uid", newUid);
        } catch (Exception e) {
            String newUid = (String) schema.getOrDefault("x-uid", "unknown");
            auditLogService.auditFailure("create", "uiSchema", newUid,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    @Transactional
    public Map<String, Object> patch(String uid, Map<String, Object> schema, String name) {
        try {
            UiSchema node = findByUid(uid)
                    .orElseThrow(() -> new com.nocobase.web.ResourceNotFoundException("UI Schema", uid));

            if (schema != null) {
                Map<String, Object> existing = parseSchema(node.getSchema());
                deepMerge(existing, schema);
                node.setSchema(toJsonString(existing));
            }

            if (name != null) {
                node.setName(name);
            }

            node.setUpdatedAt(LocalDateTime.now());
            save(node);
            auditLogService.auditSuccess("update", "uiSchema", uid,
                    Map.of("name", name != null ? name : ""));

            return Map.of("uid", uid);
        } catch (Exception e) {
            auditLogService.auditFailure("update", "uiSchema", uid,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Recursively delete a UI Schema node and all its descendants.
     */
    @Transactional
    public void deleteRecursiveByUid(String nodeUid) {
        try {
            deleteRecursiveByUidInternal(nodeUid);
            auditLogService.auditSuccess("destroy", "uiSchema", nodeUid, Map.of());
        } catch (Exception e) {
            auditLogService.auditFailure("destroy", "uiSchema", nodeUid,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    private void deleteRecursiveByUidInternal(String nodeUid) {
        List<UiSchema> children = uiSchemaRepository.findByParentUid(nodeUid);
        for (UiSchema child : children) {
            deleteRecursiveByUidInternal(child.getUid());
        }
        uiSchemaRepository.deleteByUid(nodeUid);
    }

    // ========================================================================
    // Template methods
    // ========================================================================

    public List<Map<String, Object>> listTemplates() {
        List<UiSchemaTemplate> templates = templateRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (UiSchemaTemplate template : templates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", template.getId());
            item.put("name", template.getName());
            item.put("schema", template.getSchema() != null ? parseSchema(template.getSchema()) : Map.of());
            item.put("createdAt", template.getCreatedAt());
            item.put("updatedAt", template.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> getTemplate(String name) {
        UiSchemaTemplate template = templateRepository.findByName(name)
                .orElseThrow(() -> new com.nocobase.web.ResourceNotFoundException("UI Schema Template", name));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", template.getId());
        result.put("name", template.getName());
        result.put("schema", template.getSchema() != null ? parseSchema(template.getSchema()) : Map.of());
        result.put("createdAt", template.getCreatedAt());
        result.put("updatedAt", template.getUpdatedAt());
        return result;
    }

    // ========================================================================
    // Internal helper methods
    // ========================================================================

    private Map<String, Object> buildTreeFromNode(UiSchema node) {
        Map<String, Object> schema = parseSchema(node.getSchema());

        List<UiSchema> children = findByParentUidOrderBySortOrderAsc(node.getUid());
        if (!children.isEmpty()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (UiSchema child : children) {
                properties.put(child.getUid(), buildTreeFromNode(child));
            }
            schema.put("properties", properties);
        }

        schema.put("x-uid", node.getUid());
        return schema;
    }

    public Map<String, Object> parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(schemaJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid schema JSON: "
                    + SqlErrorSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    public String toJsonString(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize schema: "
                    + SqlErrorSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();
            Object targetValue = target.get(key);

            if (sourceValue instanceof Map && targetValue instanceof Map) {
                Map<String, Object> targetMap = (Map<String, Object>) targetValue;
                Map<String, Object> sourceMap = (Map<String, Object>) sourceValue;
                Map<String, Object> merged = new LinkedHashMap<>(targetMap);
                deepMerge(merged, sourceMap);
                target.put(key, merged);
            } else {
                target.put(key, sourceValue);
            }
        }
    }
}