package com.nocobase.controller;

import com.nocobase.service.UiSchemaService;
import com.nocobase.web.ApiResponse;
import com.nocobase.web.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * UI Schema Controller.
 * All repository access is delegated to UiSchemaService.
 * Transaction boundaries are in the service layer.
 */
@RestController
@RequestMapping("/api")
public class UiSchemaController {

    @Autowired
    private UiSchemaService uiSchemaService;

    // --- getTree (full tree from root) ---

    @GetMapping({"/uiSchemas:getTree", "/uiSchemas/getTree"})
    public ResponseEntity<?> getTree() {
        Map<String, Object> tree = uiSchemaService.getTree();
        return ResponseEntity.ok(ApiResponse.success(tree));
    }

    // --- getTreeByUid (tree from a specific uid) ---

    @GetMapping({"/uiSchemas:getTreeByUid", "/uiSchemas/getTreeByUid"})
    public ResponseEntity<?> getTreeByUid(@RequestParam String uid) {
        Map<String, Object> tree = uiSchemaService.getTreeByUid(uid);
        return ResponseEntity.ok(ApiResponse.success(tree));
    }

    // --- getTreeBySchemaUid (tree from a specific schemaUid) ---

    @GetMapping({"/uiSchemas:getTreeBySchemaUid", "/uiSchemas/getTreeBySchemaUid"})
    public ResponseEntity<?> getTreeBySchemaUid(@RequestParam String schemaUid) {
        Map<String, Object> tree = uiSchemaService.getTreeBySchemaUid(schemaUid);
        return ResponseEntity.ok(ApiResponse.success(tree));
    }

    // --- getJsonSchema ---

    @GetMapping({"/uiSchemas:getJsonSchema", "/uiSchemas/getJsonSchema"})
    public ResponseEntity<?> getJsonSchema(@RequestParam String uid) {
        Map<String, Object> schema = uiSchemaService.getJsonSchema(uid);
        return ResponseEntity.ok(ApiResponse.success(schema));
    }

    // --- getParentJsonSchema ---

    @GetMapping({"/uiSchemas:getParentJsonSchema", "/uiSchemas/getParentJsonSchema"})
    public ResponseEntity<?> getParentJsonSchema(@RequestParam String uid) {
        Map<String, Object> schema = uiSchemaService.getParentJsonSchema(uid);
        return ResponseEntity.ok(ApiResponse.success(schema));
    }

    // --- insertAdjacent ---

    /**
     * Insert a new UI Schema node adjacent to a target node.
     *
     * <p><b>Uniqueness guarantees:</b>
     * <ul>
     *   <li>uid (x-uid) must be unique across all ui_schemas rows. If the x-uid
     *       already exists, the operation fails immediately with a 400 error.</li>
     *   <li>schemaUid is set equal to uid by default. It represents the
     *       "schema-level" identity of this node. A node can reference another
     *       node's schema by setting schemaUid to that node's uid.</li>
     * </ul>
     */
    @PostMapping({"/uiSchemas:insertAdjacent", "/uiSchemas/insertAdjacent"})
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<?> insertAdjacent(@RequestBody Map<String, Object> body) {
        String targetUid = (String) body.get("targetUid");
        String position = (String) body.get("position");
        Object schemaObj = body.get("schema");

        if (targetUid == null || schemaObj == null) {
            throw new IllegalArgumentException("targetUid and schema are required");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (schemaObj instanceof Map)
                ? (Map<String, Object>) schemaObj
                : uiSchemaService.parseSchema(schemaObj.toString());

        Map<String, Object> result = uiSchemaService.insertAdjacent(targetUid, position, schema);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // --- patch ---

    @PostMapping({"/uiSchemas:patch", "/uiSchemas/patch"})
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<?> patch(@RequestBody Map<String, Object> body) {
        String uid = (String) body.get("uid");
        Object schemaObj = body.get("schema");

        if (uid == null) {
            throw new IllegalArgumentException("uid is required");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = null;
        if (schemaObj != null) {
            schema = (schemaObj instanceof Map)
                    ? (Map<String, Object>) schemaObj
                    : uiSchemaService.parseSchema(schemaObj.toString());
        }

        String name = (String) body.get("name");

        Map<String, Object> result = uiSchemaService.patch(uid, schema, name);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // --- remove (recursive, supports deep trees) ---

    /**
     * Remove a UI Schema node and all its descendants.
     */
    @PostMapping({"/uiSchemas:remove", "/uiSchemas/remove"})
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<?> remove(@RequestParam(required = false) String uid,
                                     @RequestBody(required = false) Map<String, Object> body) {
        String targetUid = uid;
        if (targetUid == null && body != null) {
            targetUid = (String) body.get("uid");
        }
        if (targetUid == null) {
            throw new IllegalArgumentException("uid is required");
        }

        final String finalUid = targetUid;
        if (!uiSchemaService.existsByUid(finalUid)) {
            throw new ResourceNotFoundException("UI Schema", finalUid);
        }

        uiSchemaService.deleteRecursiveByUid(finalUid);
        return ResponseEntity.ok(ApiResponse.success(Map.of("uid", finalUid)));
    }

    // --- UI Schema Templates ---

    @GetMapping({"/uiSchemaTemplates:list", "/uiSchemaTemplates/list"})
    public ResponseEntity<?> listTemplates() {
        List<Map<String, Object>> templates = uiSchemaService.listTemplates();
        return ResponseEntity.ok(ApiResponse.success(templates));
    }

    @GetMapping({"/uiSchemaTemplates:get", "/uiSchemaTemplates/get"})
    public ResponseEntity<?> getTemplate(@RequestParam String name) {
        Map<String, Object> template = uiSchemaService.getTemplate(name);
        return ResponseEntity.ok(ApiResponse.success(template));
    }
}