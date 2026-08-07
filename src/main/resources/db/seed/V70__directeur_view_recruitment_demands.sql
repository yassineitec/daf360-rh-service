-- ============================================================
-- V70 — Grant RH_VIEW_RECRUITMENT_DEMAND to Directeur
--
-- No schema change: a permission grant only. Safe to apply any time, and nothing 500s
-- without it — the symptom is a 403, described below.
--
-- THE BUG: V31 granted RH_APPROVE_RECRUITMENT_DEMAND to Directeur / DRH / Administrateur,
-- but granted RH_VIEW_RECRUITMENT_DEMAND only to Manager / Responsable Technique /
-- Responsable RH / DRH / Administrateur — Directeur was left out of the VIEW list.
--
-- So the one role whose whole purpose here is approving could not READ a demand:
--   GET  /api/hr/recruitment-demands        → 403 (needs VIEW)
--   GET  /api/hr/recruitment-demands/{id}   → 403 (needs VIEW)
--   POST /api/hr/recruitment-demands/{id}/review → allowed, but unreachable in practice
-- The route guard passes (it accepts any of the three codes), so a Directeur reached
-- /rh/recruitment-demands and got an empty screen with a load error.
--
-- Approving something you cannot read is not a coherent permission set. Rather than widen
-- the review endpoint, VIEW is granted where APPROVE already is.
--
--   sqlcmd -S <server> -d DAF360_HR -i V70__directeur_view_recruitment_demands.sql
-- Re-runnable: NOT EXISTS guard, same shape as V31's own inserts.
-- Created: 2026-08-07
-- ============================================================

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_VIEW_RECRUITMENT_DEMAND'
FROM [dbo].[Roles] r
WHERE r.frenchName = 'Directeur'
  AND (r.deleted IS NULL OR r.deleted = 0)
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp
      WHERE rp.role_id = r.id
        AND rp.permission = 'RH_VIEW_RECRUITMENT_DEMAND'
  );
GO

-- Any other role holding APPROVE without VIEW has the same broken combination. Reported
-- rather than auto-granted: a permission set nobody configured deliberately deserves a look.
PRINT '--- Roles that can APPROVE recruitment demands but not VIEW them ---';
SELECT r.id, r.frenchName
FROM [dbo].[Roles] r
JOIN [dbo].[RolePermissions] a
  ON a.role_id = r.id AND a.permission = 'RH_APPROVE_RECRUITMENT_DEMAND'
WHERE (r.deleted IS NULL OR r.deleted = 0)
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] v
      WHERE v.role_id = r.id AND v.permission = 'RH_VIEW_RECRUITMENT_DEMAND'
  );
GO

PRINT 'V70 applied: Directeur can now read the recruitment demands it approves.';
GO
