-- V10__admin_lists_permission.sql
-- Drop and recreate CK_RolePermissions_Permission to include ADMIN_LISTS,
-- then assign ADMIN_LISTS to the Administrateur role.

-- Step 1: Drop existing CHECK constraint if it exists
ALTER TABLE RolePermissions
    DROP CONSTRAINT IF EXISTS CK_RolePermissions_Permission;

-- Step 2: Recreate the CHECK constraint with ADMIN_LISTS added
ALTER TABLE RolePermissions
    ADD CONSTRAINT CK_RolePermissions_Permission
        CHECK (permission IN (
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
            'ADMIN_LISTS'
        ));

-- Step 3: Assign ADMIN_LISTS to the Administrateur role (dynamic lookup)
INSERT INTO RolePermissions (role_id, permission)
SELECT r.id, 'ADMIN_LISTS'
FROM Roles r
WHERE r.frenchName = 'Administrateur' AND (r.deleted = 0 OR r.deleted IS NULL)
  AND NOT EXISTS (
      SELECT 1
      FROM RolePermissions rp
      WHERE rp.role_id = r.id
        AND rp.permission = 'ADMIN_LISTS'
  );
