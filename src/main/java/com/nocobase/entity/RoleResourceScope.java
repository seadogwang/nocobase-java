package com.nocobase.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Role-Resource-Scope entity.
 * Stores filter JSON for data-range permissions (e.g., user can only see their own records).
 */
@Entity
@Table(name = "role_resource_scopes")
public class RoleResourceScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_resource_id", nullable = false)
    private Long roleResourceId;

    /** Filter JSON for data range (e.g., {"createdById": "$currentUser.id"}) */
    @Column(columnDefinition = "TEXT")
    private String scope;

    /** The action this scope applies to (list, get, create, update, destroy) */
    @Column(name = "action")
    private String action;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public RoleResourceScope() {}

    public RoleResourceScope(Long roleResourceId, String scope, String action) {
        this.roleResourceId = roleResourceId;
        this.scope = scope;
        this.action = action;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRoleResourceId() { return roleResourceId; }
    public void setRoleResourceId(Long roleResourceId) { this.roleResourceId = roleResourceId; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}