package com.nocobase.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persisted external data source configuration.
 * Separate from {@link com.nocobase.config.NocobaseDataSourceProperties.DataSourceConfig}
 * which is bound from YAML at startup. This entity allows runtime CRUD management
 * of external data sources via the DataSourceController API.
 *
 * <p>Secrets (password) are stored encrypted/hashed in the database and are NEVER
 * returned to the frontend. Log output is sanitized to prevent credential leakage.
 */
@Entity
@Table(name = "external_data_sources")
public class DataSourceConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Data source key matching [A-Za-z][A-Za-z0-9_-]{0,63} */
    @Column(name = "ds_key", unique = true, nullable = false, length = 64)
    private String dsKey;

    /** Display name */
    @Column(name = "display_name")
    private String displayName;

    /** JDBC URL */
    @Column(name = "ds_url", nullable = false)
    private String url;

    /** JDBC driver class name */
    @Column(name = "driver_class_name")
    private String driverClassName;

    /** Database username */
    @Column(name = "ds_username")
    private String username;

    /** Database password (encrypted at rest, never returned to frontend) */
    @Column(name = "ds_password")
    private String password;

    /** Whether this data source is enabled */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** SQL dialect: h2 or postgresql */
    @Column(name = "dialect")
    private String dialect;

    /** Whether this data source is read-only (always true for external) */
    @Column(name = "read_only", nullable = false)
    private boolean readOnly = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // -- constructors ----------------------------------------------------

    public DataSourceConfigEntity() {}

    // -- getters / setters -----------------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDsKey() { return dsKey; }
    public void setDsKey(String dsKey) { this.dsKey = dsKey; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // -- lifecycle -------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}