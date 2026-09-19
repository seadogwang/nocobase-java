package com.nocobase.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.sql.SqlIdentifier;

import java.util.*;

/**
 * Immutable definition of a database index, parsed from field and collection metadata.
 *
 * <p>Index names, table names, and column names are all validated as safe SQL identifiers
 * via {@link SqlIdentifier} at build time.
 *
 * <p>For SQL/view collections, indexes are metadata-only -- no physical index DDL is generated.
 */
public class IndexDefinition {

    private final String name;
    private final String tableName;
    private final List<String> columnNames;
    private final boolean unique;
    private final String collectionName;

    private IndexDefinition(Builder builder) {
        this.name = SqlIdentifier.validate(Objects.requireNonNull(builder.name, "name"));
        this.tableName = builder.tableName != null ? SqlIdentifier.validate(builder.tableName) : null;
        // Validate column names using traditional loop to avoid synthetic class issues
        List<String> validated = new ArrayList<>(builder.columnNames.size());
        for (String cn : builder.columnNames) {
            try {
                validated.add(SqlIdentifier.validate(cn));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Index '" + builder.name + "': invalid column name '" + cn + "' - " + e.getMessage(), e);
            }
        }
        this.columnNames = Collections.unmodifiableList(validated);
        this.unique = builder.unique;
        this.collectionName = Objects.requireNonNull(builder.collectionName, "collectionName");
    }

    public String getName() { return name; }
    public String getTableName() { return tableName; }
    public List<String> getColumnNames() { return columnNames; }
    public boolean isUnique() { return unique; }
    public String getCollectionName() { return collectionName; }

    /**
     * Parse index definitions from field options and collection options.
     *
     * <p>Two sources of index definitions:
     * <ol>
     *   <li><b>Field-level options:</b> {@code {"index": true}} or {@code {"unique": true}}
     *       in the field's options JSON. Auto-generates index names using the convention
     *       {@code idx_{tableName}_{columnName}} (non-unique) or
     *       {@code udx_{tableName}_{columnName}} (unique).</li>
     *   <li><b>Collection-level options:</b> {@code {"indexes": [{"name": "...", "fields": ["..."], "unique": false}]}}
     *       in the collection's options JSON. Supports multi-column indexes with explicit names.</li>
     * </ol>
     *
     * @param fields       the fields of the collection
     * @param collection   the collection entity
     * @param objectMapper Jackson ObjectMapper for parsing JSON options
     * @return list of index definitions, never null
     */
    public static List<IndexDefinition> parse(List<FieldEntity> fields, CollectionEntity collection,
                                               ObjectMapper objectMapper) {
        List<IndexDefinition> indexes = new ArrayList<>();
        String tableName = collection.getEffectiveTableName();

        // 1. Parse field-level index/unique options
        for (FieldEntity field : fields) {
            String optionsJson = field.getOptions();
            if (optionsJson == null || optionsJson.isBlank()) continue;

            Map<String, Object> options;
            try {
                options = objectMapper.readValue(optionsJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Field '" + field.getName() + "' in collection '" + collection.getName()
                        + "': invalid options JSON - " + e.getMessage(), e);
            }

            String colName = field.getEffectiveColumnName();
            try {
                SqlIdentifier.validate(colName);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Field '" + field.getName() + "' in collection '" + collection.getName()
                        + "': invalid column name '" + colName + "' - " + e.getMessage(), e);
            }

            // Unique index
            if (Boolean.TRUE.equals(options.get("unique"))) {
                String indexName = "udx_" + tableName + "_" + colName;
                indexes.add(new Builder()
                        .name(indexName)
                        .tableName(tableName)
                        .addColumnName(colName)
                        .unique(true)
                        .collectionName(collection.getName())
                        .build());
            }

            // Non-unique index
            if (Boolean.TRUE.equals(options.get("index"))) {
                String indexName = "idx_" + tableName + "_" + colName;
                indexes.add(new Builder()
                        .name(indexName)
                        .tableName(tableName)
                        .addColumnName(colName)
                        .unique(false)
                        .collectionName(collection.getName())
                        .build());
            }
        }

