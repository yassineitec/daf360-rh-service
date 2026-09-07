-- =============================================================================
-- V84__rh_super_admin_permission.sql
--
-- New permission: RH_SUPER_ADMIN — lets a holder pick ANY country on admin
-- screens that otherwise scope to their own pays (Jours fériés "Ajouter",
-- Gestion de pause filter). See PermissionCatalog.RH_SUPER_ADMIN.
--
-- No CK_RolePermissions_Permission CHECK constraint to extend — it was dropped
-- for good in V40; PermissionCatalog.java is the only source of truth now.
--
-- Granted to holders of HR_ADMIN_ROLES (the actual "/rh/admin" gate), not
-- blanket-granted to every role: `daf360_access` sits close to its 4096-byte
-- cookie limit for the widest roles already (see the note in
-- V76__it_asset_assignments.sql) — every extra code on the heaviest role is a
-- cost paid on every request, so this stays opt-in per role rather than
-- automatic for "Administrateur" alone.
--
-- Holders must sign out/in again: the JWT `permissions` claim only refreshes
-- at login.
--
-- Safe to re-run — the NOT EXISTS guard makes it idempotent.
-- =============================================================================

USE [DAF360_HR];

INSERT INTO [dbo].[RolePermissions] ([role_id], [permission])
SELECT DISTINCT rp.[role_id], N'RH_SUPER_ADMIN'
FROM   [dbo].[RolePermissions] rp
WHERE  rp.[permission] = N'HR_ADMIN_ROLES'
  AND  NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] x
                   WHERE x.[role_id] = rp.[role_id]
                     AND x.[permission] = N'RH_SUPER_ADMIN');
GO
