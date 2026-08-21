-- Grant EVERY permission code in the platform to one role (default: role_id = 7).
--
-- Not V-numbered on purpose: this is an operational grant, not a schema change, and the
-- same file is meant to be re-run for other roles by editing @RoleId below.
--
-- Codes are the union of the three catalogs that own them (114 total):
--   PermissionCatalog.ALL_CODES      — 86, incl. the 16 PAYROLL_* mirrored for grantability
--     (daf360-rh-service/src/main/java/com/daf360/rh/common/PermissionCatalog.java)
--   FactPermissionCatalog.ALL_CODES  — 26 FACT_*
--     (daf360-facturation-service/.../modules/admin/FactPermissionCatalog.java)
--   pointage                         — 2 POINTAGE_*
--
-- Keep this list in sync when a catalog gains a code. The DB cannot help you: the
-- CK_RolePermissions_Permission CHECK constraint was dropped in V40, so a misspelled code
-- inserts happily and simply never matches an authority at runtime.
--
-- Re-runnable: the INSERT anti-joins on the composite PK (role_id, permission).

SET NOCOUNT ON;

DECLARE @RoleId BIGINT = 7;   -- <<< change this to target another role

-- Refuse to run against a role that does not exist or was soft-deleted, rather than
-- inserting orphan rows that would silently do nothing.
IF NOT EXISTS (SELECT 1 FROM [dbo].[Roles]
               WHERE id = @RoleId AND (deleted = 0 OR deleted IS NULL))
BEGIN
    RAISERROR('Role %I64d does not exist or is soft-deleted — nothing was granted.', 16, 1, @RoleId);
    RETURN;
END

DECLARE @Codes TABLE (permission NVARCHAR(100) PRIMARY KEY);

