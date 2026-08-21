-- V74: Per-role country (pays) data scope
--
-- Until now data isolation was per-USER and single-valued: Users.pays_id landed in the
-- JWT as a scalar `paysId` claim, and TenantService.getEffectivePaysId() returned either
-- that one id or null (= see everything) for anyone holding ADMIN_ROLES. There was no way
-- to say "this role sees TN, EGY and UAE but not the rest".
--
-- This table adds that, together with an explicit MODE on the role saying how to read it.
--
-- The mode is not optional sugar — without it the two cases below are indistinguishable,
-- and confusing them leaks data across entities:
--
--   "Responsable GC" is held by Tunisian AND Egyptian users, and each must see only their
--   own country. Listing {TN, EG} against that role would let a Tunisian holder read
--   Egyptian data. What that role wants is mode = OWN.
--
--   "RH" genuinely spans TN + EG + UAE for every holder, whatever their own country.
--   That role wants mode = LIST with those three ids.
--
-- Hence Roles.pays_scope_mode:
--
--   OWN  (default) -> the user's own Users.pays_id, one country, per user.
--                     Exactly the pre-V74 behaviour. Any rows in RolePaysScope are ignored
--                     (kept, not deleted, so switching modes back does not lose them).
--   LIST           -> exactly the pays_ids listed in RolePaysScope, same set for every
--                     holder regardless of their own pays_id.
--   ALL            -> unrestricted, every country.
--
-- Backfill maps every existing role onto the mode that reproduces what it does today:
-- showAll = 1 becomes ALL, everything else becomes OWN. So applying this migration changes
-- no behaviour anywhere until someone deliberately switches a role to LIST.
--
-- Roles.showAll already existed on the entity, DTOs and admin UI but was read by NO
-- authorization code (the only mention was a stale comment in EmployeeProfileController).
-- It now becomes the "all countries" switch rather than dead weight.
--
-- Re-runnable: guarded by IF NOT EXISTS.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'RolePaysScope' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    -- No FOREIGN KEY to [Roles] or [pays] on purpose. Both are Timesheet-owned shared
    -- tables (see the comment on domain/Role.java) and every other pays_id column in this
    -- schema — notification_routing_rules, regimes, breaks, the V23 dimension tables —
    -- is likewise declared without one, because their id columns are not guaranteed to be
    -- BIGINT here. Orphans are cleaned up by RoleService.deleteRole instead.
    CREATE TABLE [dbo].[RolePaysScope] (
        [role_id] BIGINT NOT NULL,
        [pays_id] BIGINT NOT NULL,
        CONSTRAINT [PK_RolePaysScope] PRIMARY KEY ([role_id], [pays_id])
    );
    -- The composite PK is (role_id, pays_id), so lookups by role_id already have a
    -- covering index and the pair is a set — a country cannot be listed twice for a role.
END
GO

-- ── Roles.pays_scope_mode ─────────────────────────────────────────────────────────────
-- snake_case on purpose: unlike frenchName/showAll this needs no backtick quoting to
-- survive Hibernate's CamelCaseToUnderscoresNamingStrategy (same as parent_role_id).
IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.Roles') AND name = 'pays_scope_mode')
BEGIN
    ALTER TABLE [dbo].[Roles] ADD [pays_scope_mode] NVARCHAR(10) NULL;
END
GO

-- Backfill: reproduce today's behaviour exactly. Runs on rows that are still NULL only, so
-- re-running never overwrites a mode an administrator has since chosen.
UPDATE [dbo].[Roles]
   SET [pays_scope_mode] = CASE WHEN [showAll] = 1 THEN 'ALL' ELSE 'OWN' END
 WHERE [pays_scope_mode] IS NULL;
GO

-- Widening a column to NOT NULL requires that NOTHING references it: SQL Server raises
-- Msg 5074 / 4922 for EVERY dependent object, one at a time — first the DEFAULT, then the
-- CHECK. So both are dropped, the column is altered, and both are re-created.
--
-- Each step is guarded on its own condition rather than sharing one, because guarding the
-- ALTER behind "does the default exist" meant that once the default was created a re-run
-- skipped the ALTER silently and left the column nullable for good. The drop/re-create
-- shape also repairs a database left half-applied by an earlier version of this script.

-- 1. Drop both dependents so the column can be altered.
IF EXISTS (SELECT 1 FROM sys.default_constraints
           WHERE parent_object_id = OBJECT_ID('dbo.Roles') AND name = 'DF_Roles_PaysScopeMode')
BEGIN
    ALTER TABLE [dbo].[Roles] DROP CONSTRAINT [DF_Roles_PaysScopeMode];
END
GO

IF EXISTS (SELECT 1 FROM sys.check_constraints
           WHERE parent_object_id = OBJECT_ID('dbo.Roles') AND name = 'CK_Roles_PaysScopeMode')
BEGIN
    ALTER TABLE [dbo].[Roles] DROP CONSTRAINT [CK_Roles_PaysScopeMode];
END
GO

-- 2. Re-run the backfill: a role inserted between an earlier attempt and this one would
--    still be NULL, and ALTER COLUMN ... NOT NULL fails if any row is.
UPDATE [dbo].[Roles]
   SET [pays_scope_mode] = CASE WHEN [showAll] = 1 THEN 'ALL' ELSE 'OWN' END
 WHERE [pays_scope_mode] IS NULL;
GO

-- 3. NOT NULL, only while the column is still nullable.
IF EXISTS (SELECT 1 FROM sys.columns
           WHERE object_id = OBJECT_ID('dbo.Roles') AND name = 'pays_scope_mode' AND is_nullable = 1)
