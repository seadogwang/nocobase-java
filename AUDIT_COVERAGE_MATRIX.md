# Audit Coverage Matrix

## Overview

This matrix tracks audit logging coverage for all write entry points in the NocoBase Java backend.

**Legend:**
- CHECK = Covered
- X = Not covered
- N/A = Not applicable
- REQUIRED = Propagation.REQUIRED (fail-fast, audit write is part of caller's transaction)
- REQUIRES_NEW = Propagation.REQUIRES_NEW (audit write in its own transaction, won't compound errors)

---

## User Management

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| user | create | UserManagementService.createUser | CHECK | CHECK | CHECK | CHECK | CHECK (no password) | REQUIRED / REQUIRES_NEW |
| user | update | UserManagementService.updateUser | CHECK | CHECK | CHECK | CHECK | CHECK (no password) | REQUIRED / REQUIRES_NEW |
| user | destroy | UserManagementService.destroyUser | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| user | updateRoles | UserManagementService.updateUserRoles | CHECK | CHECK | CHECK | CHECK | CHECK (no password) | REQUIRED / REQUIRES_NEW |

---

## Role Management

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| role | create | RoleManagementService.createRole | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| role | update | RoleManagementService.updateRole | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| role | destroy | RoleManagementService.destroyRole | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |

---

## ACL Management

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| acl | create | AclManagementService.createRoleResource | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| acl | update | AclManagementService.updateRoleResource | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| acl | destroy | AclManagementService.destroyRoleResource | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| aclAction | create | AclManagementService.createAction | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| aclAction | update | AclManagementService.updateAction | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| aclAction | destroy | AclManagementService.destroyAction | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| aclScope | create | AclManagementService.createScope | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| aclScope | update | AclManagementService.updateScope | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| aclScope | destroy | AclManagementService.destroyScope | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |

---

## Collection & Field Management

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| collection | create | CollectionMetadataService.createCollection | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| collection | delete | CollectionMetadataService.deleteCollection | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| field | add | CollectionMetadataService.addField | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| field | drop | CollectionMetadataService.dropField | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |

---

## UI Schema

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| uiSchema | insertAdjacent (create) | UiSchemaService.insertAdjacent | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| uiSchema | patch (update) | UiSchemaService.patch | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| uiSchema | remove (destroy) | UiSchemaService.deleteRecursiveByUid | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |

---

## System Settings

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| systemSettings | update | SystemSettingsService.update | CHECK | CHECK | CHECK | CHECK | CHECK (sensitive keys filtered) | REQUIRED / REQUIRES_NEW |

---

## Data Sources

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| dataSource | create | DataSourceConfigService.create | CHECK | CHECK | CHECK | CHECK | CHECK (password redacted) | REQUIRED / REQUIRES_NEW |
| dataSource | update | DataSourceConfigService.update | CHECK | CHECK | CHECK | CHECK | CHECK (password redacted) | REQUIRED / REQUIRES_NEW |
| dataSource | destroy | DataSourceConfigService.delete | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| dataSource | testConnection | DataSourceConfigService.testConnection | CHECK | CHECK | CHECK | CHECK | CHECK (password redacted) | N/A / REQUIRES_NEW |

---

## Plugins (via PluginModuleRegistry)

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| plugin | enable | PluginModuleRegistry.enable | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| plugin | disable | PluginModuleRegistry.disable | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| plugin | install | PluginModuleRegistry.install | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| plugin | uninstall | PluginModuleRegistry.uninstall | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| plugin | remove (delete) | PluginModuleRegistry.delete | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |

---

## Application Plugins (delegates to PluginModuleRegistry)

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| plugin | enable | ApplicationPluginController -> PluginModuleRegistry.enable | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| plugin | disable | ApplicationPluginController -> PluginModuleRegistry.disable | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| plugin | uninstall | ApplicationPluginController -> PluginModuleRegistry.uninstall | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |
| plugin | remove | ApplicationPluginController -> PluginModuleRegistry.delete | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |

---

## Bootstrap

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| system | bootstrap | BootstrapController.setup | CHECK | CHECK | CHECK | CHECK (null, no user yet) | CHECK (no password) | REQUIRED / N/A |

---

## Dynamic CRUD (via GenericCrudController -> DynamicRepository)

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| dynamicCrud | create | DynamicRepository.create | CHECK | CHECK | CHECK | CHECK | CHECK (only metadata) | REQUIRED / REQUIRES_NEW |
| dynamicCrud | update | DynamicRepository.update | CHECK | CHECK | CHECK | CHECK | CHECK (only field names) | REQUIRED / REQUIRES_NEW |
| dynamicCrud | destroy | DynamicRepository.destroy | CHECK | CHECK | CHECK | CHECK | CHECK | REQUIRED / REQUIRES_NEW |

---

## Dynamic Associations (via GenericCrudController -> AssociationActionService)

| Resource | Action | Entry Point | Success Audit | Failure Audit | requestId | actorUserId | Sanitization | Transaction Behavior |
|----------|--------|------------|---------------|---------------|-----------|-------------|--------------|---------------------|
| association | add | AssociationActionService.add | CHECK | CHECK | CHECK | CHECK | CHECK (only IDs) | REQUIRED / REQUIRES_NEW |
| association | remove | AssociationActionService.remove | CHECK | CHECK | CHECK | CHECK | CHECK (only IDs) | REQUIRED / REQUIRES_NEW |
| association | set | AssociationActionService.set | CHECK | CHECK | CHECK | CHECK | CHECK (only IDs + count) | REQUIRED / REQUIRES_NEW |

---

## Summary

| Metric | Count | Percentage |
|--------|-------|------------|
| **Total write entry points** | 44 | 100% |
| **Success audit coverage** | 44 / 44 | 100% |
| **Failure audit coverage** | 44 / 44 | 100% |
| **requestId coverage** | 44 / 44 | 100% |
| **actorUserId coverage** | 44 / 44 | 100% |
| **Sanitization coverage** | 44 / 44 | 100% |

### Remaining Gaps

**None.** All 44 write entry points have full audit coverage (success + failure).

### Notes

1. **requestId**: All services use `RequestIdContext.get()` via `AuditLogService.buildAuditLog()`, which auto-generates a UUID if no request ID is available. This ensures 100% coverage.

2. **actorUserId**: All services use `CurrentUserContext.getCurrentUserId()` via `AuditLogService.buildAuditLog()`. For bootstrap, the actor is null (no user exists yet).

3. **Sanitization**: `AuditLogService.buildAuditLog()` calls `sanitizeDetails()` which:
   - Redacts keys containing "password", "token", "secret", "privatekey", "credential", "jdbc", "sql", "driver", "masterkey", "apikey", "authkey", "encryption"
   - Strips JDBC URLs, SQL fragments, token-like patterns, password= patterns, secret= patterns, Bearer tokens
   - Dynamic CRUD services only record metadata (collection name, field names, record IDs) -- never full record values

4. **Transaction Behavior**: Success audits use `REQUIRED` (fail-fast -- if audit write fails, the business transaction rolls back). Failure audits use `REQUIRES_NEW` (audit write in its own transaction -- if it fails, the original error is not compounded).

5. **DynamicRepository and AssociationActionService** were added in Phase 14 as part of the audit coverage gap filling.