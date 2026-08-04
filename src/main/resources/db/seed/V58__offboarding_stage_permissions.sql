-- ============================================================
-- V58 — Offboarding: per-stage permissions
--
-- WHY: V44 put every offboarding endpoint behind a single RH_MANAGE_OFFBOARDING,
-- granted only to DRH + Administrateur. The consequence is that the people who OWN
-- the work cannot do it — an IT officer cannot record a returned laptop, finance
-- cannot touch the solde de tout compte, a manager cannot close a passation. All of
-- it had to be clicked by RH on their behalf.
--
-- The task catalog already names the owner of every task (`owner_role`:
-- IT_OFFICER, FACILITIES_OFFICER, HOME_BASE_MANAGER, HR_OFFICER, FINANCE_OFFICER)
-- but `owner_role` is a free string with no join to Roles, so it can never be an
-- authorization input. These three codes are what the wizard's stages 3, 4 and 6
-- check instead.
--
-- Stages 1 (Déclaration), 2 (Validation), 5 (Kit RH) and 7 (Clôture) need NO new
-- code — RH_MANAGE_OFFBOARDING / RH_VALIDATE_OFFBOARDING / RH_CONDUCT_EXIT_INTERVIEW
-- already exist and are already RH-only, which is exactly the intent.
--
-- RH keeps sight of everything: DRH and Administrateur are granted all three new
-- codes too, so "RH sees and can act on every stage" falls out of the grants rather
-- than being special-cased in the UI.
--
-- rh-service has NO Flyway. Apply by hand:
--   sqlcmd -S <server> -d DAF360_HR -i V58__offboarding_stage_permissions.sql
-- Re-runnable: every insert is guarded by NOT EXISTS.
--
-- ⚠️ Permissions are read from the JWT — every affected user must log out and back
-- in before the new stages open up for them.
-- Created: 2026-08-04
-- ============================================================

-- ── Stage 3 — Passation (HOME_BASE_MANAGER) ──────────────────
-- Department managers, because the handover is signed off by the person receiving
-- the work, plus RH for oversight.
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_OFFBOARDING_STAGE_HANDOVER'
FROM [dbo].[Roles] r
WHERE r.frenchName IN (
    N'Administrateur',
    N'Directeur des Ressources Humaines (DRH)',
    N'Ressources Humaines (RH)',
    N'Responsable commercial',
    N'Responsable Génie Civil',
    N'Responsable planner',
    N'Responsable IT',
    N'Responsable IT (Egypt)'
)
AND (r.deleted = 0 OR r.deleted IS NULL)
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = 'RH_OFFBOARDING_STAGE_HANDOVER'
);
PRINT 'RH_OFFBOARDING_STAGE_HANDOVER granted to ' + CAST(@@ROWCOUNT AS VARCHAR) + ' role(s).';
GO

-- ── Stage 4 — Informatique & Matériel (IT_OFFICER) ───────────
-- Holds the file's only hard gate before validation: ASSET_RETURN_IT is is_blocking.
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_OFFBOARDING_STAGE_IT'
FROM [dbo].[Roles] r
WHERE r.frenchName IN (
    N'Administrateur',
    N'Directeur des Ressources Humaines (DRH)',
    N'Ressources Humaines (RH)',
    N'Responsable IT',
    N'Responsable IT (Egypt)'
)
AND (r.deleted = 0 OR r.deleted IS NULL)
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = 'RH_OFFBOARDING_STAGE_IT'
);
PRINT 'RH_OFFBOARDING_STAGE_IT granted to ' + CAST(@@ROWCOUNT AS VARCHAR) + ' role(s).';
GO

-- ── Stage 6 — Paie & Solde de tout compte (FINANCE_OFFICER) ──
-- Same role set the FACT_* finance permissions use (V41), so finance access to the
-- STC lines up with finance access to billing.
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_OFFBOARDING_STAGE_PAYROLL'
FROM [dbo].[Roles] r
WHERE r.frenchName IN (
    N'Administrateur',
    N'Directeur des Ressources Humaines (DRH)',
    N'Ressources Humaines (RH)',
    N'Directeur Administratif et Financier (DAF)',
    N'Responsable facturation',
    N'Chargé de facturation'
)
AND (r.deleted = 0 OR r.deleted IS NULL)
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = 'RH_OFFBOARDING_STAGE_PAYROLL'
);
PRINT 'RH_OFFBOARDING_STAGE_PAYROLL granted to ' + CAST(@@ROWCOUNT AS VARCHAR) + ' role(s).';
GO

-- ── Stage owners also need to READ the file ──────────────────
-- The /rh/offboarding route and GET /api/hr/offboarding/{id} are gated on
-- RH_MANAGE_OFFBOARDING | RH_VIEW_CONTRACTS | RH_MANAGE_LIFECYCLE. Without one of
-- them an IT officer holding RH_OFFBOARDING_STAGE_IT could not open the file at all,
-- so the new code would grant nothing. RH_VIEW_CONTRACTS is the read-only one.
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_VIEW_CONTRACTS'
FROM [dbo].[Roles] r
WHERE r.frenchName IN (
    N'Responsable IT',
    N'Responsable IT (Egypt)',
    N'Directeur Administratif et Financier (DAF)',
    N'Responsable facturation',
    N'Chargé de facturation',
    N'Responsable commercial',
    N'Responsable Génie Civil',
    N'Responsable planner'
)
AND (r.deleted = 0 OR r.deleted IS NULL)
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = 'RH_VIEW_CONTRACTS'
);
PRINT 'RH_VIEW_CONTRACTS granted to ' + CAST(@@ROWCOUNT AS VARCHAR) + ' stage-owner role(s).';
GO

-- Verify:
-- SELECT r.frenchName, rp.permission
-- FROM [dbo].[RolePermissions] rp JOIN [dbo].[Roles] r ON r.id = rp.role_id
-- WHERE rp.permission LIKE 'RH_OFFBOARDING_STAGE%'
-- ORDER BY rp.permission, r.frenchName;
