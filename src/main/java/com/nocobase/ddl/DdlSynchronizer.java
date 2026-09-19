package com.nocobase.ddl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.field.FieldOptions;
import com.nocobase.field.FieldOptionsParser;
import com.nocobase.repository.CollectionRepository;
import com.nocobase.repository.FieldRepository;
import com.nocobase.runtime.IndexDefinition;
import com.nocobase.sql.SqlErrorSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DDL Synchronizer: manages dynamic table creation/alteration based on metadata changes.
 * Ensures metadata and DDL are executed atomically within a transaction.
 */
@Service
public class DdlSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(DdlSynchronizer.class);

    private final JdbcTemplate jdbcTemplate;
    private final CollectionRepository collectionRepository;
    private final FieldRepository fieldRepository;
    private final DialectAdapter dialectAdapter;
    private final ObjectMapper objectMapper;

    private static final String[] SYSTEM_FIELD_NAMES = {"id", "created_at", "updated_at"};

    public DdlSynchronizer(JdbcTemplate jdbcTemplate,
                           CollectionRepository collectionRepository,
                           FieldRepository fieldRepository,
                           DialectAdapterFactory dialectAdapterFactory,
                           ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.collectionRepository = collectionRepository;
        this.fieldRepository = fieldRepository;
        this.dialectAdapter = dialectAdapterFactory.getAdapter();
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new collection with its table.
     */
    @Transactional
    public CollectionEntity createCollection(CollectionEntity collection, java.util.List<FieldEntity> fields) {
        // Save metadata first
        collection.setCreatedAtTime(LocalDateTime.now());
        collection.setUpdatedAtTime(LocalDateTime.now());
        CollectionEntity saved = collectionRepository.save(collection);

        // Skip DDL for view/sql collections -- they don't create physical tables
        if (!isViewOrSql(collection)) {
            DdlPlan plan = buildCreateTablePlan(collection, fields);
            executePlan(plan);
        }

        // Save fields metadata
        int sortOrder = 0;
        for (FieldEntity field : fields) {
            field.setCollectionName(collection.getName());
            field.setSortOrder(sortOrder++);
            field.setCreatedAt(LocalDateTime.now());
            field.setUpdatedAt(LocalDateTime.now());
            fieldRepository.save(field);
        }

        return saved;
    }

    private boolean isViewOrSql(CollectionEntity collection) {
        return "view".equals(collection.getType()) || "sql".equals(collection.getType())
                || Boolean.TRUE.equals(collection.getView());
    }

    /**
     * Add a field to an existing collection.
     */
    @Transactional
    public FieldEntity addField(FieldEntity field) {
        CollectionEntity collection = collectionRepository.findByName(field.getCollectionName())
                .orElseThrow(() -> new RuntimeException("Collection not found: " + field.getCollectionName()));

        boolean isViewOrSql = isViewOrSql(collection);
        field.setSortOrder(fieldRepository.findByCollectionName(field.getCollectionName()).size());
        field.setCreatedAt(LocalDateTime.now());
        field.setUpdatedAt(LocalDateTime.now());
        FieldEntity saved = fieldRepository.save(field);

        // Only add a physical column if the field generates one AND it's a physical collection
        if (field.generatesPhysicalColumn() && !isViewOrSql) {
            FieldOptions opts = parseFieldOptions(field);
            String sqlType = dialectAdapter.mapFieldType(
                    field.getType(), opts.getLength(), opts.getPrecision(), opts.getScale());
            boolean isNullable = opts.getNullable() != null ? opts.getNullable() : true;
            DdlPlan.ColumnDef column = new DdlPlan.ColumnDef(
                    field.getEffectiveColumnName(), sqlType, isNullable,
                    opts.getDefaultValue(), false, false);
            DdlPlan plan = DdlPlan.addColumn(collection.getName(),
                    collection.getEffectiveTableName(), column, dialectAdapter);
            executePlan(plan);
        }

        return saved;
    }

    /**
     * Remove a field from a collection.
     */
    @Transactional
    public void dropField(String collectionName, String fieldName) {
        if (isSystemField(fieldName)) {
            throw new IllegalArgumentException("Cannot drop system field: " + fieldName);
        }

        FieldEntity field = fieldRepository.findByCollectionNameAndName(collectionName, fieldName)
                .orElseThrow(() -> new RuntimeException("Field not found: " + collectionName + "." + fieldName));

        fieldRepository.delete(field);

        if (field.generatesPhysicalColumn()) {
            CollectionEntity collection = collectionRepository.findByName(collectionName)
                    .orElseThrow(() -> new RuntimeException("Collection not found: " + collectionName));
            if (!isViewOrSql(collection)) {
                DdlPlan plan = DdlPlan.dropColumn(collectionName,
                        collection.getEffectiveTableName(), field.getEffectiveColumnName(), dialectAdapter);
                executePlan(plan);
            }
        }
    }

    /**
     * Drop a collection and its table.
     */
    @Transactional
    public void dropCollection(String collectionName) {
        CollectionEntity collection = collectionRepository.findByName(collectionName)
                .orElseThrow(() -> new RuntimeException("Collection not found: " + collectionName));

        if (Boolean.TRUE.equals(collection.getSystem())) {
            throw new IllegalArgumentException("Cannot drop system collection: " + collectionName);
        }

        // Delete fields first (FK constraint)
        fieldRepository.findByCollectionName(collectionName).forEach(fieldRepository::delete);

        // Delete collection metadata
        collectionRepository.delete(collection);

        // Drop the table (only for physical collections)
        if (!isViewOrSql(collection)) {
            DdlPlan plan = DdlPlan.dropTable(collectionName, collection.getEffectiveTableName(), dialectAdapter);
            executePlan(plan);
        }
    }

    /**
     * Build a CREATE TABLE DDL plan for a new collection.
     */
    private DdlPlan buildCreateTablePlan(CollectionEntity collection, java.util.List<FieldEntity> fields) {
        java.util.List<DdlPlan.ColumnDef> columns = new java.util.ArrayList<>();

        // ID column
        columns.add(new DdlPlan.ColumnDef("id", "BIGINT", false, null, true, true));

        // User-defined fields
        for (FieldEntity field : fields) {
            if (field.generatesPhysicalColumn()) {
                FieldOptions opts = parseFieldOptions(field);
                String sqlType = dialectAdapter.mapFieldType(
                        field.getType(), opts.getLength(), opts.getPrecision(), opts.getScale());
                boolean isNullable = opts.getNullable() != null ? opts.getNullable() : true;
                columns.add(new DdlPlan.ColumnDef(
                        field.getEffectiveColumnName(), sqlType, isNullable,
                        opts.getDefaultValue(), false, false));
            }
        }

        // Timestamps
        columns.add(new DdlPlan.ColumnDef("created_at", "TIMESTAMP", true,
                FieldOptions.DefaultValue.expression("CURRENT_TIMESTAMP"), false, false));
        columns.add(new DdlPlan.ColumnDef("updated_at", "TIMESTAMP", true,
                FieldOptions.DefaultValue.expression("CURRENT_TIMESTAMP"), false, false));

        return DdlPlan.createTable(collection.getName(), collection.getEffectiveTableName(), columns, dialectAdapter);
    }

    /**
     * Execute all statements in a DDL plan.
     */
    private void executePlan(DdlPlan plan) {
        for (String sql : plan.getStatements()) {
            try {
                log.debug("Executing DDL statement for collection '{}'", plan.getCollectionName());
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.error("DDL execution failed for collection '{}'", plan.getCollectionName());
                throw new RuntimeException("DDL execution failed for collection '" + plan.getCollectionName() + "'", e);
            }
        }
    }

    /**
     * Parse field options from the field's JSON options string.
     * Fails fast on invalid options -- never produces half-baked DDL.
     *
     * @param field the field entity
     * @return parsed FieldOptions (never null)
     * @throws IllegalArgumentException if options JSON is invalid
     */
    private FieldOptions parseFieldOptions(FieldEntity field) {
        String optionsJson = field.getOptions();
        if (optionsJson == null || optionsJson.isBlank()) {
            return FieldOptions.builder().build();
        }
        try {
            Map<String, Object> optionsMap = objectMapper.readValue(optionsJson,
                    new TypeReference<Map<String, Object>>() {});
            return FieldOptionsParser.parse(optionsMap);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Field '" + field.getName() + "' in collection '" + field.getCollectionName()
                    + "': invalid options JSON - " + e.getMessage(), e);
        }
    }

    /**
     * Dry-run: build a DDL plan without executing it.
     * Returns the plan that would be executed for manual inspection.
     *
     * @param collection the collection entity
     * @param fields     the field entities
     * @return the DdlPlan that would be executed
     */
    public DdlPlan dryRunCreateCollection(CollectionEntity collection, List<FieldEntity> fields) {
        if (isViewOrSql(collection)) {
            return new DdlPlan(collection.getName());
        }
        return buildCreateTablePlan(collection, fields);
    }

    /**
     * Diff the current database state against target metadata, including indexes.
     * Returns a list of SchemaPlan entries describing what changes would be made.
     * Does NOT execute any DDL.
     *
     * @param collection the collection entity
     * @param fields     the target field entities
     * @param indexDefs  the target index definitions (may be null)
     * @return list of SchemaPlan entries describing the diff
     */
    public List<SchemaPlan> diff(CollectionEntity collection, List<FieldEntity> fields,
                                  List<IndexDefinition> indexDefs) {
        List<SchemaPlan> plans = new ArrayList<>();
        String tableName = collection.getEffectiveTableName();

        if (isViewOrSql(collection)) {
            plans.add(new SchemaPlan(SchemaPlan.Action.NO_OP, collection.getName(),
                    "View/sql collection -- no physical DDL", false));
            return plans;
        }

        // Check table existence
        if (!dialectAdapter.tableExists(jdbcTemplate, tableName)) {
            plans.add(new SchemaPlan(SchemaPlan.Action.MISSING_TABLE, collection.getName(),
                    "Table '" + tableName + "' does not exist", false));
            // Can't check columns or indexes if table doesn't exist
            return plans;
        }

        // Check column existence
        for (FieldEntity field : fields) {
            if (field.generatesPhysicalColumn()) {
                String colName = field.getEffectiveColumnName();
                if (!dialectAdapter.columnExists(jdbcTemplate, tableName, colName)) {
                    FieldOptions opts = parseFieldOptions(field);
                    String sqlType = dialectAdapter.mapFieldType(
                            field.getType(), opts.getLength(), opts.getPrecision(), opts.getScale());
                    plans.add(new SchemaPlan(SchemaPlan.Action.MISSING_COLUMN, collection.getName(),
                            "Column '" + colName + "' (type: " + sqlType + ") does not exist in table '"
                            + tableName + "'", false));
                }
            }
        }

        // Check index existence
        if (indexDefs != null && !indexDefs.isEmpty()) {
            for (IndexDefinition idxDef : indexDefs) {
                boolean exists = dialectAdapter.indexExists(jdbcTemplate, tableName, idxDef.getName());
                if (!exists) {
                    plans.add(new SchemaPlan(SchemaPlan.Action.MISSING_INDEX, collection.getName(),
                            "Index '" + idxDef.getName() + "' does not exist on table '" + tableName
                            + "' (columns: " + idxDef.getColumnNames() + ", unique: " + idxDef.isUnique() + ")",
                            false));
                }
            }
        }

        if (plans.isEmpty()) {
            plans.add(new SchemaPlan(SchemaPlan.Action.NO_OP, collection.getName(),
                    "No schema changes required", false));
        }

        return plans;
    }

    /**
     * Sync indexes for a collection. Idempotent: only creates missing indexes.
     * Does NOT auto-drop extra indexes (conservative strategy).
     * Skips view/sql collections (no physical index DDL).
     * Skips external SQL datasource collections (no index DDL).
     *
     * @param collection the collection entity
     * @param indexDefs  the list of desired index definitions
     */
    public void syncIndexes(CollectionEntity collection, List<IndexDefinition> indexDefs) {
        if (isViewOrSql(collection)) {
            log.info("Skipping index sync for view/sql collection: {}", collection.getName());
            return;
        }

        if (indexDefs == null || indexDefs.isEmpty()) {
            return;
        }

        String tableName = collection.getEffectiveTableName();
        for (IndexDefinition indexDef : indexDefs) {
            try {
                boolean exists = dialectAdapter.indexExists(jdbcTemplate, tableName, indexDef.getName());
                if (exists) {
                    log.debug("Index already exists, skipping: {} on {}", indexDef.getName(), tableName);
                    continue;
                }

                String sql = dialectAdapter.buildCreateIndex(
                        tableName, indexDef.getName(), indexDef.getColumnNames(), indexDef.isUnique());
                log.debug("Executing DDL statement for collection '{}'", collection.getName());
                jdbcTemplate.execute(sql);
                log.info("Created index: {} on {} (columns: {}, unique: {})",
                        indexDef.getName(), tableName, indexDef.getColumnNames(), indexDef.isUnique());
            } catch (Exception e) {
                log.error("Failed to sync index {} on collection {}: {}",
                        indexDef.getName(), collection.getName(), SqlErrorSanitizer.sanitizeForLog(e.getMessage()));
                throw new RuntimeException("Failed to sync index: " + SqlErrorSanitizer.sanitizeForClient(e.getMessage()), e);
            }
        }
    }

    private boolean isSystemField(String name) {
        for (String sys : SYSTEM_FIELD_NAMES) {
            if (sys.equals(name)) return true;
        }
        return false;
    }
}