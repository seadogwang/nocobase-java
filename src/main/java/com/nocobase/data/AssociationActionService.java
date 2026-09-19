package com.nocobase.data;

import com.nocobase.acl.AclService;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.runtime.RelationDefinition;
import com.nocobase.service.AuditLogService;
import com.nocobase.web.ForbiddenException;
import com.nocobase.web.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles NocoBase association resource actions.
 * All data access goes through DynamicRepository -- no direct JdbcTemplate usage.
 */
@Service
public class AssociationActionService {

    private static final Logger log = LoggerFactory.getLogger(AssociationActionService.class);

    /**
     * Maximum number of items to include in a single $in clause.
     * Prevents overly large SQL queries. Larger sets are batched into multiple queries.
     */
    private static final int SAFE_BATCH_SIZE = 1000;

    private final CollectionRuntimeService runtimeService;
    private final DynamicRepository dynamicRepository;
    private final AclService aclService;
    private final AuditLogService auditLogService;

    public AssociationActionService(CollectionRuntimeService runtimeService,
                                     DynamicRepository dynamicRepository,
                                     AclService aclService,
                                     AuditLogService auditLogService) {
        this.runtimeService = runtimeService;
        this.dynamicRepository = dynamicRepository;
        this.aclService = aclService;
        this.auditLogService = auditLogService;
    }

    public static AssociationRequest parse(String resourceName) {
        int dotIndex = resourceName.indexOf('.');
        if (dotIndex < 0) {
            throw new IllegalArgumentException("Association resource must contain '.': " + resourceName);
        }
        return new AssociationRequest(
                resourceName.substring(0, dotIndex),
                resourceName.substring(dotIndex + 1));
    }

    // --- list ---

    public List<Map<String, Object>> list(String resourceName, Object sourceId) {
        AssociationRequest req = parse(resourceName);
        CollectionDefinition sourceDef = runtimeService.get(req.source());
        RelationDefinition rel = getRelation(sourceDef, req.association());

        checkAcl(sourceDef.getName(), "get");
        checkAcl(rel.getTargetCollection(), "list");

        // Verify source record is in scope
        Map<String, Object> sourceRecord = dynamicRepository.get(sourceDef.getName(), sourceId);
        if (sourceRecord == null) {
            throw new ResourceNotFoundException("Record", sourceDef.getName() + "/" + sourceId);
        }

        return switch (rel.getType()) {
            case "belongsTo" -> listBelongsTo(rel, sourceRecord);
            case "hasOne" -> listHasOne(rel, sourceId);
            case "hasMany" -> listHasMany(rel, sourceId);
            case "belongsToMany" -> listBelongsToMany(rel, sourceId);
            default -> throw new IllegalArgumentException("Unsupported relation type: " + rel.getType());
        };
    }

    // --- add ---

