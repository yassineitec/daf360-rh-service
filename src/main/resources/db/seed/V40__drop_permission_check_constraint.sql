-- =============================================================================
-- V40: Permanently remove CK_RolePermissions_Permission CHECK constraint
--
-- Context:
--   This file was formerly V32__add_fact_permissions.sql, which maintained a
--   hardcoded allowlist of every permitted permission code.  As new permissions
--   were added in V33-V39 (lifecycle, offboarding, entretiens, self-service,
--   FACT_VIEW_ALL_CLIENTS …) the list became impossible to keep in sync and
--   caused INSERT failures in later migrations.
--
--   V39 Step 0 already drops the constraint on a live DB so that its inserts
--   succeed.  This migration is the idempotent safety net for a fresh DB where
--   the original V32 script was applied manually via SSMS.
--
--   PermissionCatalog.java is the single source of truth for allowed codes.
--   No database-level CHECK constraint is needed or maintained going forward.
--
-- Safe to re-run — guarded with IF EXISTS.
-- =============================================================================

USE [DAF360_HR];

IF EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE  name               = 'CK_RolePermissions_Permission'
      AND  parent_object_id   = OBJECT_ID('dbo.RolePermissions')
)
BEGIN
    ALTER TABLE [dbo].[RolePermissions] DROP CONSTRAINT CK_RolePermissions_Permission;
    PRINT 'V40 — CK_RolePermissions_Permission dropped.';
END
ELSE
    PRINT 'V40 — constraint already absent (dropped by V39 or never applied). Nothing to do.';
