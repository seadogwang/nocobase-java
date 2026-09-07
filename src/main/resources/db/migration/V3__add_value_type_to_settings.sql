-- V3: Add value_type column to system_settings for explicit type storage
-- Supports: string, boolean, number, json, null
-- Old records without value_type continue to work (type guessing fallback)
ALTER TABLE "system_settings" ADD COLUMN IF NOT EXISTS "value_type" VARCHAR(20);