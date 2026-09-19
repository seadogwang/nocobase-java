package com.nocobase.data;

import com.nocobase.acl.AclService;
import com.nocobase.runtime.CollectionDefinition;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.runtime.RelationDefinition;
import com.nocobase.web.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles relation field queries (appends).
 * All data access goes through DynamicRepository -- no direct JdbcTemplate usage.
 */
@Service
public class RelationQueryService {

    private static final Logger log = LoggerFactory.getLogger(RelationQueryService.class);

    /**
     * Maximum number of items to include in a single $in clause.
     * Prevents overly large SQL queries and avoids Integer.MAX_VALUE overflow.
     * Larger sets are batched into multiple queries automatically.
     */
    private static final int SAFE_BATCH_SIZE = 1000;

    private final CollectionRuntimeService runtimeService;
    private final AclService aclService;
    private final DynamicRepository dynamicRepository;

    public RelationQueryService(CollectionRuntimeService runtimeService,
                                AclService aclService,
                                DynamicRepository dynamicRepository) {
        this.runtimeService = runtimeService;
        this.aclService = aclService;
        this.dynamicRepository = dynamicRepository;
    }

    /**
     * Append related data to a list of records.
     */
    public void appendRelations(String collectionName, List<Map<String, Object>> records, String appends) {
        if (appends == null || appends.isEmpty() || records.isEmpty()) {
            return;
        }

        CollectionDefinition def = runtimeService.get(collectionName);
        String[] appendFields = appends.split(",");

        for (String fieldName : appendFields) {
            fieldName = fieldName.trim();
            if (fieldName.isEmpty()) continue;

            RelationDefinition rel = def.getRelation(fieldName);
            if (rel == null) {
                throw new IllegalArgumentException(
                    "Unknown relation field '" + fieldName + "' in collection '" + collectionName + "'");
            }

            checkAcl(rel.getTargetCollection());

            switch (rel.getType()) {
                case "belongsTo" -> appendBelongsTo(records, rel);
                case "hasOne" -> appendHasOne(records, rel);
                case "hasMany" -> appendHasMany(records, rel);
                case "belongsToMany" -> appendBelongsToMany(records, rel);
                default -> throw new IllegalArgumentException(
                    "Unsupported relation type: " + rel.getType());
            }
        }
    }

    /**
     * belongsTo: query target collection by primary key via DynamicRepository.
     */
    private void appendBelongsTo(List<Map<String, Object>> records, RelationDefinition rel) {
        String fk = rel.getForeignKey();
        String targetKey = rel.getTargetKey();

        List<Object> fkValues = records.stream()
                .map(r -> r.get(fk))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (fkValues.isEmpty()) return;

        // Query via DynamicRepository with $in filter -- ACL scope and field filtering applied automatically
        // P0-D: batch $in queries to avoid Integer.MAX_VALUE / overly large SQL
        Map<Object, Map<String, Object>> relatedMap = batchListByKey(
                rel.getTargetCollection(), targetKey, fkValues, null);

        for (Map<String, Object> record : records) {
            Object fkValue = record.get(fk);
            record.put(rel.getName(), relatedMap.getOrDefault(fkValue, null));
        }
    }

    /**
     * hasOne: query target collection by foreign key via DynamicRepository.
     */
    private void appendHasOne(List<Map<String, Object>> records, RelationDefinition rel) {
        String sourceKey = rel.getSourceKey();
        String fk = rel.getForeignKey();

        List<Object> sourceValues = records.stream()
                .map(r -> r.get(sourceKey))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (sourceValues.isEmpty()) return;

        // P0-D: batch $in queries to avoid Integer.MAX_VALUE / overly large SQL
        Map<Object, Map<String, Object>> relatedMap = batchListByKey(
                rel.getTargetCollection(), fk, sourceValues, null);

        for (Map<String, Object> record : records) {
            Object sourceValue = record.get(sourceKey);
            record.put(rel.getName(), relatedMap.getOrDefault(sourceValue, null));
        }
    }

    /**
     * hasMany: query target collection by foreign key via DynamicRepository.
     */
    private void appendHasMany(List<Map<String, Object>> records, RelationDefinition rel) {
        String sourceKey = rel.getSourceKey();
        String fk = rel.getForeignKey();

        List<Object> sourceValues = records.stream()
                .map(r -> r.get(sourceKey))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (sourceValues.isEmpty()) return;

        // P0-D: batch $in queries to avoid Integer.MAX_VALUE / overly large SQL
        List<Map<String, Object>> allRelated = batchList(
                rel.getTargetCollection(), fk, sourceValues, null);

        Map<Object, List<Map<String, Object>>> grouped = new HashMap<>();
        for (Map<String, Object> r : allRelated) {
            Object fkValue = r.get(fk);
            grouped.computeIfAbsent(fkValue, k -> new ArrayList<>()).add(r);
        }

        for (Map<String, Object> record : records) {
            Object sourceValue = record.get(sourceKey);
            record.put(rel.getName(), grouped.getOrDefault(sourceValue, Collections.emptyList()));
        }
    }

