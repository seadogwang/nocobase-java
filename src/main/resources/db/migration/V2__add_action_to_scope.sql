-- V2: Add action column to role_resource_scopes for per-action scope support
ALTER TABLE "role_resource_scopes" ADD COLUMN IF NOT EXISTS "action" VARCHAR(50);

-- Set existing scopes to 'list' as default action for backward compatibility
UPDATE "role_resource_scopes" SET "action" = 'list' WHERE "action" IS NULL;