-- ─────────────────────────────────────────────────────────────────────────────
-- V31b — Corrective: RolePermissions for recruitment demand feature
-- Run once in SSMS after V31 has already been applied.
-- All INSERTs are idempotent (NOT EXISTS guard).
-- ─────────────────────────────────────────────────────────────────────────────

-- ── VIEW + CREATE ────────────────────────────────────────────────────────────
-- Managers and directors who submit staffing demands

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_VIEW_RECRUITMENT_DEMAND'
FROM [dbo].[Roles] r
WHERE r.frenchName IN (
    N'Responsable Génie Civil',
    N'Responsable commercial',
    N'Responsable planner',
    N'Responsable IT',
    N'Responsable IT (Egypt)',
    N'DDC',
    N'Directeur Administratif et Financier (DAF)',
    N'Directeur des Ressources Humaines (DRH)',
    N'PDG',
    N'Ressources Humaines (RH)',
    N'Assistante RH'
)
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = 'RH_VIEW_RECRUITMENT_DEMAND'
);

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_CREATE_RECRUITMENT_DEMAND'
FROM [dbo].[Roles] r
WHERE r.frenchName IN (
    N'Responsable Génie Civil',
    N'Responsable commercial',
    N'Responsable planner',
    N'Responsable IT',
    N'Responsable IT (Egypt)',
    N'DDC',
    N'Directeur Administratif et Financier (DAF)',
    N'Directeur des Ressources Humaines (DRH)',
    N'PDG'
)
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = 'RH_CREATE_RECRUITMENT_DEMAND'
);

-- ── APPROVE ──────────────────────────────────────────────────────────────────
-- Directors and DRH who validate/reject demands

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_APPROVE_RECRUITMENT_DEMAND'
FROM [dbo].[Roles] r
WHERE r.frenchName IN (
    N'Directeur des Ressources Humaines (DRH)',
    N'Directeur Administratif et Financier (DAF)',
    N'PDG'
)
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = 'RH_APPROVE_RECRUITMENT_DEMAND'
);

-- ── Verification query (run after to confirm) ─────────────────────────────────
-- SELECT r.frenchName, rp.permission
-- FROM [dbo].[RolePermissions] rp
-- JOIN [dbo].[Roles] r ON r.id = rp.role_id
-- WHERE rp.permission IN (
--     'RH_VIEW_RECRUITMENT_DEMAND',
--     'RH_CREATE_RECRUITMENT_DEMAND',
--     'RH_APPROVE_RECRUITMENT_DEMAND'
-- )
-- ORDER BY r.frenchName, rp.permission;
