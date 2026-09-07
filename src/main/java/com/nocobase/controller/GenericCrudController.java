package com.nocobase.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.data.AssociationActionService;
import com.nocobase.data.DynamicRepository;
import com.nocobase.data.RelationQueryService;
import com.nocobase.sql.SqlErrorSanitizer;
import com.nocobase.web.ApiResponse;
import com.nocobase.web.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Generic CRUD Controller - handles NocoBase-style REST API.
 * URL is rewritten by NocobaseUrlFilter to /api/crud/{collection}/{action}.
 * All exceptions propagate to GlobalExceptionHandler for consistent error responses.
 */
@RestController
@RequestMapping("/api/crud")
public class GenericCrudController {

    @Autowired
    private DynamicRepository dynamicRepository;

    @Autowired
    private RelationQueryService relationQueryService;

    @Autowired
    private AssociationActionService associationActionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @RequestMapping("/{collection}/{action}")
    public ResponseEntity<?> handleRequest(
            @PathVariable String collection,
            @PathVariable String action,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false, defaultValue = "20") int pageSize,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "-id") String sort,
            @RequestParam(required = false) String appends,
            @RequestParam(required = false) String fields,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) String filterByTk) {

        // Check if this is an association resource (e.g., "posts.tags")
        if (collection.contains(".")) {
            return handleAssociation(collection, action, filterByTk, body);
        }

        return switch (action) {
            case "list" -> handleList(collection, filter, pageSize, page, sort, appends, fields);
            case "get" -> handleGet(collection, filterByTk, appends);
            case "create" -> handleCreate(collection, body);
            case "update" -> handleUpdate(collection, filterByTk, body);
            case "destroy" -> handleDestroy(collection, filterByTk);
            default -> ResponseEntity.ok(ApiResponse.success("action: " + action));
        };
    }

    private ResponseEntity<?> handleAssociation(String resource, String action,
                                                  String filterByTk, Map<String, Object> body) {
        // Validate filterByTk is present for all association actions
        if (filterByTk == null || filterByTk.isEmpty()) {
            throw new IllegalArgumentException("filterByTk (source record ID) is required for association actions");
        }
        Object sourceId;
        try {
            sourceId = Long.parseLong(filterByTk);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("filterByTk must be a numeric ID");
        }

        return switch (action) {
            case "list" -> {
                var result = associationActionService.list(resource, sourceId);
                yield ResponseEntity.ok(ApiResponse.success(result));
            }
            case "get" -> {
                var result = associationActionService.list(resource, sourceId);
                yield result.isEmpty()
                    ? ResponseEntity.ok(ApiResponse.success(null))
                    : ResponseEntity.ok(ApiResponse.success(result.get(0)));
            }
            case "add" -> {
                Object targetId = body != null ? body.get("targetId") : null;
                if (targetId == null) {
                    throw new IllegalArgumentException("targetId is required");
                }
                associationActionService.add(resource, sourceId, targetId);
                yield ResponseEntity.ok(ApiResponse.success(Map.of("message", "ok")));
            }
            case "remove" -> {
                Object targetId = body != null ? body.get("targetId") : null;
                if (targetId == null) {
                    throw new IllegalArgumentException("targetId is required");
                }
                associationActionService.remove(resource, sourceId, targetId);
                yield ResponseEntity.ok(ApiResponse.success(Map.of("message", "ok")));
            }
            case "set" -> {
                @SuppressWarnings("unchecked")
                List<Object> targetIds = body != null ? (List<Object>) body.get("targetIds") : null;
                associationActionService.set(resource, sourceId,
                        targetIds != null ? targetIds : List.of());
                yield ResponseEntity.ok(ApiResponse.success(Map.of("message", "ok")));
            }
            default -> throw new IllegalArgumentException("Unsupported association action: " + action);
        };
    }

    private ResponseEntity<?> handleList(String collection, String filter, int pageSize,
                                          int page, String sort, String appends, String fields) {
        Map<String, Object> filterMap = null;
        if (filter != null && !filter.isEmpty()) {
            try {
                filterMap = objectMapper.readValue(filter, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid filter JSON: "
                        + SqlErrorSanitizer.sanitize(e.getMessage() != null ? e.getMessage() : "Unknown error"));
            }
        }

        DynamicRepository.ListResult result = dynamicRepository.list(collection, filterMap, sort, page, pageSize, fields);

        if (appends != null && !appends.isEmpty()) {
            relationQueryService.appendRelations(collection, result.getData(), appends);
        }

        return ResponseEntity.ok(ApiResponse.list(result.getData(), result.getCount(), result.getPage(), result.getPageSize()));
    }

    private ResponseEntity<?> handleGet(String collection, String filterByTk, String appends) {
        Map<String, Object> record = dynamicRepository.get(collection, Long.parseLong(filterByTk));
        if (record == null) {
            throw new ResourceNotFoundException("Record", collection + "/" + filterByTk);
        }
        if (appends != null && !appends.isEmpty()) {
            relationQueryService.appendRelations(collection, List.of(record), appends);
        }
        return ResponseEntity.ok(ApiResponse.success(record));
    }

    private ResponseEntity<?> handleCreate(String collection, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("Request body is empty");
        }
        Map<String, Object> result = dynamicRepository.create(collection, body);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private ResponseEntity<?> handleUpdate(String collection, String filterByTk, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("Request body is empty");
        }
        Map<String, Object> result = dynamicRepository.update(collection, Long.parseLong(filterByTk), body);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private ResponseEntity<?> handleDestroy(String collection, String filterByTk) {
        dynamicRepository.destroy(collection, Long.parseLong(filterByTk));
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", filterByTk)));
    }
}