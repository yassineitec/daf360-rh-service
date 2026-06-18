-- Users.roleId was a duplicate of Users.role_id (both FK to Roles.id, always in sync).
-- Users.manager_id was never populated (0/221 rows).
-- Both columns and their FK constraints are dropped.

IF EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'FKe7fqa58anirmw612mpkgtr485')
    ALTER TABLE [dbo].[Users] DROP CONSTRAINT [FKe7fqa58anirmw612mpkgtr485];

IF EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'FK_Users_Manager')
    ALTER TABLE [dbo].[Users] DROP CONSTRAINT [FK_Users_Manager];

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Users' AND COLUMN_NAME = 'roleId')
    ALTER TABLE [dbo].[Users] DROP COLUMN [roleId];

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'Users' AND COLUMN_NAME = 'manager_id')
    ALTER TABLE [dbo].[Users] DROP COLUMN [manager_id];
