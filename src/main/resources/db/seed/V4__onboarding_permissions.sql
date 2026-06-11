-- =============================================================================
-- V4__onboarding_permissions.sql
-- Adds new permission codes for HR onboarding, recruitment, and IT provisioning
-- and assigns them to the appropriate roles.
-- =============================================================================

-- =============================================================================
-- STEP 1 — Rebuild the CHECK constraint to include new permission codes
-- =============================================================================

ALTER TABLE [dbo].[RolePermissions] DROP CONSTRAINT [CK_RolePermissions_Permission];

ALTER TABLE [dbo].[RolePermissions] ADD CONSTRAINT [CK_RolePermissions_Permission]
  CHECK (permission IN (
    -- Existing 32 permissions (preserved exactly)
    'MANAGE_EVENTS',
    'CREATE_USER',
    'GET_USERS',
    'UPDATE_USER',
    'DELETE_USER',
    'GET_PAYS',
    'CREATE_PAYS',
    'UPDATE_PAYS',
    'DELETE_PAYS',
    'GET_HOLIDAYS',
    'CREATE_HOLIDAY',
    'UPDATE_HOLIDAY',
    'DELETE_HOLIDAY',
    'GET_PERMISSIONS',
    'GET_ROLES',
    'CREATE_ROLE',
    'UPDATE_ROLE',
    'DELETE_ROLE',
    'GET_LEAVES',
    'ADD_LEAVE',
    'RESPONSE_LEAVE',
    'GET_GLOBAL_LEAVES',
    'GET_CATEGORIES',
    'CREATE_CATEGORY',
    'UPDATE_CATEGORY',
    'DELETE_CATEGORY',
    'SETTLE_LEAVES',
    'GET_TSR',
    'CREATE_TSR',
    'RESPOND_TSR',
    'GET_GLOBAL_TSR',
    'VIEW_DASHBOARD',
    -- New permissions added in V4
    'HR_CREATE_PROFILE',
    'HR_UPDATE_PROFILE',
    'HR_ARCHIVE_PROFILE',
    'VIEW_CANDIDATES',
    'CREATE_CANDIDATE',
    'EDIT_CANDIDATE',
    'ACCEPT_REJECT_CANDIDATE',
    'IT_PROVISIONING',
    'HR_ONBOARDING',
    'VIEW_WORKFLOW',
    'HR_ADMIN_ROLES',
    'VIEW_NOTIFICATIONS',
    'ADMIN_EVENTS'
  ));

-- =============================================================================
-- STEP 2 — Declare role ID variables using dynamic lookup by frenchName
-- This is resilient to future ID changes.
-- =============================================================================

DECLARE @adminId       BIGINT = (SELECT id FROM [dbo].[Roles] WHERE frenchName = 'Administrateur'                          AND deleted = 0);
DECLARE @hrOfficerId   BIGINT = (SELECT id FROM [dbo].[Roles] WHERE frenchName = 'Ressources Humaines (RH)'                AND deleted = 0);
DECLARE @hrdId         BIGINT = (SELECT id FROM [dbo].[Roles] WHERE frenchName = 'Directeur des Ressources Humaines (DRH)' AND deleted = 0);
DECLARE @itManagerId   BIGINT = (SELECT id FROM [dbo].[Roles] WHERE frenchName = 'Responsable IT'                         AND deleted = 0);
DECLARE @itEgyptId     BIGINT = (SELECT id FROM [dbo].[Roles] WHERE frenchName = 'Responsable IT (Egypt)'                 AND deleted = 0);

-- Talent Acquisition role does not exist in the current DB.
-- DECLARE @taId BIGINT = (SELECT id FROM [dbo].[Roles] WHERE frenchName LIKE '%Talent%' AND deleted = 0);
-- VIEW_CANDIDATES, CREATE_CANDIDATE, EDIT_CANDIDATE will be assigned to DRH and HR Officer
-- as closest equivalents until a dedicated Talent Acquisition role is created.

-- All active role IDs for VIEW_NOTIFICATIONS (18 roles)
-- IDs: 13, 14, 15, 16, 17, 18, 19, 20, 22, 23, 24, 25, 26, 27, 28, 29, 30, 10030

-- =============================================================================
-- STEP 3 — Idempotent inserts per permission group
-- =============================================================================

-- Helper macro pattern:
-- IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @x AND permission = 'Y')
--   INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@x, 'Y');

-- -------------------------------------------------------------------------
-- HR_CREATE_PROFILE → HR Officer (RH, id=24) and DRH (id=17)
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrOfficerId AND permission = 'HR_CREATE_PROFILE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrOfficerId, 'HR_CREATE_PROFILE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'HR_CREATE_PROFILE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'HR_CREATE_PROFILE');

-- -------------------------------------------------------------------------
-- HR_UPDATE_PROFILE → HR Officer (24) and DRH (17)
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrOfficerId AND permission = 'HR_UPDATE_PROFILE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrOfficerId, 'HR_UPDATE_PROFILE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'HR_UPDATE_PROFILE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'HR_UPDATE_PROFILE');

-- -------------------------------------------------------------------------
-- HR_ARCHIVE_PROFILE → HR Officer (24) and DRH (17)
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrOfficerId AND permission = 'HR_ARCHIVE_PROFILE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrOfficerId, 'HR_ARCHIVE_PROFILE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'HR_ARCHIVE_PROFILE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'HR_ARCHIVE_PROFILE');

