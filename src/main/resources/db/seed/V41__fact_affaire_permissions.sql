-- =============================================================================
-- V41: FACT_VIEW_AFFAIRE + FACT_MANAGE_AFFAIRE role assignments
--
-- These two permissions were added to PermissionCatalog but were never seeded
-- to RolePermissions, causing a 403 on /api/fact/affaires/{id}/livrables/*
-- when selecting "Livrable" as mode de facturation in the affaire stepper.
--
-- FACT_VIEW_AFFAIRE  — guards GET disciplines / WBS / documents endpoints
-- FACT_MANAGE_AFFAIRE — guards POST/PUT livrable affectation endpoints
--
-- Safe to re-run — every INSERT is guarded with a NOT EXISTS check.
-- =============================================================================

USE [DAF360_HR];

-- ── FACT_VIEW_AFFAIRE ────────────────────────────────────────────────────────
-- Roles that need read access to affaire livrables / disciplines

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'FACT_VIEW_AFFAIRE'
FROM [dbo].[Roles] r
WHERE r.frenchName IN (
    N'Administrateur',
    N'PDG',
    N'Directeur Administratif et Financier (DAF)',
    N'Directeur des Ressources Humaines (DRH)',
    N'DDC',
    N'Responsable commercial',
    N'Responsable Génie Civil',
    N'Responsable planner',
    N'Chargé de facturation',
    N'Responsable facturation'
)
AND r.deleted = 0
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = 'FACT_VIEW_AFFAIRE'
);

PRINT 'FACT_VIEW_AFFAIRE granted to ' + CAST(@@ROWCOUNT AS VARCHAR) + ' role(s).';

-- ── FACT_MANAGE_AFFAIRE ──────────────────────────────────────────────────────
-- Roles that can create / update livrable affectations within an affaire

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'FACT_MANAGE_AFFAIRE'
FROM [dbo].[Roles] r
WHERE r.frenchName IN (
    N'Administrateur',
    N'PDG',
    N'Directeur Administratif et Financier (DAF)',
    N'DDC',
    N'Responsable commercial',
    N'Chargé de facturation',
    N'Responsable facturation'
)
AND r.deleted = 0
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = 'FACT_MANAGE_AFFAIRE'
);

PRINT 'FACT_MANAGE_AFFAIRE granted to ' + CAST(@@ROWCOUNT AS VARCHAR) + ' role(s).';

-- ── Verification ─────────────────────────────────────────────────────────────
-- SELECT r.frenchName, rp.permission
-- FROM [dbo].[RolePermissions] rp
-- JOIN [dbo].[Roles] r ON r.id = rp.role_id
-- WHERE rp.permission IN ('FACT_VIEW_AFFAIRE', 'FACT_MANAGE_AFFAIRE')
-- ORDER BY r.frenchName, rp.permission;
