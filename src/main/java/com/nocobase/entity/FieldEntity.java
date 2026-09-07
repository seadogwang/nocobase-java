package com.nocobase.entity;

import com.nocobase.sql.SqlIdentifier;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * NocoBase Field metadata entity.
 * Supports normal fields, system fields, relationship fields, and virtual fields.
 */
@Entity
@Table(name = "fields")
public class FieldEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The collection this field belongs to */
    @Column(name = "collection_name", nullable = false)
    private String collectionName;

    /** Field name (must be a valid identifier) */
    @Column(nullable = false)
    private String name;

    /** Underlying field type or relationship type (string, integer, belongsTo, hasMany, etc.) */
    @Column(nullable = false)
    private String type;

    /** NocoBase field interface (e.g., input, select, richText, etc.) */
    @Column(name = "interface_type")
    private String interfaceType;

    /** UI Schema for this field (JSON) */
    @Column(name = "ui_schema", columnDefinition = "TEXT")
    private String uiSchema;

    /** Additional options (JSON) including default, nullable, unique, index */
    @Column(columnDefinition = "TEXT")
    private String options;

    // --- Relationship fields ---

    /** Target collection for relationship fields */
    private String target;

    /** Foreign key column name (for belongsTo) */
    @Column(name = "foreign_key")
    private String foreignKey;

    /** Source key in the current collection (default: id) */
    @Column(name = "source_key")
    private String sourceKey = "id";

    /** Target key in the target collection (default: id) */
    @Column(name = "target_key")
    private String targetKey = "id";

    /** Through table name (for belongsToMany) */
    @Column(name = "through")
    private String through;

    /** Other key (for belongsToMany through table) */
    @Column(name = "other_key")
    private String otherKey;

    // --- Display & ordering ---

    private String component;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    private Boolean hidden = false;

    /** Whether this is a system field (cannot be deleted) */
    @Column(name = "system")
    private Boolean system = false;

    // --- Timestamps ---

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public FieldEntity() {}

    public FieldEntity(String collectionName, String name, String type) {
        this.collectionName = collectionName;
        this.name = name;
        this.type = type;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getInterfaceType() { return interfaceType; }
    public void setInterfaceType(String interfaceType) { this.interfaceType = interfaceType; }

    public String getUiSchema() { return uiSchema; }
    public void setUiSchema(String uiSchema) { this.uiSchema = uiSchema; }

    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getForeignKey() { return foreignKey; }
    public void setForeignKey(String foreignKey) { this.foreignKey = foreignKey; }

    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }

    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String targetKey) { this.targetKey = targetKey; }

    public String getThrough() { return through; }
    public void setThrough(String through) { this.through = through; }

    public String getOtherKey() { return otherKey; }
    public void setOtherKey(String otherKey) { this.otherKey = otherKey; }

    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Boolean getHidden() { return hidden; }
    public void setHidden(Boolean hidden) { this.hidden = hidden; }

    public Boolean getSystem() { return system; }
    public void setSystem(Boolean system) { this.system = system; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // --- Helper methods ---

    /**
     * Returns true if this is a relationship field.
     */
    public boolean isRelation() {
        return type != null && (
            type.equals("belongsTo") ||
            type.equals("hasOne") ||
            type.equals("hasMany") ||
            type.equals("belongsToMany")
        );
    }

    /**
     * Returns true if this field generates a physical column in the database.
     */
    public boolean generatesPhysicalColumn() {
        if (type == null) return false;
        return switch (type) {
            case "belongsTo" -> foreignKey != null;
            case "hasOne", "hasMany", "belongsToMany" -> false;
            default -> true; // normal fields generate columns
        };
    }

    /**
     * Returns the effective column name for this field.
     * For belongsTo, it's the foreignKey; otherwise it's the field name.
     */
    public String getEffectiveColumnName() {
        if ("belongsTo".equals(type) && foreignKey != null) {
            return foreignKey;
        }
        return name;
    }

    /**
     * Validates that the field name is a legal identifier.
     * Throws IllegalArgumentException if not.
     */
    public void validateFieldName() {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Field name cannot be empty");
        }
        try {
            SqlIdentifier.validate(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid field name: " + name
                + ". Must start with letter or underscore, contain only letters, digits, underscores.", e);
        }
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (hidden == null) {
            hidden = false;
        }
        if (system == null) {
            system = false;
        }
        validateFieldName();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        validateFieldName();
    }
}