-- =============================================================================
-- V42: FACT_VIEW_ALL_CLIENTS — grant to affaire-creating roles
--
-- The affaire creation wizard (step 2 "Informations générales") always calls
-- GET /api/fact/clients/dropdown?pays=0 to populate the Client dropdown.
-- The PaysIsolationInterceptor blocks that call with 403 for users who lack
-- FACT_VIEW_ALL_CLIENTS, leaving the dropdown empty and making it impossible
-- to proceed. Granting this permission to all roles that can create affaires
-- (same set as FACT_MANAGE_AFFAIRE) restores the full client list.
--
-- Safe to re-run — every INSERT is guarded with a NOT EXISTS check.
-- =============================================================================

USE [DAF360_HR];

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'FACT_VIEW_ALL_CLIENTS'
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
    WHERE rp.role_id = r.id AND rp.permission = 'FACT_VIEW_ALL_CLIENTS'
);

PRINT 'FACT_VIEW_ALL_CLIENTS granted to ' + CAST(@@ROWCOUNT AS VARCHAR) + ' role(s).';

-- ── Verification ─────────────────────────────────────────────────────────────
-- SELECT r.frenchName, rp.permission
-- FROM [dbo].[RolePermissions] rp
-- JOIN [dbo].[Roles] r ON r.id = rp.role_id
-- WHERE rp.permission = 'FACT_VIEW_ALL_CLIENTS'
-- ORDER BY r.frenchName;
