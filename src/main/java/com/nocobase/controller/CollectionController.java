package com.nocobase.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.service.CollectionMetadataService;
import com.nocobase.sql.SqlErrorSanitizer;
import com.nocobase.web.ApiResponse;
import com.nocobase.web.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Collection and Field management controller.
 * All operations go through CollectionMetadataService -- no direct JdbcTemplate usage.
 * Errors are thrown as standard exceptions handled by GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api")
public class CollectionController {

    @Autowired
    private CollectionMetadataService metadataService;

    @Autowired
    private CollectionRuntimeService runtimeService;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping({"/collections:list", "/collections/list"})
    public ResponseEntity<?> list() {
        var result = runtimeService.getAll().stream()
                .map(this::toCollectionMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> getByName(@PathVariable String name) {
        if (!runtimeService.exists(name)) {
            throw new ResourceNotFoundException("Collection", name);
        }
        return ResponseEntity.ok(ApiResponse.success(toCollectionMap(runtimeService.get(name))));
    }

    /**
     * Dynamic collection creation.
     * POST /api/collections:create
     * Preserves all extension fields like options, schema, filterTargetKey, etc.
     */
    @PostMapping({"/collections:create", "/collections/create"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> createCollection(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String title = (String) body.get("title");
        String type = (String) body.getOrDefault("type", "physical");

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Collection name is required");
        }

        if (runtimeService.exists(name)) {
            throw new IllegalArgumentException("Collection already exists: " + name);
        }

        CollectionEntity collection = new CollectionEntity(name, title != null ? title : name, type);
        String tableName = (String) body.getOrDefault("tableName", name);
        collection.setTableName(tableName);

        // Preserve all extension fields
        collection.setSchema((String) body.getOrDefault("schema", "public"));
        collection.setNamespace((String) body.get("namespace"));
        collection.setTemplate((String) body.get("template"));
        collection.setCategory((String) body.get("category"));
        collection.setView(body.get("view") instanceof Boolean v ? v : null);
        collection.setSortable(body.get("sortable") instanceof Boolean v ? v : null);
        collection.setLogging(body.get("logging") instanceof Boolean v ? v : null);
        collection.setHidden(body.get("hidden") instanceof Boolean v ? v : null);
        collection.setAutoGenId(body.get("autoGenId") instanceof Boolean v ? v : null);
        collection.setCreatedAt(body.get("createdAt") instanceof Boolean v ? v : null);
        collection.setUpdatedAt(body.get("updatedAt") instanceof Boolean v ? v : null);
        collection.setSql((String) body.get("sql"));
        collection.setInherits((String) body.get("inherits"));

        // Preserve options as JSON string
        Object optionsObj = body.get("options");
        if (optionsObj instanceof Map) {
            try {
                collection.setOptions(objectMapper.writeValueAsString(optionsObj));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid options JSON: " + SqlErrorSanitizer.sanitize(e.getMessage()));
            }
        } else if (optionsObj instanceof String) {
            collection.setOptions((String) optionsObj);
        }

        List<FieldEntity> fields = new ArrayList<>();
        Object fieldsObj = body.get("fields");
        if (fieldsObj instanceof List<?> fieldsList) {
            for (Object fieldObj : fieldsList) {
                if (fieldObj instanceof Map<?, ?> fieldMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fm = (Map<String, Object>) fieldMap;
                    FieldEntity field = buildFieldEntity(name, fm);
                    fields.add(field);
                }
            }
        }

        CollectionEntity saved = metadataService.createCollection(collection, fields);

        Map<String, Object> response = toCollectionResponseMap(saved);
        response.put("message", "Collection created successfully");
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Dynamic collection deletion.
     * POST /api/collections:destroy
     */
    @PostMapping({"/collections:destroy", "/collections/destroy"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> destroyCollection(@RequestParam(required = false) String filterByTk,
                                                @RequestBody(required = false) Map<String, Object> body) {
        String name = filterByTk;
        if (name == null && body != null) {
            name = (String) body.get("filterByTk");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Collection name is required");
        }

        metadataService.deleteCollection(name);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "name", name,
                "message", "Collection deleted successfully"
        )));
    }

    /**
     * Dynamic field addition.
     * POST /api/fields:create
     * Preserves all fields like options, uiSchema, interface, sort, etc.
     */
    @PostMapping({"/fields:create", "/fields/create"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> addField(@RequestBody Map<String, Object> body) {
        String collectionName = (String) body.get("collectionName");
        String fieldName = (String) body.get("name");
        String fieldType = (String) body.get("type");

        if (collectionName == null || fieldName == null || fieldType == null) {
            throw new IllegalArgumentException("collectionName, name, and type are required");
        }

        FieldEntity field = buildFieldEntity(collectionName, body);

        FieldEntity saved = metadataService.addField(field);

        return ResponseEntity.ok(ApiResponse.success(toFieldResponseMap(saved)));
    }

    /**
     * Dynamic field deletion.
     * POST /api/fields:destroy
     */
    @PostMapping({"/fields:destroy", "/fields/destroy"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> destroyField(@RequestBody Map<String, Object> body) {
        String collectionName = (String) body.get("collectionName");
        String fieldName = (String) body.get("name");

        if (collectionName == null || fieldName == null) {
            throw new IllegalArgumentException("collectionName and name are required");
        }

        metadataService.dropField(collectionName, fieldName);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "name", fieldName,
                "message", "Field deleted successfully"
        )));
    }

    /**
     * Dry-run collection creation.
     * POST /api/collections:dryRun
     * Returns an action/table/column/index summary with no SQL and no comments.
     */
    @PostMapping({"/collections:dryRun", "/collections/dryRun"})
    @PreAuthorize("hasRole('admin') or hasRole('root')")
    public ResponseEntity<?> dryRunCollection(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String title = (String) body.get("title");
        String type = (String) body.getOrDefault("type", "physical");

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Collection name is required");
        }

        CollectionEntity collection = new CollectionEntity(name, title != null ? title : name, type);
        String tableName = (String) body.getOrDefault("tableName", name);
        collection.setTableName(tableName);

        // Parse fields from body
        List<FieldEntity> fields = new ArrayList<>();
        Object fieldsObj = body.get("fields");
        if (fieldsObj instanceof List<?> fieldsList) {
            for (Object fieldObj : fieldsList) {
                if (fieldObj instanceof Map<?, ?> fieldMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fm = (Map<String, Object>) fieldMap;
                    FieldEntity field = buildFieldEntity(name, fm);
                    fields.add(field);
                }
            }
        }

        // Delegate to service layer which uses SchemaPlan via DdlSynchronizer.diff()
        List<Map<String, Object>> summary = metadataService.dryRun(collection, fields);

        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    /**
     * Convert a CollectionDefinition to the frontend-compatible map format.
     * Returns all collection fields including name, title, type, tableName, fields, etc.
     */
    private Map<String, Object> toCollectionMap(CollectionDefinition def) {
        List<Map<String, Object>> fieldList = def.getFields().values().stream()
                .map(f -> {
                    Map<String, Object> fieldMap = new LinkedHashMap<>();
                    fieldMap.put("name", f.getName());
                    fieldMap.put("type", f.getType());
                    fieldMap.put("interface", f.getInterfaceType() != null ? f.getInterfaceType() : "");
                    fieldMap.put("hidden", f.isHidden());
                    fieldMap.put("system", f.isSystem());
                    return fieldMap;
                })
                .collect(Collectors.toList());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", def.getName());
        map.put("title", def.getTitle() != null ? def.getTitle() : "");
        map.put("type", def.getType());
        map.put("tableName", def.getTableName());
        map.put("schema", def.getSchema());
        map.put("view", def.isView());
        map.put("hidden", def.isHidden());
        map.put("system", def.isSystem());
        map.put("sortable", def.isSortable());
        map.put("logging", def.isLogging());
        map.put("options", def.getOptions());
        map.put("fields", fieldList);
        return map;
    }

    /**
     * Build a FieldEntity from a map of field properties.
     * Preserves all extension fields like options, uiSchema, interface, sort, etc.
     */
    private FieldEntity buildFieldEntity(String collectionName, Map<String, Object> fieldMap) {
        String fieldName = (String) fieldMap.get("name");
        String fieldType = (String) fieldMap.get("type");

        if (fieldName == null || fieldType == null) {
            throw new IllegalArgumentException("Field name and type are required");
        }

        FieldEntity field = new FieldEntity(collectionName, fieldName, fieldType);
        field.setInterfaceType((String) fieldMap.get("interface"));
        field.setComponent((String) fieldMap.get("component"));
        field.setTarget((String) fieldMap.get("target"));
        field.setForeignKey((String) fieldMap.get("foreignKey"));
        field.setSourceKey((String) fieldMap.get("sourceKey"));
        field.setTargetKey((String) fieldMap.get("targetKey"));
        field.setThrough((String) fieldMap.get("through"));
        field.setOtherKey((String) fieldMap.get("otherKey"));

        // sortOrder
        Object sortOrderObj = fieldMap.get("sort");
        if (sortOrderObj instanceof Number) {
            field.setSortOrder(((Number) sortOrderObj).intValue());
        } else {
            Object sortOrderAlt = fieldMap.get("sortOrder");
            if (sortOrderAlt instanceof Number) {
                field.setSortOrder(((Number) sortOrderAlt).intValue());
            }
        }

        // hidden
        if (fieldMap.get("hidden") instanceof Boolean v) {
            field.setHidden(v);
        }

        // uiSchema - preserve as JSON string
        Object uiSchemaObj = fieldMap.get("uiSchema");
        if (uiSchemaObj instanceof Map) {
            try {
                field.setUiSchema(objectMapper.writeValueAsString(uiSchemaObj));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid uiSchema JSON for field '" + fieldName
                        + "': " + SqlErrorSanitizer.sanitize(e.getMessage()));
            }
        } else if (uiSchemaObj instanceof String) {
            field.setUiSchema((String) uiSchemaObj);
        }

        // options - preserve as JSON string
        Object optionsObj = fieldMap.get("options");
        if (optionsObj instanceof Map) {
            try {
                field.setOptions(objectMapper.writeValueAsString(optionsObj));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid options JSON for field '" + fieldName
                        + "': " + SqlErrorSanitizer.sanitize(e.getMessage()));
            }
        } else if (optionsObj instanceof String) {
            field.setOptions((String) optionsObj);
        }

        return field;
    }

    /**
     * Build a response map for a created collection.
     */
    private Map<String, Object> toCollectionResponseMap(CollectionEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("name", entity.getName());
        map.put("title", entity.getTitle());
        map.put("type", entity.getType());
        map.put("tableName", entity.getEffectiveTableName());
        map.put("schema", entity.getSchema());
        map.put("hidden", entity.getHidden());
        map.put("system", entity.getSystem());
        if (entity.getOptions() != null) {
            try {
                map.put("options", objectMapper.readValue(entity.getOptions(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                map.put("options", entity.getOptions());
            }
        }
        return map;
    }

    /**
     * Build a response map for a created field.
     */
    private Map<String, Object> toFieldResponseMap(FieldEntity field) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", field.getId());
        map.put("name", field.getName());
        map.put("type", field.getType());
        map.put("collectionName", field.getCollectionName());
        map.put("interface", field.getInterfaceType() != null ? field.getInterfaceType() : "");
        map.put("target", field.getTarget());
        map.put("foreignKey", field.getForeignKey());
        map.put("sourceKey", field.getSourceKey());
        map.put("targetKey", field.getTargetKey());
        map.put("through", field.getThrough());
        map.put("otherKey", field.getOtherKey());
        map.put("sortOrder", field.getSortOrder());
        map.put("hidden", field.getHidden());
        if (field.getOptions() != null) {
            try {
                map.put("options", objectMapper.readValue(field.getOptions(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                map.put("options", field.getOptions());
            }
        }
        if (field.getUiSchema() != null) {
            try {
                map.put("uiSchema", objectMapper.readValue(field.getUiSchema(),
                        new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                map.put("uiSchema", field.getUiSchema());
            }
        }
        return map;
    }
}