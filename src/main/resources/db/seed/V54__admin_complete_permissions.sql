-- =============================================================================
-- V54: Grant ALL missing permissions to Administrateur role
--
-- RH gaps (7): lifecycle/interview codes never seeded
--   → /lifecycle route was fully blocked (permissionGuard requires RH_VIEW_CONTRACTS
--     or RH_MANAGE_LIFECYCLE, neither of which Administrateur had)
--
-- FACT_* gaps (23): most facturation codes never seeded
--   → Backend @PreAuthorize checks fail without FACT_SUPER_ADMIN in JWT
--   → Extra template codes added for completeness
--
-- Already granted in prior migrations (skipped here):
--   V41: FACT_VIEW_AFFAIRE, FACT_MANAGE_AFFAIRE
--   V42: FACT_VIEW_ALL_CLIENTS
--
-- Safe to re-run — every INSERT is guarded with NOT EXISTS.
-- =============================================================================

USE [DAF360_HR];

DECLARE @codes TABLE (permission NVARCHAR(255));
INSERT INTO @codes VALUES

    -- ── RH: Cycle de vie (never seeded) ─────────────────────────────────────
    ('RH_VIEW_CONTRACTS'),
    ('RH_CREATE_CONTRACT'),
    ('RH_MANAGE_LIFECYCLE'),
    ('RH_VALIDATE_TRIAL_PERIOD'),
    ('RH_MANAGE_ALERTS'),

    -- ── RH: Entretiens (never seeded) ───────────────────────────────────────
    ('RH_ADMIN_INTERVIEW_TYPES'),
    ('RH_MANAGE_INTERVIEWS'),

    -- ── FACT_* catalog codes (never seeded except VIEW_AFFAIRE/MANAGE_AFFAIRE/VIEW_ALL_CLIENTS) ──
    ('FACT_VIEW_COST'),
    ('FACT_MANAGE_COST'),
    ('FACT_ADMIN_COST'),
    ('FACT_VIEW_INVOICING'),
    ('FACT_MANAGE_INVOICING'),
    ('FACT_VIEW_BILLING'),
    ('FACT_MANAGE_BILLING'),
    ('FACT_VIEW_PAYMENT'),
    ('FACT_MANAGE_PAYMENT'),
    ('FACT_SUPER_ADMIN'),

    -- ── FACT_* extra codes used in templates (not in FactPermissionCatalog) ─
    ('FACT_CREATE_TS'),
    ('FACT_UPDATE_AFFAIRE'),
    ('FACT_VALIDER_BUDGET'),
    ('FACT_APPROVE_COST_L1'),
    ('FACT_VALIDATE_KYC'),
    ('FACT_VALIDATE_DF'),
    ('FACT_CHEF_PROJET'),
    ('FACT_VALIDATE_RF'),
    ('FACT_BANK_RECONCILIATION'),
    ('FACT_MANAGE_ST'),
    ('FACT_MANAGE_SUPPLIERS'),
    ('FACT_VALID_TECHNIQUE_TS'),
    ('FACT_VALID_COMMERCIALE_TS');

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, c.permission
FROM [dbo].[Roles] r
CROSS JOIN @codes c
WHERE r.frenchName = N'Administrateur'
  AND r.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp
      WHERE rp.role_id = r.id AND rp.permission = c.permission
  );

PRINT 'V54: Inserted ' + CAST(@@ROWCOUNT AS VARCHAR) + ' permission(s) for Administrateur.';
GO
