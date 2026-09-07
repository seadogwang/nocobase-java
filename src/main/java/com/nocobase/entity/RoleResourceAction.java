package com.nocobase.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Role-Resource-Action permission entity.
 * Controls which actions (list/get/create/update/destroy) a role can perform on a resource.
 */
@Entity
@Table(name = "role_resource_actions")
public class RoleResourceAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_resource_id", nullable = false)
    private Long roleResourceId;

    /** Action name: list, get, create, update, destroy */
    @Column(nullable = false)
    private String action;

    /** Comma-separated list of allowed fields, or null for all */
    @Column(columnDefinition = "TEXT")
    private String fields;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public RoleResourceAction() {}

    public RoleResourceAction(Long roleResourceId, String action, String fields) {
        this.roleResourceId = roleResourceId;
        this.action = action;
        this.fields = fields;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRoleResourceId() { return roleResourceId; }
    public void setRoleResourceId(Long roleResourceId) { this.roleResourceId = roleResourceId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getFields() { return fields; }
    public void setFields(String fields) { this.fields = fields; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}