package com.nocobase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.runtime.CollectionCapability;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.runtime.IndexDefinition;
import com.nocobase.web.ForbiddenException;
import com.nocobase.web.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Unified entry point for collection and field metadata management.
 * Orchestrates metadata persistence, DDL execution, and runtime registry refresh.
 * All collection/field modifications MUST go through this service.
 *
 * <h3>Concurrency Control</h3>
 * <p>Per-collection-name {@link ReentrantLock} guards ensure that:
 * <ul>
 *   <li><b>createCollection:</b> Concurrent creates with the same collection name
 *       are serialized; the first one succeeds and subsequent callers get a 409
 *       (Conflict) because the name already exists after the first create.</li>
 *   <li><b>addField / dropField:</b> Concurrent field add/drop operations on the
 *       same collection are serialized, keeping the runtime registry and DB table
 *       consistent.</li>
 *   <li><b>DDL + reload ordering:</b> DDL is always executed BEFORE the runtime
 *       registry is reloaded. The lock ensures no other thread can read an
 *       inconsistent state between DDL and reload.</li>
 * </ul>
 */
@Service
public class CollectionMetadataService {

    private static final Logger log = LoggerFactory.getLogger(CollectionMetadataService.class);

    private final DdlSynchronizer ddlSynchronizer;
    private final CollectionRuntimeService runtimeService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    /** Per-collection-name locks for concurrency control. */
    private final ConcurrentHashMap<String, ReentrantLock> collectionLocks = new ConcurrentHashMap<>();

    public CollectionMetadataService(DdlSynchronizer ddlSynchronizer,
                                      CollectionRuntimeService runtimeService,
                                      ObjectMapper objectMapper,
                                      AuditLogService auditLogService) {
        this.ddlSynchronizer = ddlSynchronizer;
        this.runtimeService = runtimeService;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * Create a new collection with its fields.
     * Executes DDL, saves metadata, and refreshes the runtime registry.
     *
     * <p><b>Concurrency:</b> Uses a per-name lock. If two threads attempt to
     * create the same collection concurrently, only one succeeds. The second
     * gets a 409 Conflict because the collection already exists.
     */
    @Transactional
    public CollectionEntity createCollection(CollectionEntity collection, List<FieldEntity> fields) {
        String collectionName = collection.getName();
        log.info("Creating collection: {}", collectionName);

        // Validate collection type
        if (collection.getType() == null) {
            collection.setType("physical");
        }

        ReentrantLock lock = collectionLocks.computeIfAbsent(collectionName, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-check: the collection might have been created by another thread
            // while we were waiting for the lock
            if (runtimeService.exists(collectionName)) {
                throw new IllegalStateException("Collection already exists: " + collectionName);
            }

            // Step 1: Execute DDL (DDL is auto-committed in most DBs)
            // If DDL fails, metadata won't be saved (transaction rolls back the metadata save)
            CollectionEntity saved = ddlSynchronizer.createCollection(collection, fields);

            // Step 2: Refresh runtime registry (only after DDL succeeds)
            runtimeService.reload(collectionName);

            log.info("Collection created: {}", collectionName);
            auditLogService.auditSuccess("create", "collection", collectionName,
                    Map.of("name", collectionName, "type", collection.getType(),
                            "title", collection.getTitle() != null ? collection.getTitle() : ""));
            return saved;
        } finally {
            lock.unlock();
            // Clean up the lock if no one else is waiting on it
            if (!lock.hasQueuedThreads() && !lock.isLocked()) {
                collectionLocks.remove(collectionName, lock);
            }
        }
    }

    /**
     * Delete a collection and its table.
     *
     * <p><b>Concurrency:</b> Uses a per-name lock to prevent concurrent
     * modifications (addField/dropField) during deletion.
     */
    @Transactional
    public void deleteCollection(String collectionName) {
        log.info("Deleting collection: {}", collectionName);

        // Check capability
        var def = runtimeService.get(collectionName);
        if (def.isSystem()) {
            throw new ForbiddenException("Cannot delete system collection: " + collectionName);
        }

        ReentrantLock lock = collectionLocks.computeIfAbsent(collectionName, k -> new ReentrantLock());
        lock.lock();
        try {
            // Step 1: Execute DDL to drop the table
            ddlSynchronizer.dropCollection(collectionName);

            // Step 2: Refresh runtime registry (only after DDL succeeds)
            runtimeService.reload(collectionName);

            log.info("Collection deleted: {}", collectionName);
            auditLogService.auditSuccess("destroy", "collection", collectionName, Map.of());
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads() && !lock.isLocked()) {
                collectionLocks.remove(collectionName, lock);
            }
        }
    }