    /**
     * belongsToMany: query through table and target table via DynamicRepository.
     */
    private void appendBelongsToMany(List<Map<String, Object>> records, RelationDefinition rel) {
        String throughTable = rel.getThrough();
        String sourceKey = rel.getSourceKey();
        String fk = rel.getForeignKey();
        String otherKey = rel.getOtherKey();
        String targetKey = rel.getTargetKey();

        List<Object> sourceValues = records.stream()
                .map(r -> r.get(sourceKey))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (sourceValues.isEmpty()) return;

        // Query through table via internal API (no through table permission/scope)
        DynamicRepository.ListResult throughResult = dynamicRepository.listLinks(
                throughTable, fk, sourceValues, otherKey);

        Set<Object> targetIds = throughResult.getData().stream()
                .map(r -> r.get(otherKey))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (targetIds.isEmpty()) {
            for (Map<String, Object> record : records) {
                record.put(rel.getName(), Collections.emptyList());
            }
            return;
        }

        // Query target table via DynamicRepository -- P0-D: batch $in queries
        Map<Object, Map<String, Object>> targetMap = batchListByKey(
                rel.getTargetCollection(), targetKey, new ArrayList<>(targetIds), null);

        // Group through rows by source key
        Map<Object, List<Object>> throughGrouped = new HashMap<>();
        for (Map<String, Object> row : throughResult.getData()) {
            Object srcVal = row.get(fk);
            Object tgtVal = row.get(otherKey);
            throughGrouped.computeIfAbsent(srcVal, k -> new ArrayList<>()).add(tgtVal);
        }

        for (Map<String, Object> record : records) {
            Object sourceValue = record.get(sourceKey);
            List<Object> targetIdList = throughGrouped.getOrDefault(sourceValue, Collections.emptyList());
            List<Map<String, Object>> relatedRecords = targetIdList.stream()
                    .map(targetMap::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            record.put(rel.getName(), relatedRecords);
        }
    }

    /**
     * Batch query using $in filter, splitting large value lists into chunks
     * to avoid overly large SQL queries. Uses listAllInternal for full pagination
     * so results are not truncated by maxPageSize (e.g., SQL collections).
     * Returns a flat list of all matching records.
     */
    private List<Map<String, Object>> batchList(String collectionName, String inKey,
                                                List<Object> inValues, String sort) {
        List<Map<String, Object>> allResults = new ArrayList<>();
        for (int i = 0; i < inValues.size(); i += SAFE_BATCH_SIZE) {
            int end = Math.min(i + SAFE_BATCH_SIZE, inValues.size());
            List<Object> batch = inValues.subList(i, end);
            Map<String, Object> filter = Map.of(inKey, Map.of("$in", batch));
            allResults.addAll(dynamicRepository.listAllInternal(collectionName, "list", filter, sort, null));
        }
        return allResults;
    }

    /**
     * Batch query using $in filter, returning a map keyed by the inKey column.
     * First matching record wins per key. Uses listAllInternal for full pagination
     * so results are not truncated by maxPageSize (e.g., SQL collections).
     */
    private Map<Object, Map<String, Object>> batchListByKey(String collectionName, String inKey,
                                                            List<Object> inValues, String sort) {
        Map<Object, Map<String, Object>> resultMap = new HashMap<>();
        for (int i = 0; i < inValues.size(); i += SAFE_BATCH_SIZE) {
            int end = Math.min(i + SAFE_BATCH_SIZE, inValues.size());
            List<Object> batch = inValues.subList(i, end);
            Map<String, Object> filter = Map.of(inKey, Map.of("$in", batch));
            List<Map<String, Object>> rows = dynamicRepository.listAllInternal(collectionName, "list", filter, sort, null);
            for (Map<String, Object> r : rows) {
                resultMap.putIfAbsent(r.get(inKey), r);
            }
        }
        return resultMap;
    }

    private void checkAcl(String targetCollection) {
        if (!aclService.canAction(targetCollection, "list")) {
            throw new ForbiddenException(
                "No permission to read related collection: " + targetCollection);
        }
    }
}