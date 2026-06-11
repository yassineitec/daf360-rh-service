USE [DAF360_HR];
GO

-- Drop existing check constraint on RolePermissions.permission
IF EXISTS (
    SELECT 1
    FROM sys.check_constraints
    WHERE name = 'CK_RolePermissions_Permission'
      AND parent_object_id = OBJECT_ID('dbo.RolePermissions')
)
BEGIN
    ALTER TABLE dbo.RolePermissions DROP CONSTRAINT CK_RolePermissions_Permission;
END
GO

-- Re-add constraint with 48 permissions (47 already in DB + ADMIN_ROLES)
ALTER TABLE dbo.RolePermissions
    ADD CONSTRAINT CK_RolePermissions_Permission CHECK (
        permission IN (
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
            'ADMIN_EVENTS',
            'ADMIN_LISTS',
            'ADMIN_NOTIFICATIONS',
            'ADMIN_ROLES'
        )
    );
GO

-- Assign ADMIN_ROLES to the Administrateur role (idempotent)
INSERT INTO dbo.RolePermissions (role_id, permission)
SELECT r.id, 'ADMIN_ROLES'
FROM dbo.Roles r
WHERE r.frenchName = 'Administrateur'
  AND NOT EXISTS (
      SELECT 1
      FROM dbo.RolePermissions rp
      WHERE rp.role_id = r.id
        AND rp.permission = 'ADMIN_ROLES'
  );
GO