    /**
     * Add a field to an existing collection.
     *
     * <p><b>Concurrency:</b> Uses a per-collection-name lock. Concurrent
     * addField/dropField calls on the same collection are serialized, ensuring
     * the runtime registry and DB table stay consistent.
     */
    @Transactional
    public FieldEntity addField(FieldEntity field) {
        String collectionName = field.getCollectionName();
        String fieldName = field.getName();
        log.info("Adding field {}.{}", collectionName, fieldName);

        // Check capability
        var def = runtimeService.get(collectionName);
        var capability = CollectionCapability.forType(def.getType());
        if (!capability.isSchemaMutable()) {
            throw new ForbiddenException(
                "Cannot modify schema of " + def.getType() + " collection: " + collectionName);
        }

        ReentrantLock lock = collectionLocks.computeIfAbsent(collectionName, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-check: field might have been added by another thread
            if (def.getFields().containsKey(fieldName)) {
                throw new IllegalStateException(
                        "Field '" + fieldName + "' already exists in collection '" + collectionName + "'");
            }

            // Step 1: Execute DDL to add the column
            FieldEntity saved = ddlSynchronizer.addField(field);

            // Step 2: Refresh runtime registry (only after DDL succeeds)
            runtimeService.reload(collectionName);

            log.info("Field added: {}.{}", collectionName, fieldName);
            auditLogService.auditSuccess("create", "field", collectionName + "." + fieldName,
                    Map.of("collectionName", collectionName, "fieldName", fieldName,
                            "fieldType", field.getType()));
            return saved;
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads() && !lock.isLocked()) {
                collectionLocks.remove(collectionName, lock);
            }
        }
    }

    /**
     * Remove a field from a collection.
     *
     * <p><b>Concurrency:</b> Uses a per-collection-name lock. Concurrent
     * addField/dropField calls on the same collection are serialized, ensuring
     * the runtime registry and DB table stay consistent.
     */
    @Transactional
    public void dropField(String collectionName, String fieldName) {
        log.info("Dropping field {}.{}", collectionName, fieldName);

        var def = runtimeService.get(collectionName);
        var capability = CollectionCapability.forType(def.getType());
        if (!capability.isSchemaMutable()) {
            throw new ForbiddenException(
                "Cannot modify schema of " + def.getType() + " collection: " + collectionName);
        }

        ReentrantLock lock = collectionLocks.computeIfAbsent(collectionName, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-check: field might have been dropped by another thread
            if (!def.getFields().containsKey(fieldName)) {
                throw new ResourceNotFoundException("Field", collectionName + "." + fieldName);
            }

            // Step 1: Execute DDL to drop the column
            ddlSynchronizer.dropField(collectionName, fieldName);

            // Step 2: Refresh runtime registry (only after DDL succeeds)
            runtimeService.reload(collectionName);

            log.info("Field dropped: {}.{}", collectionName, fieldName);
            auditLogService.auditSuccess("destroy", "field", collectionName + "." + fieldName,
                    Map.of("collectionName", collectionName, "fieldName", fieldName));
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads() && !lock.isLocked()) {
                collectionLocks.remove(collectionName, lock);
            }
        }
    }

    /**
     * Dry-run collection creation: builds a summary of what would be created
     * without executing any DDL. Uses {@link DdlSynchronizer#diff} (which uses
     * {@link com.nocobase.ddl.SchemaPlan}) to check whether the table exists,
     * and {@link IndexDefinition#parse} for index definitions.
     *
     * @param collection the collection entity
     * @param fields     the field entities
     * @return list of summary maps (action, table, column, index entries)
     */
    public List<Map<String, Object>> dryRun(CollectionEntity collection, List<FieldEntity> fields) {
        List<Map<String, Object>> summary = new ArrayList<>();

        // Get SchemaPlan via DdlSynchronizer.diff() to check table existence
        List<IndexDefinition> indexDefs = IndexDefinition.parse(fields, collection, objectMapper);
        List<com.nocobase.ddl.SchemaPlan> plans = ddlSynchronizer.diff(collection, fields, indexDefs);

        boolean tableExists = plans.stream()
                .noneMatch(p -> p.getAction() == com.nocobase.ddl.SchemaPlan.Action.MISSING_TABLE);

        // 1. Action summary
        summary.add(Map.of(
                "action", "create",
                "collection", collection.getName(),
                "type", collection.getType(),
                "tableName", collection.getEffectiveTableName()
        ));

        // 2. Table summary (informed by SchemaPlan diff)
        summary.add(Map.of(
                "action", "table",
                "tableName", collection.getEffectiveTableName(),
                "exists", tableExists
        ));

        // 3. Column summary
        summary.add(Map.of("action", "column", "column", "id", "type", "BIGINT", "system", true));
        for (FieldEntity field : fields) {
            if (field.generatesPhysicalColumn()) {
                summary.add(Map.of(
                        "action", "column",
                        "column", field.getEffectiveColumnName(),
                        "type", field.getType(),
                        "system", false
                ));
            } else {
                summary.add(Map.of(
                        "action", "column",
                        "column", field.getName(),
                        "type", field.getType(),
                        "virtual", true
                ));
            }
        }
        summary.add(Map.of("action", "column", "column", "created_at", "type", "TIMESTAMP", "system", true));
        summary.add(Map.of("action", "column", "column", "updated_at", "type", "TIMESTAMP", "system", true));

        // 4. Index summary (from IndexDefinition.parse)
        for (IndexDefinition idx : indexDefs) {
            summary.add(Map.of(
                    "action", "index",
                    "name", idx.getName(),
                    "columns", idx.getColumnNames(),
                    "unique", idx.isUnique()
            ));
        }

        return summary;
    }

    /**
     * List all collections (for API compatibility).
     */
    public List<CollectionEntity> listCollections() {
        return List.copyOf(runtimeService.getAll().stream()
                .map(def -> {
                    CollectionEntity entity = new CollectionEntity();
                    entity.setName(def.getName());
                    entity.setTitle(def.getTitle());
                    entity.setType(def.getType());
                    entity.setSystem(def.isSystem());
                    entity.setHidden(def.isHidden());
                    return entity;
                })
                .toList());
    }
}