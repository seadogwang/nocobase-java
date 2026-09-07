package com.nocobase.runtime;

import com.nocobase.sql.SqlIdentifier;

import java.util.Objects;

/**
 * Runtime relation definition, describing a relationship between two collections.
 *
 * <p>All key fields (foreignKey, sourceKey, targetKey, otherKey) are validated
 * as safe SQL identifiers via {@link SqlIdentifier} at build time.
 */
public class RelationDefinition {
    private final String name;
    private final String type;           // belongsTo, hasOne, hasMany, belongsToMany
    private final String targetCollection;
    private final String foreignKey;
    private final String sourceKey;
    private final String targetKey;
    private final String through;         // for belongsToMany
    private final String otherKey;        // for belongsToMany

    private RelationDefinition(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name");
        this.type = Objects.requireNonNull(builder.type, "type");
        this.targetCollection = Objects.requireNonNull(builder.targetCollection, "targetCollection");
        // Validate all key fields as safe SQL identifiers
        this.foreignKey = validateKey(builder.foreignKey, "foreignKey", builder.name);
        this.sourceKey = validateKey(builder.sourceKey != null ? builder.sourceKey : "id", "sourceKey", builder.name);
        this.targetKey = validateKey(builder.targetKey != null ? builder.targetKey : "id", "targetKey", builder.name);
        this.through = builder.through;
        this.otherKey = validateKey(builder.otherKey, "otherKey", builder.name);
    }

    /**
     * Validate a relation key field as a safe SQL identifier.
     * Returns null if the key is null (optional key).
     */
    private static String validateKey(String key, String keyType, String relationName) {
        if (key == null) {
            return null;
        }
        try {
            return SqlIdentifier.validate(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Relation '" + relationName + "': invalid " + keyType + " '" + key + "' - " + e.getMessage(), e);
        }
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getTargetCollection() { return targetCollection; }
    public String getForeignKey() { return foreignKey; }
    public String getSourceKey() { return sourceKey; }
    public String getTargetKey() { return targetKey; }
    public String getThrough() { return through; }
    public String getOtherKey() { return otherKey; }

    public boolean isBelongsTo() { return "belongsTo".equals(type); }
    public boolean isHasOne() { return "hasOne".equals(type); }
    public boolean isHasMany() { return "hasMany".equals(type); }
    public boolean isBelongsToMany() { return "belongsToMany".equals(type); }

    public static Builder builder(String name, String type, String targetCollection) {
        return new Builder(name, type, targetCollection);
    }

    public static class Builder {
        private final String name;
        private final String type;
        private final String targetCollection;
        private String foreignKey;
        private String sourceKey = "id";
        private String targetKey = "id";
        private String through;
        private String otherKey;

        public Builder(String name, String type, String targetCollection) {
            this.name = name;
            this.type = type;
            this.targetCollection = targetCollection;
        }

        public Builder foreignKey(String foreignKey) { this.foreignKey = foreignKey; return this; }
        public Builder sourceKey(String sourceKey) { this.sourceKey = sourceKey; return this; }
        public Builder targetKey(String targetKey) { this.targetKey = targetKey; return this; }
        public Builder through(String through) { this.through = through; return this; }
        public Builder otherKey(String otherKey) { this.otherKey = otherKey; return this; }

        public RelationDefinition build() {
            return new RelationDefinition(this);
        }
    }
}