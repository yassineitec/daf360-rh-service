-- =============================================================================
-- V43: PAYROLL_* permissions — grant all to Administrateur role
--
-- The payroll simulator requires PAYROLL_* authorities in the JWT.
-- These are issued by the portal based on RolePermissions rows in DAF360_HR.
-- Without them every payroll endpoint returns 403.
--
-- Safe to re-run — every INSERT is guarded with a NOT EXISTS check.
-- =============================================================================

USE [DAF360_HR];

DECLARE @adminId BIGINT = (
    SELECT id FROM [dbo].[Roles] WHERE frenchName = N'Administrateur' AND deleted = 0
);

IF @adminId IS NULL
BEGIN
    PRINT 'Administrateur role not found — aborting.';
    RETURN;
END

PRINT 'Granting PAYROLL_* permissions to Administrateur id=' + CAST(@adminId AS VARCHAR);

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_RUN_SIMULATION')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_RUN_SIMULATION');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_VIEW_INDIVIDUAL')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_VIEW_INDIVIDUAL');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_APPROVE_PARAMSET')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_APPROVE_PARAMSET');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_RUN_CALIBRATION')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_RUN_CALIBRATION');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_EXPORT_BUDGET')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_EXPORT_BUDGET');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_IMPORT_PARTNER')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_IMPORT_PARTNER');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_VIEW_AGGREGATE')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_VIEW_AGGREGATE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_APPROVE_PARAMSET_FAST_TRACK')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_APPROVE_PARAMSET_FAST_TRACK');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_VIEW_PARAMSET')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_VIEW_PARAMSET');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_UPLOAD_ACTUAL')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_UPLOAD_ACTUAL');

IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='PAYROLL_SUPER_ADMIN')
    INSERT INTO [dbo].[RolePermissions] (role_id, permission) VALUES (@adminId, 'PAYROLL_SUPER_ADMIN');

PRINT 'Done. ' + CAST(@@ROWCOUNT AS VARCHAR) + ' permission(s) inserted on last statement.';

-- ── Verification ─────────────────────────────────────────────────────────────
-- SELECT permission FROM [dbo].[RolePermissions]
-- WHERE role_id = @adminId AND permission LIKE 'PAYROLL_%'
-- ORDER BY permission;