        // 2. Parse collection-level indexes
        String collectionOptionsJson = collection.getOptions();
        if (collectionOptionsJson != null && !collectionOptionsJson.isBlank()) {
            try {
                Map<String, Object> collectionOptions = objectMapper.readValue(
                        collectionOptionsJson, new TypeReference<Map<String, Object>>() {});
                Object indexesRaw = collectionOptions.get("indexes");
                if (indexesRaw != null) {
                    if (!(indexesRaw instanceof List<?>)) {
                        throw new IllegalArgumentException(
                                "Collection '" + collection.getName() + "': 'indexes' must be an array");
                    }
                    List<?> indexesList = (List<?>) indexesRaw;
                    for (int i = 0; i < indexesList.size(); i++) {
                        Object item = indexesList.get(i);
                        if (!(item instanceof Map<?, ?>)) {
                            throw new IllegalArgumentException(
                                    "Collection '" + collection.getName() + "': indexes[" + i + "] must be an object");
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> idx = (Map<String, Object>) item;

                        String idxName = (String) idx.get("name");
                        if (idxName == null || idxName.isBlank()) {
                            throw new IllegalArgumentException(
                                    "Collection '" + collection.getName() + "': indexes[" + i + "] missing 'name'");
                        }

                        Object fieldsRaw = idx.get("fields");
                        if (fieldsRaw == null) {
                            throw new IllegalArgumentException(
                                    "Collection '" + collection.getName() + "': index '" + idxName + "' missing 'fields'");
                        }
                        if (!(fieldsRaw instanceof List<?>)) {
                            throw new IllegalArgumentException(
                                    "Collection '" + collection.getName() + "': index '"
                                    + idxName + "' 'fields' must be an array");
                        }
                        List<?> fieldsList = (List<?>) fieldsRaw;
                        if (fieldsList.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "Collection '" + collection.getName() + "': index '"
                                    + idxName + "' 'fields' must not be empty");
                        }

                        // Validate each field entry is a string
                        for (int j = 0; j < fieldsList.size(); j++) {
                            if (!(fieldsList.get(j) instanceof String)) {
                                throw new IllegalArgumentException(
                                        "Collection '" + collection.getName() + "': index '"
                                        + idxName + "' fields[" + j + "] must be a string");
                            }
                        }

                        // Resolve field names to effective column names
                        List<String> columnNames = new ArrayList<>();
                        for (Object f : fieldsList) {
                            String fieldName = (String) f;
                            if (fieldName.isBlank()) {
                                throw new IllegalArgumentException(
                                        "Collection '" + collection.getName() + "': index '"
                                        + idxName + "' fields must not contain blank strings");
                            }
                            // Look up the field in the collection's fields
                            FieldEntity found = null;
                            for (FieldEntity fe : fields) {
                                if (fieldName.equals(fe.getName())) {
                                    found = fe;
                                    break;
                                }
                            }
                            if (found == null) {
                                throw new IllegalArgumentException(
                                        "Collection '" + collection.getName() + "': index '" + idxName
                                        + "' references field '" + fieldName + "' which does not exist");
                            }
                            if (!found.generatesPhysicalColumn()) {
                                throw new IllegalArgumentException(
                                        "Collection '" + collection.getName() + "': index '" + idxName
                                        + "' references field '" + fieldName + "' which is a relation or virtual field");
                            }
                            // Use the effective column name (handles belongsTo FK columns, custom columnName)
                            String colName = found.getEffectiveColumnName();
                            columnNames.add(colName);
                        }

                        boolean isUnique = Boolean.TRUE.equals(idx.get("unique"));

                        indexes.add(new Builder()
                                .name(idxName)
                                .tableName(tableName)
                                .columnNames(columnNames)
                                .unique(isUnique)
                                .collectionName(collection.getName())
                                .build());
                    }
                }
            } catch (IllegalArgumentException e) {
                throw e; // Re-throw validation errors as-is
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Collection '" + collection.getName() + "': invalid options JSON for index parsing - "
                        + e.getMessage(), e);
            }
        }

        return Collections.unmodifiableList(indexes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexDefinition that)) return false;
        return unique == that.unique
                && Objects.equals(name, that.name)
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(columnNames, that.columnNames)
                && Objects.equals(collectionName, that.collectionName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, tableName, columnNames, unique, collectionName);
    }

    @Override
    public String toString() {
        return "IndexDefinition{name='" + name + "', tableName='" + tableName
                + "', columns=" + columnNames + ", unique=" + unique + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String tableName;
        private List<String> columnNames = new ArrayList<>();
        private boolean unique;
        private String collectionName;

        public Builder name(String name) { this.name = name; return this; }
        public Builder tableName(String tableName) { this.tableName = tableName; return this; }
        public Builder columnNames(List<String> columnNames) { this.columnNames = new ArrayList<>(columnNames); return this; }
        public Builder addColumnName(String columnName) { this.columnNames.add(columnName); return this; }
        public Builder unique(boolean unique) { this.unique = unique; return this; }
        public Builder collectionName(String collectionName) { this.collectionName = collectionName; return this; }

        public IndexDefinition build() {
            return new IndexDefinition(this);
        }
    }
}