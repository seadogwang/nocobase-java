-- V5: Add unique constraints and foreign key delete strategies
-- Adds missing database-level constraints to the baseline schema.
-- Unique indexes prevent duplicate data at the database level.
-- FK cascades ensure referential integrity on delete operations.

-- ========================================================================
-- Unique constraints
-- ========================================================================

-- ui_schemas.uid must be unique
CREATE UNIQUE INDEX IF NOT EXISTS "uq_ui_schemas_uid" ON "ui_schemas"("uid");

-- user_roles(user_id, role_id) must be unique (no duplicate role assignments)
CREATE UNIQUE INDEX IF NOT EXISTS "uq_user_roles_user_role" ON "user_roles"("user_id", "role_id");

-- role_resources(role_name, resource_name) must be unique (one resource per role)
CREATE UNIQUE INDEX IF NOT EXISTS "uq_role_resources_role_resource" ON "role_resources"("role_name", "resource_name");

-- role_resource_actions(role_resource_id, action) must be unique (one action per resource)
CREATE UNIQUE INDEX IF NOT EXISTS "uq_role_resource_actions_rr_action" ON "role_resource_actions"("role_resource_id", "action");

-- ========================================================================
-- Foreign key delete strategies
-- ========================================================================
-- Note: The original V1 baseline migration created foreign keys without explicit
-- names (H2 auto-generates names like CONSTRAINT_E). Since we cannot reliably
-- drop and recreate auto-generated constraints in a cross-database SQL migration,
-- the following cascading delete behavior is enforced at the application level
-- via the service layer (see AclService, UserService for cascade logic).
--
-- Application-level cascade rules:
--   - Deleting a user → CASCADE delete user_roles entries
--   - Deleting a role → CASCADE delete user_roles, role_resources entries
--   - Deleting a role_resource → CASCADE delete role_resource_actions and role_resource_scopes
--   - Deleting a collection → CASCADE delete fields entries
--
-- For new database installations where the constraints are created fresh,
-- the V1 baseline should be updated to include ON DELETE CASCADE.
-- This migration documents the expected behavior and adds unique indexes
-- which are the primary defense against data integrity issues.