BEGIN
    ALTER TABLE [dbo].[Roles] ALTER COLUMN [pays_scope_mode] NVARCHAR(10) NOT NULL;
END
GO

-- 4. Re-create the default. New roles created outside the API (seed scripts, manual
--    inserts) then land on OWN, the narrowest of the three, rather than failing on NULL.
IF NOT EXISTS (SELECT 1 FROM sys.default_constraints
               WHERE parent_object_id = OBJECT_ID('dbo.Roles') AND name = 'DF_Roles_PaysScopeMode')
BEGIN
    ALTER TABLE [dbo].[Roles]
        ADD CONSTRAINT [DF_Roles_PaysScopeMode] DEFAULT 'OWN' FOR [pays_scope_mode];
END
GO

-- 5. Re-create the CHECK. Re-adding it re-validates every row, so this also proves no
--    value outside the three modes slipped in while the constraint was dropped.
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints
               WHERE parent_object_id = OBJECT_ID('dbo.Roles') AND name = 'CK_Roles_PaysScopeMode')
BEGIN
    ALTER TABLE [dbo].[Roles] ADD CONSTRAINT [CK_Roles_PaysScopeMode]
        CHECK ([pays_scope_mode] IN ('OWN', 'LIST', 'ALL'));
END
GO

-- ── Applied-state check (read-only) ───────────────────────────────────────────────────
-- All four rows must read OK. The earlier version of this script could leave
-- "pays_scope_mode NOT NULL" as MISSING while everything else looked fine.
SELECT 'RolePaysScope table' AS object_name,
       CASE WHEN EXISTS (SELECT 1 FROM sys.tables
                         WHERE name = 'RolePaysScope' AND schema_id = SCHEMA_ID('dbo'))
            THEN 'OK' ELSE 'MISSING' END AS state
UNION ALL
SELECT 'pays_scope_mode column',
       CASE WHEN EXISTS (SELECT 1 FROM sys.columns
                         WHERE object_id = OBJECT_ID('dbo.Roles') AND name = 'pays_scope_mode')
            THEN 'OK' ELSE 'MISSING' END
UNION ALL
SELECT 'pays_scope_mode NOT NULL',
       CASE WHEN EXISTS (SELECT 1 FROM sys.columns
                         WHERE object_id = OBJECT_ID('dbo.Roles') AND name = 'pays_scope_mode'
                           AND is_nullable = 0)
            THEN 'OK' ELSE 'MISSING' END
UNION ALL
SELECT 'DF_Roles_PaysScopeMode',
       CASE WHEN EXISTS (SELECT 1 FROM sys.default_constraints
                         WHERE parent_object_id = OBJECT_ID('dbo.Roles')
                           AND name = 'DF_Roles_PaysScopeMode')
            THEN 'OK' ELSE 'MISSING' END
UNION ALL
SELECT 'CK_Roles_PaysScopeMode',
       CASE WHEN EXISTS (SELECT 1 FROM sys.check_constraints
                         WHERE parent_object_id = OBJECT_ID('dbo.Roles')
                           AND name = 'CK_Roles_PaysScopeMode')
            THEN 'OK' ELSE 'MISSING' END
UNION ALL
SELECT 'roles still NULL (must be 0)',
       CAST(COUNT(*) AS NVARCHAR(20))
  FROM [dbo].[Roles] WHERE [pays_scope_mode] IS NULL;
GO

-- ── Verification (read-only) ──────────────────────────────────────────────────────────
-- Every role next to the countries its active holders actually sit in. Nothing here is a
-- problem to fix: a role spanning several countries in mode OWN is the NORMAL, correct
-- state — "Responsable GC" held by Tunisians and Egyptians stays OWN, and each holder keeps
-- seeing only their own country, exactly as before this migration.
--
-- Use it to decide, per role, whether it should later become LIST: switch a role to LIST
-- only when EVERY holder is meant to see ALL the listed countries, not merely their own.
SELECT r.id                                   AS role_id,
       r.frenchName                            AS role_name,
       r.pays_scope_mode                       AS mode,
       COUNT(DISTINCT u.pays_id)               AS distinct_countries_of_holders,
       COUNT(u.id)                             AS active_holders,
       STRING_AGG(CONVERT(NVARCHAR(MAX), p.french_label), ', ') AS holder_countries
  FROM [dbo].[Roles] r
  LEFT JOIN [dbo].[Users] u
         ON u.role_id = r.id AND (u.isActive = 1 OR u.isActive IS NULL)
  LEFT JOIN [dbo].[pays]  p ON p.id = u.pays_id
 WHERE (r.deleted = 0 OR r.deleted IS NULL)
 GROUP BY r.id, r.frenchName, r.pays_scope_mode
 ORDER BY COUNT(DISTINCT u.pays_id) DESC, r.frenchName;
GO

-- OPTIONAL, run by hand only if [Roles].[id] and [pays].[id] are BIGINT in your instance
-- (check with: SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
--  WHERE TABLE_NAME IN ('Roles','pays') AND COLUMN_NAME = 'id';). If they are INT, adding
-- these constraints fails with a type-mismatch error — that is why they are not above.
--
-- ALTER TABLE [dbo].[RolePaysScope] ADD CONSTRAINT [FK_RolePaysScope_Role]
--     FOREIGN KEY ([role_id]) REFERENCES [dbo].[Roles]([id]) ON DELETE CASCADE;
-- ALTER TABLE [dbo].[RolePaysScope] ADD CONSTRAINT [FK_RolePaysScope_Pays]
--     FOREIGN KEY ([pays_id]) REFERENCES [dbo].[pays]([id]);
-- GO