    @Transactional
    public void add(String resourceName, Object sourceId, Object targetId) {
        try {
            AssociationRequest req = parse(resourceName);
            CollectionDefinition sourceDef = runtimeService.get(req.source());

            // P1-H: SQL collection add/remove/set must be rejected
            checkNotSqlCollection(sourceDef, "add");

            RelationDefinition rel = getRelation(sourceDef, req.association());
            CollectionDefinition targetDef = runtimeService.get(rel.getTargetCollection());
            checkNotSqlCollection(targetDef, "add");

            // P1-D2: Cross-datasource write must be rejected
            checkNotCrossDatasource(sourceDef, targetDef, "add");

            checkAcl(sourceDef.getName(), "update");
            checkAcl(rel.getTargetCollection(), "update");

            verifyInScope(sourceDef.getName(), sourceId);
            verifyInScope(rel.getTargetCollection(), targetId);

            switch (rel.getType()) {
                case "belongsToMany" -> addBelongsToMany(rel, sourceId, targetId);
                case "hasMany" -> addHasMany(rel, sourceId, targetId);
                default -> throw new IllegalArgumentException(
                    "add not supported for relation type: " + rel.getType());
            }

            auditLogService.auditSuccess("add", "association", resourceName,
                    Map.of("sourceId", sourceId, "targetId", targetId));
        } catch (Exception e) {
            auditLogService.auditFailure("add", "association", resourceName,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    // --- remove ---

    @Transactional
    public void remove(String resourceName, Object sourceId, Object targetId) {
        try {
            AssociationRequest req = parse(resourceName);
            CollectionDefinition sourceDef = runtimeService.get(req.source());

            // P1-H: SQL collection add/remove/set must be rejected
            checkNotSqlCollection(sourceDef, "remove");

            RelationDefinition rel = getRelation(sourceDef, req.association());
            CollectionDefinition targetDef = runtimeService.get(rel.getTargetCollection());
            checkNotSqlCollection(targetDef, "remove");

            // P1-D2: Cross-datasource write must be rejected
            checkNotCrossDatasource(sourceDef, targetDef, "remove");

            checkAcl(sourceDef.getName(), "update");
            checkAcl(rel.getTargetCollection(), "update");

            verifyInScope(sourceDef.getName(), sourceId);
            verifyInScope(rel.getTargetCollection(), targetId);

            switch (rel.getType()) {
                case "belongsToMany" -> removeBelongsToMany(rel, sourceId, targetId);
                case "hasMany" -> removeHasMany(rel, sourceId, targetId);
                default -> throw new IllegalArgumentException(
                    "remove not supported for relation type: " + rel.getType());
            }

            auditLogService.auditSuccess("remove", "association", resourceName,
                    Map.of("sourceId", sourceId, "targetId", targetId));
        } catch (Exception e) {
            auditLogService.auditFailure("remove", "association", resourceName,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    // --- set ---

    @Transactional
    public void set(String resourceName, Object sourceId, List<Object> targetIds) {
        try {
            AssociationRequest req = parse(resourceName);
            CollectionDefinition sourceDef = runtimeService.get(req.source());

            // P1-H: SQL collection add/remove/set must be rejected
            checkNotSqlCollection(sourceDef, "set");

            RelationDefinition rel = getRelation(sourceDef, req.association());
            CollectionDefinition targetDef = runtimeService.get(rel.getTargetCollection());
            checkNotSqlCollection(targetDef, "set");

            // P1-D2: Cross-datasource write must be rejected
            checkNotCrossDatasource(sourceDef, targetDef, "set");

            checkAcl(sourceDef.getName(), "update");
            checkAcl(rel.getTargetCollection(), "update");

            verifyInScope(sourceDef.getName(), sourceId);

            switch (rel.getType()) {
                case "belongsToMany" -> setBelongsToMany(rel, sourceId, targetIds);
                case "hasMany" -> setHasMany(rel, sourceId, targetIds);
                case "belongsTo" -> setBelongsTo(rel, sourceDef, sourceId,
                        targetIds != null && !targetIds.isEmpty() ? targetIds.get(0) : null);
                default -> throw new IllegalArgumentException(
                    "set not supported for relation type: " + rel.getType());
            }

            auditLogService.auditSuccess("set", "association", resourceName,
                    Map.of("sourceId", sourceId, "targetCount", targetIds != null ? targetIds.size() : 0));
        } catch (Exception e) {
            auditLogService.auditFailure("set", "association", resourceName,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    // --- BelongsTo ---

    private List<Map<String, Object>> listBelongsTo(RelationDefinition rel, Map<String, Object> sourceRecord) {
        Object fkValue = sourceRecord.get(rel.getForeignKey());
        if (fkValue == null) return List.of();
        Map<String, Object> target = dynamicRepository.get(rel.getTargetCollection(), fkValue);
        return target != null ? List.of(target) : List.of();
    }

    private void setBelongsTo(RelationDefinition rel, CollectionDefinition sourceDef,
                               Object sourceId, Object targetId) {
        if (targetId != null) {
            verifyInScope(rel.getTargetCollection(), targetId);
        }
        dynamicRepository.update(sourceDef.getName(), sourceId,
                mapOf(rel.getForeignKey(), targetId));
    }

    // --- HasOne / HasMany ---

    private List<Map<String, Object>> listHasOne(RelationDefinition rel, Object sourceId) {
        return listViaFilter(rel, sourceId);
    }

    private List<Map<String, Object>> listHasMany(RelationDefinition rel, Object sourceId) {
        return listViaFilter(rel, sourceId);
    }

    private void addHasMany(RelationDefinition rel, Object sourceId, Object targetId) {
        dynamicRepository.update(rel.getTargetCollection(), targetId,
                mapOf(rel.getForeignKey(), sourceId));
    }

    private void removeHasMany(RelationDefinition rel, Object sourceId, Object targetId) {
        dynamicRepository.update(rel.getTargetCollection(), targetId,
                mapOf(rel.getForeignKey(), null));
    }

    private void setHasMany(RelationDefinition rel, Object sourceId, List<Object> targetIds) {
        // Clear existing: update all records with this FK to null
        dynamicRepository.updateByFilterForAction(rel.getTargetCollection(), "update",
                mapOf(rel.getForeignKey(), null),
                Map.of(rel.getForeignKey(), Map.of("$eq", sourceId)));

        // Set new targets
        if (targetIds != null) {
            for (Object targetId : targetIds) {
                verifyInScope(rel.getTargetCollection(), targetId);
                dynamicRepository.update(rel.getTargetCollection(), targetId,
                        mapOf(rel.getForeignKey(), sourceId));
            }
        }
    }

    // --- BelongsToMany ---

    private List<Map<String, Object>> listBelongsToMany(RelationDefinition rel, Object sourceId) {
        // Query through table to get target IDs
        var throughResult = dynamicRepository.listLinks(
                rel.getThrough(), rel.getForeignKey(), List.of(sourceId), rel.getOtherKey());

        List<Object> targetIds = throughResult.getData().stream()
                .map(r -> r.get(rel.getOtherKey()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (targetIds.isEmpty()) {
            return List.of();
        }

        // Batch $in queries to avoid overly large SQL, then use listAllInternal for full pagination
        List<Map<String, Object>> allResults = new ArrayList<>();
        for (int i = 0; i < targetIds.size(); i += SAFE_BATCH_SIZE) {
            int end = Math.min(i + SAFE_BATCH_SIZE, targetIds.size());
            List<Object> batch = targetIds.subList(i, end);
            Map<String, Object> filter = Map.of(rel.getTargetKey(), Map.of("$in", batch));
            allResults.addAll(dynamicRepository.listAllInternal(rel.getTargetCollection(), "list", filter, null, null));
        }
        return allResults;
    }

    private void addBelongsToMany(RelationDefinition rel, Object sourceId, Object targetId) {
        dynamicRepository.createLink(rel.getThrough(),
                rel.getForeignKey(), sourceId, rel.getOtherKey(), targetId);
    }

    private void removeBelongsToMany(RelationDefinition rel, Object sourceId, Object targetId) {
        dynamicRepository.deleteLink(rel.getThrough(),
                rel.getForeignKey(), sourceId, rel.getOtherKey(), targetId);
    }

    private void setBelongsToMany(RelationDefinition rel, Object sourceId, List<Object> targetIds) {
        if (targetIds != null) {
            for (Object targetId : targetIds) {
                verifyInScope(rel.getTargetCollection(), targetId);
            }
        }
        dynamicRepository.replaceLinks(rel.getThrough(),
                rel.getForeignKey(), sourceId, rel.getOtherKey(),
                targetIds != null ? targetIds : List.of());
    }

    // --- Helpers ---

    /**
     * List associated records via DynamicRepository with filter on foreign key.
     * Uses listAllInternal for full pagination so results are not truncated
     * by maxPageSize (e.g., SQL collections). ACL scope and field filtering are applied automatically.
     */
    private List<Map<String, Object>> listViaFilter(RelationDefinition rel, Object sourceId) {
        Map<String, Object> filter = Map.of(rel.getForeignKey(), Map.of("$eq", sourceId));
        return dynamicRepository.listAllInternal(rel.getTargetCollection(), "list", filter, null, null);
    }

    private void checkAcl(String resourceName, String action) {
        if (!aclService.canAction(resourceName, action)) {
            throw new ForbiddenException(
                "No permission to perform '" + action + "' on '" + resourceName + "'");
        }
    }

    private void verifyInScope(String collectionName, Object recordId) {
        if (!dynamicRepository.existsInScope(collectionName, "update", recordId)) {
            throw new ForbiddenException(
                "Record " + recordId + " not found or not in scope in '" + collectionName + "'");
        }
    }

    /**
     * P1-H: Reject association add/remove/set on SQL collections.
     * SQL collections are read-only -- writes are forbidden.
     */
    private void checkNotSqlCollection(CollectionDefinition def, String action) {
        if (def.isSql()) {
            throw new ForbiddenException(
                    "Cannot " + action + " associations on SQL collection '" + def.getName() + "'"
                            + " -- SQL collections are read-only");
        }
    }

    /**
     * P1-D2: Reject cross-datasource writes.
     * Association writes (add/remove/set) are only allowed when both the source
     * and target collections are on the same data source.
     */
    private void checkNotCrossDatasource(CollectionDefinition sourceDef,
                                          CollectionDefinition targetDef, String action) {
        String sourceDs = sourceDef.getDataSourceKey();
        String targetDs = targetDef.getDataSourceKey();
        if (!sourceDs.equals(targetDs)) {
            throw new ForbiddenException(
                    "Cannot " + action + " associations across data sources: source '"
                    + sourceDef.getName() + "' is on data source '" + sourceDs
                    + "' but target '" + targetDef.getName() + "' is on data source '"
                    + targetDs + "'");
        }
    }

    private RelationDefinition getRelation(CollectionDefinition def, String associationName) {
        RelationDefinition rel = def.getRelation(associationName);
        if (rel == null) {
            throw new ResourceNotFoundException("Relation", def.getName() + "." + associationName);
        }
        return rel;
    }

    public record AssociationRequest(String source, String association) {}

    /** Create a Map that allows null values (unlike Map.of). */
    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}