-- -------------------------------------------------------------------------
-- HR_ONBOARDING → HR Officer (24) and DRH (17)
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrOfficerId AND permission = 'HR_ONBOARDING')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrOfficerId, 'HR_ONBOARDING');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'HR_ONBOARDING')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'HR_ONBOARDING');

-- -------------------------------------------------------------------------
-- VIEW_CANDIDATES → DRH (17) and HR Officer (24)
-- NOTE: No Talent Acquisition role exists; assigned to HR roles as closest match.
--       Create a dedicated Talent Acquisition role and reassign when ready.
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'VIEW_CANDIDATES')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'VIEW_CANDIDATES');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrOfficerId AND permission = 'VIEW_CANDIDATES')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrOfficerId, 'VIEW_CANDIDATES');

-- -------------------------------------------------------------------------
-- CREATE_CANDIDATE → DRH (17) and HR Officer (24)
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'CREATE_CANDIDATE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'CREATE_CANDIDATE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrOfficerId AND permission = 'CREATE_CANDIDATE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrOfficerId, 'CREATE_CANDIDATE');

-- -------------------------------------------------------------------------
-- EDIT_CANDIDATE → DRH (17) and HR Officer (24)
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'EDIT_CANDIDATE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'EDIT_CANDIDATE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrOfficerId AND permission = 'EDIT_CANDIDATE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrOfficerId, 'EDIT_CANDIDATE');

-- -------------------------------------------------------------------------
-- ACCEPT_REJECT_CANDIDATE → DRH (17) only (HR Manager equivalent)
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'ACCEPT_REJECT_CANDIDATE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'ACCEPT_REJECT_CANDIDATE');

-- -------------------------------------------------------------------------
-- IT_PROVISIONING → Responsable IT (23) and Responsable IT Egypt (10030)
-- -------------------------------------------------------------------------
IF @itManagerId IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @itManagerId AND permission = 'IT_PROVISIONING')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@itManagerId, 'IT_PROVISIONING');

IF @itEgyptId IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @itEgyptId AND permission = 'IT_PROVISIONING')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@itEgyptId, 'IT_PROVISIONING');

-- -------------------------------------------------------------------------
-- VIEW_WORKFLOW → HR Officer (24), DRH (17), Responsable IT (23), Responsable IT Egypt (10030)
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrOfficerId AND permission = 'VIEW_WORKFLOW')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrOfficerId, 'VIEW_WORKFLOW');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'VIEW_WORKFLOW')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'VIEW_WORKFLOW');

IF @itManagerId IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @itManagerId AND permission = 'VIEW_WORKFLOW')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@itManagerId, 'VIEW_WORKFLOW');

IF @itEgyptId IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @itEgyptId AND permission = 'VIEW_WORKFLOW')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@itEgyptId, 'VIEW_WORKFLOW');

-- -------------------------------------------------------------------------
-- HR_ADMIN_ROLES → Administrateur (13) only
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @adminId AND permission = 'HR_ADMIN_ROLES')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'HR_ADMIN_ROLES');

-- -------------------------------------------------------------------------
-- ADMIN_EVENTS → Administrateur (13) and DRH (17)
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @adminId AND permission = 'ADMIN_EVENTS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'ADMIN_EVENTS');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = @hrdId AND permission = 'ADMIN_EVENTS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@hrdId, 'ADMIN_EVENTS');

-- -------------------------------------------------------------------------
-- VIEW_NOTIFICATIONS → all 18 active roles
-- IDs: 13, 14, 15, 16, 17, 18, 19, 20, 22, 23, 24, 25, 26, 27, 28, 29, 30, 10030
-- -------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 13    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (13,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 14    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (14,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 15    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (15,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 16    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (16,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 17    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (17,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 18    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (18,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 19    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (19,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 20    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (20,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 22    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (22,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 23    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (23,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 24    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (24,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 25    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (25,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 26    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (26,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 27    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (27,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 28    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (28,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 29    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (29,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 30    AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (30,    'VIEW_NOTIFICATIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id = 10030 AND permission = 'VIEW_NOTIFICATIONS')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (10030, 'VIEW_NOTIFICATIONS');

-- =============================================================================
-- STEP 4 — Verification: show all newly inserted rows
-- =============================================================================

SELECT
    r.id          AS role_id,
    r.frenchName  AS role_name,
    rp.permission
FROM [dbo].[RolePermissions] rp
JOIN [dbo].[Roles] r ON r.id = rp.role_id
WHERE rp.permission IN (
    'HR_CREATE_PROFILE',
    'HR_UPDATE_PROFILE',
    'HR_ARCHIVE_PROFILE',
    'VIEW_CANDIDATES',
    'CREATE_CANDIDATE',
    'EDIT_CANDIDATE',
    'ACCEPT_REJECT_CANDIDATE',
    'IT_PROVISIONING',
    'HR_ONBOARDING',
    'VIEW_WORKFLOW',
    'HR_ADMIN_ROLES',
    'ADMIN_EVENTS',
    'VIEW_NOTIFICATIONS'
)
ORDER BY rp.permission, r.id;
