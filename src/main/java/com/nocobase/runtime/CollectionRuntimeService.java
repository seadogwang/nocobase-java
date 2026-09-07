package com.nocobase.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.ddl.DdlSynchronizer;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.repository.CollectionRepository;
import com.nocobase.repository.FieldRepository;
import com.nocobase.sql.SqlErrorSanitizer;
import com.nocobase.sql.SqlIdentifier;
import com.nocobase.sql.SqlParameterMetadata;
import com.nocobase.sql.SqlQueryCollectionExecutor;
import com.nocobase.sql.SqlDataSourceResolver;
import com.nocobase.sql.SqlValidator;
import com.nocobase.web.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry for all collection definitions.
 * Loaded from metadata at startup and refreshable at runtime.
 * All controllers and repositories must use this service to get collection info.
 */
@Service
public class CollectionRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(CollectionRuntimeService.class);

    private final CollectionRepository collectionRepository;
    private final FieldRepository fieldRepository;
    private final ObjectMapper objectMapper;
    private final DdlSynchronizer ddlSynchronizer;
    private final SqlQueryCollectionExecutor sqlQueryCollectionExecutor;
    private final SqlDataSourceResolver dataSourceResolver;

    /** Thread-safe cache of collection definitions */
    private final Map<String, CollectionDefinition> registry = new ConcurrentHashMap<>();

    /** Map of collection name to sanitized error message for collections that failed to load */
    private final Map<String, String> invalidCollections = new ConcurrentHashMap<>();

    public CollectionRuntimeService(CollectionRepository collectionRepository,
                                     FieldRepository fieldRepository,
                                     ObjectMapper objectMapper,
                                     DdlSynchronizer ddlSynchronizer,
                                     SqlQueryCollectionExecutor sqlQueryCollectionExecutor,
                                     SqlDataSourceResolver dataSourceResolver) {
        this.collectionRepository = collectionRepository;
        this.fieldRepository = fieldRepository;
        this.objectMapper = objectMapper;
        this.ddlSynchronizer = ddlSynchronizer;
        this.sqlQueryCollectionExecutor = sqlQueryCollectionExecutor;
        this.dataSourceResolver = dataSourceResolver;
    }

    /**
     * Load all collections from metadata into the runtime registry.
     */
    public void loadAll() {
        invalidCollections.clear();
        List<CollectionEntity> collections = collectionRepository.findAll();
        log.info("Loading {} collections into runtime registry", collections.size());

        Map<String, CollectionDefinition> newRegistry = new ConcurrentHashMap<>();
        for (CollectionEntity entity : collections) {
            try {
                CollectionDefinition def = buildDefinition(entity);
                // Sync indexes BEFORE adding to registry — if this fails, the collection is invalid
                syncIndexesForEntity(entity);
                newRegistry.put(entity.getName(), def);
            } catch (Exception e) {
                log.error("Failed to build definition for collection '{}': {} [{}]",
                        entity.getName(), sanitizeErrorMessage(e.getMessage()), e.getClass().getSimpleName());
                invalidCollections.put(entity.getName(), sanitizeErrorMessage(e.getMessage()));
            }
        }
        registry.clear();
        registry.putAll(newRegistry);
        log.info("Runtime registry loaded with {} collections, {} invalid skipped",
                registry.size(), invalidCollections.size());
    }

    /**
     * Reload a single collection.
     * On success: updates the runtime registry and removes any stale invalid-collection entry.
     * On failure: removes the old definition from the registry, tracks the sanitized error
     * in invalidCollections, and re-throws so callers can detect the failure.
     */
    public void reload(String collectionName) {
        collectionRepository.findByName(collectionName).ifPresentOrElse(
                entity -> {
                    try {
                        // For SQL collections, refresh the data source before building
                        // to allow re-validation of previously unavailable data sources
                        if ("sql".equals(entity.getType())) {
                            String dataSourceKey = extractDataSourceKeyFromEntity(entity);
                            if (dataSourceKey != null && !"main".equals(dataSourceKey)) {
                                dataSourceResolver.refreshDataSource(dataSourceKey);
                            }
                        }
                        CollectionDefinition def = buildDefinition(entity);
                        // Sync indexes BEFORE updating registry — if this fails, the collection is marked invalid
                        syncIndexesForEntity(entity);
                        registry.put(collectionName, def);
                        invalidCollections.remove(collectionName);
                        log.info("Reloaded collection: {}", collectionName);
                    } catch (Exception e) {
                        // Remove stale definition from registry
                        registry.remove(collectionName);
                        // Track the failure with sanitized error
                        invalidCollections.put(collectionName, sanitizeErrorMessage(e.getMessage()));
                        log.error("Failed to reload collection '{}': {} [{}]",
                                collectionName, sanitizeErrorMessage(e.getMessage()), e.getClass().getSimpleName());
                        // Re-throw so callers can detect the failure
                        throw new RuntimeException(
                                "Failed to reload collection '" + collectionName + "': "
                                + sanitizeErrorMessage(e.getMessage()), e);
                    }
                },
                () -> {
                    registry.remove(collectionName);
                    log.info("Removed collection from registry: {}", collectionName);
                }
        );
    }

    /**
     * Reload all collections.
     */
    public void reloadAll() {
        loadAll();
    }

    /**
     * Get a collection definition. Throws if not found.
     */
    public CollectionDefinition get(String collectionName) {
        CollectionDefinition def = registry.get(collectionName);
        if (def == null) {
            throw new ResourceNotFoundException("Collection", collectionName);
        }
        return def;
    }

    /**
     * Check if a collection exists in the registry.
     */
    public boolean exists(String collectionName) {
        return registry.containsKey(collectionName);
    }

    /**
     * Get all collection names.
     */
    public Set<String> getCollectionNames() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    /**
     * Get all collection definitions.
     */
    public Collection<CollectionDefinition> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * Build a CollectionDefinition from entities.
     */
    private CollectionDefinition buildDefinition(CollectionEntity entity) {
        List<FieldEntity> fieldEntities = fieldRepository.findByCollectionNameOrderBySortOrderAsc(entity.getName());

        CollectionDefinition.Builder builder = CollectionDefinition.builder(entity.getName())
                .title(entity.getTitle())
                .tableName(entity.getEffectiveTableName())
                .schema(entity.getSchema())
                .type(entity.getType())
                .view(entity.getView() != null && entity.getView())
                .sql(entity.getSql())
                .system(entity.getSystem() != null && entity.getSystem())
                .sortable(entity.getSortable() != null && entity.getSortable())
                .logging(entity.getLogging() != null && entity.getLogging())
                .hidden(entity.getHidden() != null && entity.getHidden());

        // Parse options
        Map<String, Object> optionsMap = new HashMap<>();
        if (entity.getOptions() != null) {
            try {
                optionsMap = objectMapper.readValue(entity.getOptions(),
                        new TypeReference<Map<String, Object>>() {});
                builder.options(optionsMap);
            } catch (Exception e) {
                // For sql/view collections, fail-fast on invalid options JSON.
                // The exception enters invalid collection tracking via loadAll/reload catch blocks.
                if ("sql".equals(entity.getType()) || "view".equals(entity.getType())) {
                    throw new IllegalArgumentException(
                            "Collection '" + entity.getName() + "': invalid options JSON - "
                            + sanitizeErrorMessage(e.getMessage()));
                }
                // NOTE: For physical collections, warn-and-ignore is acceptable for now.
                // This is a known boundary — physical collections currently tolerate
                // invalid options JSON to avoid breaking existing data.
                // This may be tightened in a future release.
                log.warn("Failed to parse options for collection '{}': {} [{}]",
                        entity.getName(), sanitizeErrorMessage(e.getMessage()), e.getClass().getSimpleName());
            }
        }

        // Parse primary key from options or field metadata
        String pkField = parsePrimaryKey(entity, optionsMap, fieldEntities);

        // Parse dataSourceKey from options, default to "main"
        String dataSourceKey = parseDataSourceKey(optionsMap, entity.getName());
        builder.dataSourceKey(dataSourceKey);
        if (pkField != null) {
            builder.primaryKey(pkField);
            builder.hasPrimaryKey(true);
        } else if ("view".equals(entity.getType()) || "sql".equals(entity.getType()) || Boolean.TRUE.equals(entity.getView())) {
            builder.hasPrimaryKey(false);
        }

        // Validate relations before building them
        for (FieldEntity fieldEntity : fieldEntities) {
            if (fieldEntity.isRelation()) {
                validateRelation(entity.getName(), fieldEntity, fieldEntities);
            }
        }

        for (FieldEntity fieldEntity : fieldEntities) {
            if (fieldEntity.isRelation()) {
                // Build relation definition
                RelationDefinition relDef = RelationDefinition.builder(
                                fieldEntity.getName(),
                                fieldEntity.getType(),
                                fieldEntity.getTarget())
                        .foreignKey(fieldEntity.getForeignKey())
                        .sourceKey(fieldEntity.getSourceKey())
                        .targetKey(fieldEntity.getTargetKey())
                        .through(fieldEntity.getThrough())
                        .otherKey(fieldEntity.getOtherKey())
                        .build();
                builder.addRelation(relDef);
            }

            // Build field definition
            FieldDefinition fieldDef = FieldDefinition.builder(fieldEntity.getName(), fieldEntity.getType())
                    .interfaceType(fieldEntity.getInterfaceType())
                    .hidden(fieldEntity.getHidden() != null && fieldEntity.getHidden())
                    .system(fieldEntity.getSystem() != null && fieldEntity.getSystem())
                    .physical(fieldEntity.generatesPhysicalColumn())
                    .effectiveColumnName(fieldEntity.getEffectiveColumnName())
                    .build();
            builder.addField(fieldDef);
        }

        CollectionDefinition def = builder.build();

        // Validate SQL collection safety and parameter metadata
        validateSqlCollection(def);

        return def;
    }

    /**
     * Validate SQL collection safety, parameter metadata, and field metadata
     * at definition-build time. Called during reload() and loadAll() to catch
     * configuration errors early.
     *
     * @throws IllegalArgumentException if the SQL collection configuration is invalid
     */
    private void validateSqlCollection(CollectionDefinition def) {
        if (!def.isSql() || def.getSql() == null || def.getSql().isBlank()) {
            return;
        }

        // Validate SQL safety
        SqlValidator.validate(def.getSql());

        // Validate parameter metadata (shape, types, cross-reference with SQL)
        SqlParameterMetadata.from(def);

        // Validate field metadata against the actual SQL result set.
        // This also triggers data-source resolution; if the external data source
        // is unavailable, the exception is caught by loadAll() and the collection
        // is marked as invalid without crashing the application.
        sqlQueryCollectionExecutor.validateFields(def);
    }

    /**
     * P0-D1: Validate a relation field definition during buildDefinition.
     * Performs the following checks:
     * <ol>
     *   <li>Target collection must exist</li>
     *   <li>Through collection must exist for belongsToMany</li>
     *   <li>sourceKey/targetKey/foreignKey/otherKey must exist in the respective collections</li>
     *   <li>Keys must be physical fields or allowed system primary keys</li>
     *   <li>Key type compatibility check (string/number/bigInt/uuid)</li>
     *   <li>belongsTo/hasOne/hasMany/belongsToMany each have required fields</li>
     * </ol>
     * <p>Error messages are crafted to never contain SQL text.
     *
     * @param collectionName the source collection name
     * @param fieldEntity the relation field entity to validate
     * @param sourceFields all fields of the source collection
     * @throws IllegalArgumentException if validation fails
     */
    private void validateRelation(String collectionName, FieldEntity fieldEntity,
                                  List<FieldEntity> sourceFields) {
        String relationType = fieldEntity.getType();
        String fieldName = fieldEntity.getName();
        String targetCollection = fieldEntity.getTarget();

        // 1. belongsTo/hasOne/hasMany/belongsToMany each have required fields
        switch (relationType) {
            case "belongsTo" -> {
                if (fieldEntity.getForeignKey() == null || fieldEntity.getForeignKey().isBlank()) {
                    throw new IllegalArgumentException(
                            "Collection '" + collectionName + "': relation '" + fieldName
                            + "' (belongsTo) requires foreignKey");
                }
            }
            case "hasOne", "hasMany" -> {
                if (fieldEntity.getForeignKey() == null || fieldEntity.getForeignKey().isBlank()) {
                    throw new IllegalArgumentException(
                            "Collection '" + collectionName + "': relation '" + fieldName
                            + "' (" + relationType + ") requires foreignKey");
                }
            }
            case "belongsToMany" -> {
                if (fieldEntity.getThrough() == null || fieldEntity.getThrough().isBlank()) {
                    throw new IllegalArgumentException(
                            "Collection '" + collectionName + "': relation '" + fieldName
                            + "' (belongsToMany) requires through");
                }
                if (fieldEntity.getForeignKey() == null || fieldEntity.getForeignKey().isBlank()) {
                    throw new IllegalArgumentException(
                            "Collection '" + collectionName + "': relation '" + fieldName
                            + "' (belongsToMany) requires foreignKey");
                }
                if (fieldEntity.getOtherKey() == null || fieldEntity.getOtherKey().isBlank()) {
                    throw new IllegalArgumentException(
                            "Collection '" + collectionName + "': relation '" + fieldName
                            + "' (belongsToMany) requires otherKey");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Collection '" + collectionName + "': relation '" + fieldName
                    + "' has unsupported type '" + relationType + "'");
        }

        // 2. Target collection must exist
        if (targetCollection == null || targetCollection.isBlank()) {
            throw new IllegalArgumentException(
                    "Collection '" + collectionName + "': relation '" + fieldName
                    + "' (" + relationType + ") requires a target collection");
        }
        java.util.Optional<CollectionEntity> targetOpt = collectionRepository.findByName(targetCollection);
        if (targetOpt.isEmpty()) {
            throw new IllegalArgumentException(
                    "Collection '" + collectionName + "': relation '" + fieldName
                    + "' (" + relationType + "): target collection '"
                    + targetCollection + "' does not exist");
        }

        // 3. Through collection must exist for belongsToMany
        if ("belongsToMany".equals(relationType)) {
            String through = fieldEntity.getThrough();
            java.util.Optional<CollectionEntity> throughOpt = collectionRepository.findByName(through);
            if (throughOpt.isEmpty()) {
                throw new IllegalArgumentException(
                        "Collection '" + collectionName + "': relation '" + fieldName
                        + "' (belongsToMany): through collection '"
                        + through + "' does not exist");
            }
        }

        // 4. sourceKey/targetKey/foreignKey/otherKey must exist in the respective collections
        //    and must be physical fields or allowed system primary keys

        // sourceKey: must exist in source collection
        String sourceKey = fieldEntity.getSourceKey() != null ? fieldEntity.getSourceKey() : "id";
        validateKeyInCollection(collectionName, fieldName, relationType, "sourceKey", sourceKey,
                collectionName, sourceFields);

        // targetKey: must exist in target collection
        String targetKey = fieldEntity.getTargetKey() != null ? fieldEntity.getTargetKey() : "id";
        List<FieldEntity> targetFields = fieldRepository.findByCollectionNameOrderBySortOrderAsc(targetCollection);
        validateKeyInCollection(collectionName, fieldName, relationType, "targetKey", targetKey,
                targetCollection, targetFields);

        // foreignKey: for belongsTo, it's the FK column in the source table;
        // for hasOne/hasMany, it's a column in the target;
        // for belongsToMany, it's a column in the through collection
        if (fieldEntity.getForeignKey() != null) {
            if ("belongsTo".equals(relationType)) {
                // Verify the foreignKey column exists as a physical field in the source collection,
                // or is a valid effective FK column from a relation field.
                validateBelongsToForeignKey(collectionName, fieldName, relationType,
                        fieldEntity.getForeignKey(), sourceFields);
            } else if ("hasOne".equals(relationType) || "hasMany".equals(relationType)) {
                validateKeyInCollection(collectionName, fieldName, relationType, "foreignKey",
                        fieldEntity.getForeignKey(), targetCollection, targetFields);
            } else if ("belongsToMany".equals(relationType)) {
                List<FieldEntity> throughFields = fieldRepository.findByCollectionNameOrderBySortOrderAsc(
                        fieldEntity.getThrough());
                validateKeyInCollection(collectionName, fieldName, relationType, "foreignKey",
                        fieldEntity.getForeignKey(), fieldEntity.getThrough(), throughFields);
            }
        }

        // otherKey: must exist in the through collection (belongsToMany only)
        if (fieldEntity.getOtherKey() != null && "belongsToMany".equals(relationType)) {
            List<FieldEntity> throughFields = fieldRepository.findByCollectionNameOrderBySortOrderAsc(
                    fieldEntity.getThrough());
            validateKeyInCollection(collectionName, fieldName, relationType, "otherKey",
                    fieldEntity.getOtherKey(), fieldEntity.getThrough(), throughFields);
        }

        // 5. Key type compatibility check (sourceKey vs targetKey)
        validateKeyTypeCompatibility(collectionName, fieldName, relationType,
                sourceKey, collectionName, sourceFields,
                targetKey, targetCollection, targetFields);

        // 6. belongsTo FK type compatibility check (FK type vs targetKey type)
        //    The FK column stores the target key value, so its type must be compatible.
        if ("belongsTo".equals(relationType) && fieldEntity.getForeignKey() != null) {
            validateBelongsToFkTypeCompatibility(collectionName, fieldName, relationType,
                    fieldEntity.getForeignKey(), targetKey, targetCollection,
                    sourceFields, targetFields);
        }

        // 7. belongsToMany through FK/otherKey type compatibility check
        //    through.foreignKey type must be compatible with sourceKey type
        //    through.otherKey type must be compatible with targetKey type
        if ("belongsToMany".equals(relationType)) {
            validateBelongsToManyThroughTypeCompatibility(collectionName, fieldName, relationType,
                    fieldEntity.getThrough(), fieldEntity.getForeignKey(),
                    fieldEntity.getOtherKey(), sourceKey, targetKey,
                    targetCollection, sourceFields, targetFields);
        }
    }

    /**
     * Validate that a key field exists in the specified collection and is a physical field
     * or an allowed system primary key.
     */
    private void validateKeyInCollection(String collectionName, String relationName,
                                         String relationType, String keyType, String keyValue,
                                         String targetCollectionName, List<FieldEntity> targetFields) {
        // Allow system default "id" for physical collections
        if ("id".equals(keyValue)) {
            Optional<CollectionEntity> targetOpt = collectionRepository.findByName(targetCollectionName);
            if (targetOpt.isPresent() && "physical".equals(targetOpt.get().getType())) {
                return; // "id" is always a valid system key for physical collections
            }
        }

        // Find the field in the target collection's fields
        FieldEntity found = null;
        for (FieldEntity fe : targetFields) {
            if (keyValue.equals(fe.getName())) {
                found = fe;
                break;
            }
        }

        if (found == null) {
            throw new IllegalArgumentException(
                    "Collection '" + collectionName + "': relation '" + relationName
                    + "' (" + relationType + "): " + keyType + " '" + keyValue
                    + "' not found in collection '" + targetCollectionName + "'");
        }

        // Must be a physical field (not a relation/virtual field)
        if (!found.generatesPhysicalColumn()) {
            throw new IllegalArgumentException(
                    "Collection '" + collectionName + "': relation '" + relationName
                    + "' (" + relationType + "): " + keyType + " '" + keyValue
                    + "' in collection '" + targetCollectionName
                    + "' must be a physical field, not '" + found.getType() + "'");
        }
    }

    /**
     * Validate the belongsTo foreignKey against the source collection's fields.
     * <p>
     * The foreignKey is valid if:
     * <ul>
     *   <li>It does not match any declared field name in the source collection (it is an
     *       implicit FK column generated by the belongsTo relation field itself), OR</li>
     *   <li>It matches a declared field that is a physical field (not a relation/virtual field)</li>
     * </ul>
     * <p>
     * If the foreignKey matches a declared field that is a relation or virtual field,
     * the validation fails because the FK column must be backed by a real database column.
     */
    private void validateBelongsToForeignKey(String collectionName, String relationName,
                                             String relationType, String foreignKey,
                                             List<FieldEntity> sourceFields) {
        // Find if the foreignKey matches any declared field in the source collection
        FieldEntity found = null;
        for (FieldEntity fe : sourceFields) {
            if (foreignKey.equals(fe.getName())) {
                found = fe;
                break;
            }
        }

        if (found == null) {
            // foreignKey does not match any declared field — this is the common case
            // where the FK column is an implicit column generated by the belongsTo field
            return;
        }

        // If the foreignKey matches a declared field, it must be a physical field
        if (!found.generatesPhysicalColumn()) {
            throw new IllegalArgumentException(
                    "Collection '" + collectionName + "': relation '" + relationName
                    + "' (" + relationType + "): foreignKey '" + foreignKey
                    + "' matches a declared field of type '" + found.getType()
                    + "', but must be a physical field or an implicit FK column");
        }
    }

    /**
     * Type category groups for key type compatibility checks.
     */
    private static final Map<String, String> TYPE_CATEGORY = Map.ofEntries(
            Map.entry("string", "string"),
            Map.entry("text", "string"),
            Map.entry("json", "string"),
            Map.entry("password", "string"),
            Map.entry("uuid", "string"),
            Map.entry("integer", "number"),
            Map.entry("bigint", "number"),
            Map.entry("bigInt", "number"),
            Map.entry("float", "number"),
            Map.entry("double", "number")
    );

    /**
     * Check that sourceKey and targetKey have compatible types.
     * Two keys are compatible if they belong to the same type category.
     * For physical collections, the system "id" field is always bigInt.
     * For view/sql collections, the explicit primaryKey field type is used.
     */
    private void validateKeyTypeCompatibility(String collectionName, String relationName,
                                               String relationType,
                                               String sourceKey, String sourceCollectionName,
                                               List<FieldEntity> sourceFields,
                                               String targetKey, String targetCollectionName,
                                               List<FieldEntity> targetFields) {
        String sourceType = findFieldType(sourceKey, sourceFields);
        String targetType = findFieldType(targetKey, targetFields);

        // For physical collections, "id" is always bigInt
        if (sourceType == null && "id".equals(sourceKey) && isPhysicalCollection(sourceCollectionName)) {
            sourceType = "bigInt";
        }
        if (targetType == null && "id".equals(targetKey) && isPhysicalCollection(targetCollectionName)) {
            targetType = "bigInt";
        }

        if (sourceType == null || targetType == null) {
            return; // cannot determine type, skip check
        }

        String sourceCategory = TYPE_CATEGORY.getOrDefault(sourceType, "unknown");
        String targetCategory = TYPE_CATEGORY.getOrDefault(targetType, "unknown");

        if (!sourceCategory.equals(targetCategory)) {
            throw new IllegalArgumentException(
                    "Collection '" + collectionName + "': relation '" + relationName
                    + "' (" + relationType + "): key type mismatch — sourceKey '"
                    + sourceKey + "' is type '" + sourceType + "' (" + sourceCategory + ")"
                    + " but targetKey '" + targetKey + "' is type '" + targetType
                    + "' (" + targetCategory + "); they must be in the same type category");
        }
    }

    /**
     * Find the type of a field by name in a list of field entities.
     */
    private String findFieldType(String fieldName, List<FieldEntity> fields) {
        for (FieldEntity fe : fields) {
            if (fieldName.equals(fe.getName())) {
                return fe.getType();
            }
        }
        return null;
    }

    /**
     * P1-E: Validate that the belongsTo FK column type is compatible with the targetKey type.
     * <p>
     * The FK column in the source table stores the target key value, so its type
     * must be compatible with the targetKey type. If the foreignKey matches a declared
     * physical field in the source collection, that field's type is checked. If the
     * foreignKey is implicit (not matching any declared field), the FK column type is
     * derived from the targetKey and is always compatible.
     */
    private void validateBelongsToFkTypeCompatibility(String collectionName, String relationName,
                                                       String relationType, String foreignKey,
                                                       String targetKey, String targetCollectionName,
                                                       List<FieldEntity> sourceFields,
                                                       List<FieldEntity> targetFields) {
        // Find the FK column type from the source collection.
        // Only match non-relation physical fields (the FK column is a regular column).
        String fkType = null;
        for (FieldEntity fe : sourceFields) {
            if (foreignKey.equals(fe.getName()) && fe.generatesPhysicalColumn() && !fe.isRelation()) {
                fkType = fe.getType();
                break;
            }
        }

        if (fkType == null) {
            return; // Implicit FK, type is derived from targetKey, always compatible
        }

        // Find targetKey type
        String targetKeyType = findFieldType(targetKey, targetFields);
        if (targetKeyType == null && "id".equals(targetKey) && isPhysicalCollection(targetCollectionName)) {
            targetKeyType = "bigInt";
        }
        if (targetKeyType == null) {
            return; // Cannot determine targetKey type, skip check
        }

        String fkCategory = TYPE_CATEGORY.getOrDefault(fkType, "unknown");
        String targetCategory = TYPE_CATEGORY.getOrDefault(targetKeyType, "unknown");

        if (!fkCategory.equals(targetCategory)) {
            throw new IllegalArgumentException(
                    "Collection '" + collectionName + "': relation '" + relationName
                    + "' (" + relationType + "): FK type mismatch — foreignKey '"
                    + foreignKey + "' is type '" + fkType + "' (" + fkCategory + ")"
                    + " but targetKey '" + targetKey + "' is type '" + targetKeyType
                    + "' (" + targetCategory + "); they must be in the same type category");
        }
    }

    /**
     * P1-E: Validate that the belongsToMany through table FK/otherKey types are compatible
     * with the sourceKey and targetKey types respectively.
     * <p>
     * The through table's foreignKey column stores the source key value, so its type must
     * be compatible with the sourceKey type. The through table's otherKey column stores the
     * target key value, so its type must be compatible with the targetKey type.
     */
    private void validateBelongsToManyThroughTypeCompatibility(String collectionName, String relationName,
                                                                String relationType, String through,
                                                                String foreignKey, String otherKey,
                                                                String sourceKey, String targetKey,
                                                                String targetCollectionName,
                                                                List<FieldEntity> sourceFields,
                                                                List<FieldEntity> targetFields) {
        List<FieldEntity> throughFields = fieldRepository.findByCollectionNameOrderBySortOrderAsc(through);

        // Check through foreignKey type vs sourceKey type
        String throughFkType = findFieldType(foreignKey, throughFields);
        String sourceKeyType = findFieldType(sourceKey, sourceFields);
        if (sourceKeyType == null && "id".equals(sourceKey) && isPhysicalCollection(collectionName)) {
            sourceKeyType = "bigInt";
        }
        if (throughFkType != null && sourceKeyType != null) {
            String throughFkCategory = TYPE_CATEGORY.getOrDefault(throughFkType, "unknown");
            String sourceCategory = TYPE_CATEGORY.getOrDefault(sourceKeyType, "unknown");
            if (!throughFkCategory.equals(sourceCategory)) {
                throw new IllegalArgumentException(
                        "Collection '" + collectionName + "': relation '" + relationName
                        + "' (" + relationType + "): through foreignKey type mismatch — through.foreignKey '"
                        + foreignKey + "' is type '" + throughFkType + "' (" + throughFkCategory + ")"
                        + " but sourceKey '" + sourceKey + "' is type '" + sourceKeyType
                        + "' (" + sourceCategory + "); they must be in the same type category");
            }
        }

        // Check through otherKey type vs targetKey type
        String throughOtherKeyType = findFieldType(otherKey, throughFields);
        String targetKeyType = findFieldType(targetKey, targetFields);
        if (targetKeyType == null && "id".equals(targetKey) && isPhysicalCollection(targetCollectionName)) {
            targetKeyType = "bigInt";
        }
        if (throughOtherKeyType != null && targetKeyType != null) {
            String throughOtherCategory = TYPE_CATEGORY.getOrDefault(throughOtherKeyType, "unknown");
            String targetCategory = TYPE_CATEGORY.getOrDefault(targetKeyType, "unknown");
            if (!throughOtherCategory.equals(targetCategory)) {
                throw new IllegalArgumentException(
                        "Collection '" + collectionName + "': relation '" + relationName
                        + "' (" + relationType + "): through otherKey type mismatch — through.otherKey '"
                        + otherKey + "' is type '" + throughOtherKeyType + "' (" + throughOtherCategory + ")"
                        + " but targetKey '" + targetKey + "' is type '" + targetKeyType
                        + "' (" + targetCategory + "); they must be in the same type category");
            }
        }
    }

    /**
     * Check whether a collection is a physical collection by looking up its type.
     */
    private boolean isPhysicalCollection(String collectionName) {
        Optional<CollectionEntity> opt = collectionRepository.findByName(collectionName);
        return opt.isPresent() && "physical".equals(opt.get().getType());
    }

    /**
     * Get the map of collections that failed to load, with sanitized error messages.
     * Never contains full SQL text or parameter values.
     */
    public Map<String, String> getInvalidCollections() {
        return Collections.unmodifiableMap(invalidCollections);
    }

    /**
     * Clear the invalid collections tracking map.
     */
    public void clearInvalidCollections() {
        invalidCollections.clear();
    }

    /**
     * Sanitize an error message to ensure it does not contain full SQL text
     * or parameter values. Delegates to {@link SqlErrorSanitizer} for unified sanitization.
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) return "Unknown error";
        return SqlErrorSanitizer.sanitize(message);
    }

    /**
     * Sync indexes for a collection entity. Parses index definitions from field and
     * collection options, then delegates to {@link DdlSynchronizer#syncIndexes}.
     * Idempotent: only creates missing indexes. DdlSynchronizer skips view/sql collections.
     * <p>
     * This method throws on failure so that callers can treat index parse/sync
     * failures as fatal — the collection is marked invalid and excluded from the registry.
     */
    private void syncIndexesForEntity(CollectionEntity entity) {
        List<FieldEntity> fields = fieldRepository.findByCollectionNameOrderBySortOrderAsc(entity.getName());
        List<IndexDefinition> indexDefs = IndexDefinition.parse(fields, entity, objectMapper);
        ddlSynchronizer.syncIndexes(entity, indexDefs);
    }

    private String parsePrimaryKey(CollectionEntity entity, Map<String, Object> options,
                                    List<FieldEntity> fieldEntities) {
        // 1. Check options.primaryKey
        Object pk = options.get("primaryKey");
        if (pk instanceof String pkStr && !pkStr.isEmpty()) {
            // Validate the primary key field exists
            validatePrimaryKey(pkStr, entity, fieldEntities);
            return pkStr;
        }

        // 2. Default: "id" for physical collections
        if ("physical".equals(entity.getType())) {
            return "id";
        }

        // 3. View/sql collections: no default primary key
        return null;
    }

    /**
     * Parse dataSourceKey from options map.
     * Validates the key format: {@code [A-Za-z][A-Za-z0-9_-]{0,63}}.
     * Defaults to "main" when not configured.
     * Accepts "default" as a deprecated alias for "main".
     */
    private String parseDataSourceKey(Map<String, Object> options, String collectionName) {
        Object raw = options.get("dataSourceKey");
        if (raw == null) {
            return "main";
        }
        String key = raw.toString().trim();
        if (key.isEmpty()) {
            return "main";
        }
        // Deprecated alias: "default" is normalized to "main"
        if ("default".equals(key)) {
            log.warn("Collection '{}': dataSourceKey 'default' is deprecated, use 'main' instead",
                    collectionName);
            return "main";
        }
        if (!key.matches("^[A-Za-z][A-Za-z0-9_-]{0,63}$")) {
            throw new IllegalArgumentException(
                    "Collection '" + collectionName + "': invalid dataSourceKey '" + key
                    + "'. Must match [A-Za-z][A-Za-z0-9_-]{0,63}");
        }
        return key;
    }

    /**
     * Extract the dataSourceKey from a CollectionEntity's options without building
     * the full CollectionDefinition. Used during reload() to refresh the data source
     * before validating the collection.
     */
    private String extractDataSourceKeyFromEntity(CollectionEntity entity) {
        String optionsJson = entity.getOptions();
        if (optionsJson == null || optionsJson.isBlank()) {
            return "main";
        }
        try {
            Map<String, Object> optionsMap = objectMapper.readValue(optionsJson,
                    new TypeReference<Map<String, Object>>() {});
            Object raw = optionsMap.get("dataSourceKey");
            if (raw == null || raw.toString().trim().isEmpty()) {
                return "main";
            }
            String key = raw.toString().trim();
            if ("default".equals(key)) {
                return "main";
            }
            return key;
        } catch (Exception e) {
            // If options JSON is invalid, return "main" — the buildDefinition will
            // handle the invalid JSON error during validation
            return "main";
        }
    }

    private void validatePrimaryKey(String pkField, CollectionEntity entity, List<FieldEntity> fieldEntities) {
        // Find the field in field entities
        FieldEntity found = null;
        for (FieldEntity fe : fieldEntities) {
            if (pkField.equals(fe.getName())) {
                found = fe;
                break;
            }
        }

        // Allow system default "id"
        if (found == null && "id".equals(pkField) && "physical".equals(entity.getType())) {
            return;
        }

        if (found == null) {
            throw new IllegalArgumentException(
                "Collection '" + entity.getName() + "': primaryKey '" + pkField
                + "' not found in fields metadata");
        }

        // For physical collections, primary key must be a physical field
        if ("physical".equals(entity.getType())) {
            if (!found.generatesPhysicalColumn()) {
                throw new IllegalArgumentException(
                    "Collection '" + entity.getName() + "': primaryKey '" + pkField
                    + "' must be a physical field, not '" + found.getType() + "'");
            }
            if (found.isRelation()) {
                throw new IllegalArgumentException(
                    "Collection '" + entity.getName() + "': primaryKey '" + pkField
                    + "' cannot be a relation field");
            }
        }

        // Validate effective column name is a legal SQL identifier
        String colName = found.getEffectiveColumnName();
        try {
            SqlIdentifier.validate(colName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Collection '" + entity.getName() + "': primaryKey '" + pkField
                + "' has invalid effective column name: " + colName, e);
        }
    }
}