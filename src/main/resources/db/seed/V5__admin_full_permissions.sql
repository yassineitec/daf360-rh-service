-- =============================================================================
-- V5__admin_full_permissions.sql
-- Grants ALL permissions to the Administrateur role.
-- Run AFTER V4. Safe to re-run — all inserts use IF NOT EXISTS guards.
-- =============================================================================

USE [DAF360_HR];

DECLARE @adminId BIGINT = (
    SELECT id FROM [dbo].[Roles]
    WHERE frenchName = 'Administrateur' AND deleted = 0
);

IF @adminId IS NULL BEGIN PRINT 'ERROR: Administrateur role not found.'; RETURN; END

PRINT 'Granting all permissions to Administrateur id=' + CAST(@adminId AS VARCHAR);

-- User management
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='CREATE_USER') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'CREATE_USER');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_USERS')    INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_USERS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='UPDATE_USER') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'UPDATE_USER');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='DELETE_USER') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'DELETE_USER');

-- Pays
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_PAYS')    INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_PAYS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='CREATE_PAYS') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'CREATE_PAYS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='UPDATE_PAYS') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'UPDATE_PAYS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='DELETE_PAYS') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'DELETE_PAYS');

-- Holidays
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_HOLIDAYS')    INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_HOLIDAYS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='CREATE_HOLIDAY')  INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'CREATE_HOLIDAY');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='UPDATE_HOLIDAY')  INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'UPDATE_HOLIDAY');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='DELETE_HOLIDAY')  INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'DELETE_HOLIDAY');

-- Roles & permissions
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_PERMISSIONS') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_PERMISSIONS');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_ROLES')       INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_ROLES');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='CREATE_ROLE')     INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'CREATE_ROLE');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='UPDATE_ROLE')     INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'UPDATE_ROLE');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='DELETE_ROLE')     INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'DELETE_ROLE');

-- Leaves
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_LEAVES')       INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_LEAVES');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='ADD_LEAVE')        INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'ADD_LEAVE');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='RESPONSE_LEAVE')   INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'RESPONSE_LEAVE');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_GLOBAL_LEAVES')INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_GLOBAL_LEAVES');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_CATEGORIES')   INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_CATEGORIES');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='CREATE_CATEGORY')  INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'CREATE_CATEGORY');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='UPDATE_CATEGORY')  INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'UPDATE_CATEGORY');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='DELETE_CATEGORY')  INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'DELETE_CATEGORY');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='SETTLE_LEAVES')    INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'SETTLE_LEAVES');

-- TSR
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_TSR')        INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_TSR');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='CREATE_TSR')     INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'CREATE_TSR');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='RESPOND_TSR')    INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'RESPOND_TSR');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='GET_GLOBAL_TSR') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'GET_GLOBAL_TSR');

-- Dashboard & events
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='VIEW_DASHBOARD') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'VIEW_DASHBOARD');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='MANAGE_EVENTS')  INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'MANAGE_EVENTS');

-- Employee profiles
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='HR_CREATE_PROFILE')  INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'HR_CREATE_PROFILE');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='HR_UPDATE_PROFILE')  INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'HR_UPDATE_PROFILE');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='HR_ARCHIVE_PROFILE') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'HR_ARCHIVE_PROFILE');

-- Recruitment
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='VIEW_CANDIDATES')        INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'VIEW_CANDIDATES');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='CREATE_CANDIDATE')       INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'CREATE_CANDIDATE');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='EDIT_CANDIDATE')         INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'EDIT_CANDIDATE');
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='ACCEPT_REJECT_CANDIDATE')INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'ACCEPT_REJECT_CANDIDATE');

-- IT Provisioning
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='IT_PROVISIONING') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'IT_PROVISIONING');

-- HR Onboarding
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='HR_ONBOARDING') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'HR_ONBOARDING');

-- Workflow & visibility
IF NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] WHERE role_id=@adminId AND permission='VIEW_WORKFLOW') INSERT INTO [dbo].[RolePermissions] (role_id,permission) VALUES (@adminId,'VIEW_WORKFLOW');

-- Verify result
SELECT r.frenchName, rp.permission
FROM [dbo].[RolePermissions] rp
JOIN [dbo].[Roles] r ON r.id = rp.role_id
WHERE r.id = @adminId
ORDER BY rp.permission;

PRINT 'Done.';