INSERT INTO @Codes (permission) VALUES
-- ── RH catalog: Consultation ───────────────────────────────────────────────────
('VIEW_DASHBOARD'),('VIEW_CANDIDATES'),('VIEW_WORKFLOW'),('VIEW_NOTIFICATIONS'),
-- ── Événements ─────────────────────────────────────────────────────────────────
('MANAGE_EVENTS'),('ADMIN_EVENTS'),
-- ── Utilisateurs ───────────────────────────────────────────────────────────────
('GET_USERS'),('CREATE_USER'),('UPDATE_USER'),('DELETE_USER'),
-- ── Pays ───────────────────────────────────────────────────────────────────────
('GET_PAYS'),('CREATE_PAYS'),('UPDATE_PAYS'),('DELETE_PAYS'),
-- ── Jours fériés ───────────────────────────────────────────────────────────────
('GET_HOLIDAYS'),('CREATE_HOLIDAY'),('UPDATE_HOLIDAY'),('DELETE_HOLIDAY'),
-- ── Rôles & Permissions ────────────────────────────────────────────────────────
('GET_PERMISSIONS'),('GET_ROLES'),('CREATE_ROLE'),('UPDATE_ROLE'),('DELETE_ROLE'),
('HR_ADMIN_ROLES'),
-- ── Congés ─────────────────────────────────────────────────────────────────────
('GET_LEAVES'),('ADD_LEAVE'),('RESPONSE_LEAVE'),('GET_GLOBAL_LEAVES'),('SETTLE_LEAVES'),
-- ── Catégories ─────────────────────────────────────────────────────────────────
('GET_CATEGORIES'),('CREATE_CATEGORY'),('UPDATE_CATEGORY'),('DELETE_CATEGORY'),
-- ── Timesheets (TSR) ───────────────────────────────────────────────────────────
('GET_TSR'),('CREATE_TSR'),('RESPOND_TSR'),('GET_GLOBAL_TSR'),
-- ── Module RH ──────────────────────────────────────────────────────────────────
('HR_CREATE_PROFILE'),('HR_UPDATE_PROFILE'),('HR_ARCHIVE_PROFILE'),('HR_ONBOARDING'),
('CREATE_CANDIDATE'),('EDIT_CANDIDATE'),('ACCEPT_REJECT_CANDIDATE'),
('RH_VIEW_RECRUITMENT_DEMAND'),('RH_CREATE_RECRUITMENT_DEMAND'),
('RH_APPROVE_RECRUITMENT_DEMAND'),('RH_HIRE_CANDIDATE'),('APPROVE_HIRING_COST'),
-- ── Cycle de vie (lifecycle + offboarding) ─────────────────────────────────────
('RH_VIEW_CONTRACTS'),('RH_CREATE_CONTRACT'),('RH_MANAGE_LIFECYCLE'),
('RH_VALIDATE_TRIAL_PERIOD'),('RH_MANAGE_ALERTS'),('RH_MANAGE_OFFBOARDING'),
('RH_VALIDATE_OFFBOARDING'),('RH_COMPLETE_OFFBOARDING_TASK'),('RH_CONDUCT_EXIT_INTERVIEW'),
('RH_OFFBOARDING_STAGE_HANDOVER'),('RH_OFFBOARDING_STAGE_IT'),
('RH_OFFBOARDING_STAGE_PAYROLL'),('RH_SUSPEND_PROFILE'),
-- ── Entretiens ─────────────────────────────────────────────────────────────────
('RH_ADMIN_INTERVIEW_TYPES'),('RH_MANAGE_INTERVIEWS'),
-- ── Temps de travail ───────────────────────────────────────────────────────────
('ADMIN_REGIMES'),('ADMIN_BREAKS'),
-- ── Module IT ──────────────────────────────────────────────────────────────────
('IT_PROVISIONING'),
-- ── Administration ─────────────────────────────────────────────────────────────
('ADMIN_LISTS'),('ADMIN_NOTIFICATIONS'),('ADMIN_ROLES'),
-- ── Module Payroll (16) ────────────────────────────────────────────────────────
('PAYROLL_RUN_SIMULATION'),('PAYROLL_VIEW_INDIVIDUAL'),('PAYROLL_APPROVE_PARAMSET'),
('PAYROLL_APPROVE_PARAMSET_FAST_TRACK'),('PAYROLL_RUN_CALIBRATION'),
('PAYROLL_IMPORT_CALIBRATION'),('PAYROLL_EXPORT_BUDGET'),('PAYROLL_IMPORT_PARTNER'),
('PAYROLL_VIEW_AGGREGATE'),('PAYROLL_VIEW_PARAMSET'),('PAYROLL_UPLOAD_ACTUAL'),
('PAYROLL_SUPER_ADMIN'),('PAYROLL_RUN_ENGINE'),('PAYROLL_VIEW_RESULTS'),
('PAYROLL_MANAGE_RUBRIQUES'),('PAYROLL_MANAGE_COUNTRIES'),
-- ── Facturation (26 FACT_*) ────────────────────────────────────────────────────
('FACT_VIEW_AFFAIRE'),('FACT_MANAGE_AFFAIRE'),('FACT_UPDATE_AFFAIRE'),('FACT_VALIDER_BUDGET'),
('FACT_VIEW_COST'),('FACT_MANAGE_COST'),('FACT_ADMIN_COST'),('FACT_APPROVE_COST_L1'),
('FACT_VIEW_INVOICING'),('FACT_MANAGE_INVOICING'),('FACT_VALIDATE_KYC'),
('FACT_VIEW_BILLING'),('FACT_MANAGE_BILLING'),('FACT_CHEF_PROJET'),
('FACT_VALIDATE_RF'),('FACT_VALIDATE_DF'),
('FACT_VIEW_PAYMENT'),('FACT_MANAGE_PAYMENT'),('FACT_BANK_RECONCILIATION'),
('FACT_MANAGE_SUPPLIERS'),('FACT_MANAGE_ST'),
('FACT_CREATE_TS'),('FACT_VALID_TECHNIQUE_TS'),('FACT_VALID_COMMERCIALE_TS'),
('FACT_VIEW_ALL_CLIENTS'),('FACT_SUPER_ADMIN'),
-- ── Pointage (2) ───────────────────────────────────────────────────────────────
('POINTAGE_MANAGE_STATUS'),('POINTAGE_RESPOND_REQUESTS');

DECLARE @Before INT = (SELECT COUNT(*) FROM [dbo].[RolePermissions] WHERE role_id = @RoleId);

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT @RoleId, c.permission
  FROM @Codes c
 WHERE NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] rp
                    WHERE rp.role_id = @RoleId AND rp.permission = c.permission);

DECLARE @After INT = (SELECT COUNT(*) FROM [dbo].[RolePermissions] WHERE role_id = @RoleId);

SELECT r.id                        AS role_id,
       r.frenchName                AS role_name,
       (SELECT COUNT(*) FROM @Codes) AS codes_in_script,
       @Before                     AS permissions_before,
       @After                      AS permissions_after,
       @After - @Before            AS newly_granted
  FROM [dbo].[Roles] r
 WHERE r.id = @RoleId;

-- Any grant this role holds that is NOT in the catalogs above: either a code retired from a
-- catalog, or a typo from an earlier manual insert. Both are dead weight — nothing matches
-- them at runtime. Empty result = clean.
SELECT rp.permission AS orphan_or_unknown_code
  FROM [dbo].[RolePermissions] rp
 WHERE rp.role_id = @RoleId
   AND rp.permission NOT IN (SELECT permission FROM @Codes)
 ORDER BY rp.permission;
