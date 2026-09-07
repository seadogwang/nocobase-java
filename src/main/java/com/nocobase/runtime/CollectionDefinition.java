package com.nocobase.runtime;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable definition of a collection, loaded from metadata at startup.
 * Used by all runtime components to get collection information.
 */
public class CollectionDefinition {
    private final String name;
    private final String title;
    private final String tableName;
    private final String schema;
    private final String type;
    private final boolean view;
    private final String sql;
    private final boolean system;
    private final boolean sortable;
    private final boolean logging;
    private final boolean hidden;
    private final String primaryKeyFieldName;
    private final boolean hasPrimaryKey;
    private final String dataSourceKey;
    private final Map<String, Object> options;
    private final Map<String, FieldDefinition> fields;
    private final List<RelationDefinition> relations;

    private CollectionDefinition(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name");
        this.title = builder.title;
        this.tableName = builder.tableName != null ? builder.tableName : builder.name;
        this.schema = builder.schema != null ? builder.schema : "public";
        this.type = builder.type != null ? builder.type : "physical";
        this.view = builder.view;
        this.sql = builder.sql;
        this.system = builder.system;
        this.sortable = builder.sortable;
        this.logging = builder.logging;
        this.hidden = builder.hidden;
        this.primaryKeyFieldName = builder.primaryKeyFieldName != null ? builder.primaryKeyFieldName : "id";
        this.hasPrimaryKey = builder.hasPrimaryKey;
        this.dataSourceKey = builder.dataSourceKey != null ? builder.dataSourceKey : "main";
        this.options = Collections.unmodifiableMap(new HashMap<>(builder.options));
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(builder.fields));
        this.relations = Collections.unmodifiableList(new ArrayList<>(builder.relations));
    }

    // Getters
    public String getName() { return name; }
    public String getTitle() { return title; }
    public String getTableName() { return tableName; }
    public String getSchema() { return schema; }
    public String getType() { return type; }
    public boolean isView() { return view; }
    public String getSql() { return sql; }
    public boolean isSystem() { return system; }
    public boolean isSortable() { return sortable; }
    public boolean isLogging() { return logging; }
    public boolean isHidden() { return hidden; }
    public Map<String, Object> getOptions() { return options; }

    public boolean isPhysical() { return "physical".equals(type); }
    public boolean isSql() { return "sql".equals(type); }

    /**
     * Returns the data source key for this collection.
     * Defaults to "main" when not configured in options.
     */
    public String getDataSourceKey() { return dataSourceKey; }

    /**
     * Returns the primary key field name. Configurable via options.primaryKey or field options.
     * Defaults to "id" for physical collections.
     */
    public String getPrimaryKeyFieldName() {
        return primaryKeyFieldName;
    }

    /**
     * Returns the primary key column name (effective column name in DB).
     */
    public String getPrimaryKeyColumnName() {
        FieldDefinition pkField = getField(primaryKeyFieldName);
        return pkField != null ? pkField.getEffectiveColumnName() : primaryKeyFieldName;
    }

    /**
     * Returns true if this collection has a primary key.
     */
    public boolean hasPrimaryKey() {
        return hasPrimaryKey && primaryKeyFieldName != null;
    }

    public FieldDefinition getField(String fieldName) {
        return fields.get(fieldName);
    }

    public Map<String, FieldDefinition> getFields() { return fields; }

    public List<RelationDefinition> getRelations() { return relations; }

    public RelationDefinition getRelation(String name) {
        return relations.stream()
                .filter(r -> r.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public boolean hasField(String fieldName) {
        return fields.containsKey(fieldName);
    }

    public List<String> getFieldNames() {
        return new ArrayList<>(fields.keySet());
    }

    public List<String> getPhysicalColumnNames() {
        return fields.values().stream()
                .filter(FieldDefinition::isPhysical)
                .map(FieldDefinition::getEffectiveColumnName)
                .toList();
    }

    @Override
    public String toString() {
        return "CollectionDefinition{name='" + name + "', type='" + type + "', fields=" + fields.size() + "}";
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String title;
        private String tableName;
        private String schema = "public";
        private String type = "physical";
        private boolean view;
        private String sql;
        private boolean system;
        private boolean sortable;
        private boolean logging;
        private boolean hidden;
        private String primaryKeyFieldName = "id";
        private boolean hasPrimaryKey = true;
        private String dataSourceKey;
        private Map<String, Object> options = new HashMap<>();
        private Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        private List<RelationDefinition> relations = new ArrayList<>();

        public Builder(String name) {
            this.name = name;
        }

        public Builder title(String title) { this.title = title; return this; }
        public Builder tableName(String tableName) { this.tableName = tableName; return this; }
        public Builder schema(String schema) { this.schema = schema; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder view(boolean view) { this.view = view; return this; }
        public Builder sql(String sql) { this.sql = sql; return this; }
        public Builder system(boolean system) { this.system = system; return this; }
        public Builder sortable(boolean sortable) { this.sortable = sortable; return this; }
        public Builder logging(boolean logging) { this.logging = logging; return this; }
        public Builder hidden(boolean hidden) { this.hidden = hidden; return this; }
        public Builder primaryKey(String fieldName) { this.primaryKeyFieldName = fieldName; return this; }
        public Builder hasPrimaryKey(boolean has) { this.hasPrimaryKey = has; return this; }
        public Builder dataSourceKey(String dataSourceKey) { this.dataSourceKey = dataSourceKey; return this; }
        public Builder options(Map<String, Object> options) { this.options.putAll(options); return this; }
        public Builder addField(FieldDefinition field) { this.fields.put(field.getName(), field); return this; }
        public Builder addRelation(RelationDefinition relation) { this.relations.add(relation); return this; }

        public CollectionDefinition build() {
            return new CollectionDefinition(this);
        }
    }
}