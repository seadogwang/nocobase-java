package com.nocobase.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * NocoBase Collection metadata entity.
 * Represents a data collection that can be a physical table, view, SQL query, or external source.
 */
@Entity
@Table(name = "collections")
public class CollectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    private String title;

    private String namespace;

    /** Physical table name, defaults to name if not set */
    @Column(name = "table_name")
    private String tableName;

    /** Database schema (PostgreSQL: public, etc.) */
    @Column(name = "schema")
    private String schema = "public";

    /** Collection template type */
    private String template;

    /** Collection type: physical, view, sql, external */
    @Column(name = "type")
    private String type = "physical";

    /** Whether this collection is a database view */
    private Boolean view = false;

    /** SQL query for sql-type collections */
    @Column(columnDefinition = "TEXT")
    private String sql;

    /** Inherited collection names (JSON array) */
    @Column(columnDefinition = "TEXT")
    private String inherits;

    /** Category for grouping collections */
    private String category;

    private Boolean sortable = false;

    private Boolean logging = false;

    private Boolean hidden = false;

    /** Whether this is a system collection (cannot be deleted) */
    @Column(name = "system")
    private Boolean system = false;

    /** Legacy: JSON array of field definitions (deprecated, use fields table) */
    @Column(columnDefinition = "TEXT")
    private String fields;

    /** Additional options (JSON) */
    @Column(columnDefinition = "TEXT")
    private String options;

    @Column(name = "auto_gen_id")
    private Boolean autoGenId = true;

    /** Whether created_at timestamp auto-management is enabled */
    @Column(name = "created_at")
    private Boolean createdAt = true;

    /** Whether updated_at timestamp auto-management is enabled */
    @Column(name = "updated_at")
    private Boolean updatedAt = true;

    @Column(name = "created_at_time")
    private LocalDateTime createdAtTime;

    @Column(name = "updated_at_time")
    private LocalDateTime updatedAtTime;

    // Constructors
    public CollectionEntity() {}

    public CollectionEntity(String name, String title, String type) {
        this.name = name;
        this.title = title;
        this.type = type;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Boolean getView() { return view; }
    public void setView(Boolean view) { this.view = view; }

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }

    public String getInherits() { return inherits; }
    public void setInherits(String inherits) { this.inherits = inherits; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Boolean getSortable() { return sortable; }
    public void setSortable(Boolean sortable) { this.sortable = sortable; }

    public Boolean getLogging() { return logging; }
    public void setLogging(Boolean logging) { this.logging = logging; }

    public Boolean getHidden() { return hidden; }
    public void setHidden(Boolean hidden) { this.hidden = hidden; }

    public Boolean getSystem() { return system; }
    public void setSystem(Boolean system) { this.system = system; }

    public String getFields() { return fields; }
    public void setFields(String fields) { this.fields = fields; }

    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }

    public Boolean getAutoGenId() { return autoGenId; }
    public void setAutoGenId(Boolean autoGenId) { this.autoGenId = autoGenId; }

    public Boolean getCreatedAt() { return createdAt; }
    public void setCreatedAt(Boolean createdAt) { this.createdAt = createdAt; }

    public Boolean getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Boolean updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCreatedAtTime() { return createdAtTime; }
    public void setCreatedAtTime(LocalDateTime createdAtTime) { this.createdAtTime = createdAtTime; }

    public LocalDateTime getUpdatedAtTime() { return updatedAtTime; }
    public void setUpdatedAtTime(LocalDateTime updatedAtTime) { this.updatedAtTime = updatedAtTime; }

    /**
     * Returns the effective table name (tableName if set, otherwise name).
     */
    public String getEffectiveTableName() {
        return (tableName != null && !tableName.isEmpty()) ? tableName : name;
    }

    /**
     * Returns true if this is a physical table collection.
     */
    public boolean isPhysical() {
        return "physical".equals(type);
    }

    /**
     * Returns true if this is a view collection.
     */
    public boolean isView() {
        return Boolean.TRUE.equals(view) || "view".equals(type);
    }

    /**
     * Returns true if this is a SQL collection.
     */
    public boolean isSql() {
        return "sql".equals(type);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAtTime == null) {
            createdAtTime = LocalDateTime.now();
        }
        if (updatedAtTime == null) {
            updatedAtTime = LocalDateTime.now();
        }
        if (type == null) {
            type = "physical";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAtTime = LocalDateTime.now();
    }
}