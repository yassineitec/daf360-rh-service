-- =============================================================================
-- V20: Regime Management Database Setup
-- Applied manually 2026-06-07
-- Checks performed before execution — results documented in comments.
-- =============================================================================
USE [DAF360_HR];
GO

-- =============================================================================
-- BLOCK 1: Add missing columns to working_time_regimes
-- Existing: id, pays_id, code, label_fr, label_en, hours_per_week,
--           days_per_week, start_time, end_time, is_flexible,
--           is_default, is_active, created_at, updated_at (datetime2)
-- NOT adding updated_at (already exists as datetime2)
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='working_time_regimes' AND COLUMN_NAME='description_fr')
    ALTER TABLE [dbo].[working_time_regimes] ADD [description_fr] NVARCHAR(500) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='working_time_regimes' AND COLUMN_NAME='description_en')
    ALTER TABLE [dbo].[working_time_regimes] ADD [description_en] NVARCHAR(500) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='working_time_regimes' AND COLUMN_NAME='break_duration_min')
    ALTER TABLE [dbo].[working_time_regimes] ADD [break_duration_min] INT NULL DEFAULT 0;
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='working_time_regimes' AND COLUMN_NAME='overtime_allowed')
    ALTER TABLE [dbo].[working_time_regimes] ADD [overtime_allowed] BIT NOT NULL DEFAULT 0;
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='working_time_regimes' AND COLUMN_NAME='max_hours_per_day')
    ALTER TABLE [dbo].[working_time_regimes] ADD [max_hours_per_day] NUMERIC(4,1) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='working_time_regimes' AND COLUMN_NAME='updated_by')
    ALTER TABLE [dbo].[working_time_regimes] ADD [updated_by] BIGINT NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name='FK_WTR_UpdatedBy')
    ALTER TABLE [dbo].[working_time_regimes]
        ADD CONSTRAINT [FK_WTR_UpdatedBy]
        FOREIGN KEY ([updated_by]) REFERENCES [dbo].[Users]([id]);
GO

-- =============================================================================
-- BLOCK 2: Create regime_role_assignments (did not exist)
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES
               WHERE TABLE_NAME='regime_role_assignments' AND TABLE_SCHEMA='dbo')
BEGIN
    CREATE TABLE [dbo].[regime_role_assignments] (
        [id]             BIGINT IDENTITY(1,1)  NOT NULL,
        [regime_id]      BIGINT                NOT NULL,
        [role_id]        BIGINT                NOT NULL,
        [pays_id]        BIGINT                NOT NULL,
        [effective_from] DATE                  NOT NULL,
        [effective_to]   DATE                  NULL,
        [is_active]      BIT                   NOT NULL DEFAULT 1,
        [assigned_by]    BIGINT                NULL,
        [assigned_at]    DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [notes]          NVARCHAR(500)         NULL,

        CONSTRAINT [PK_regime_role_assignments]
            PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_RRA_Regime]
            FOREIGN KEY ([regime_id]) REFERENCES [dbo].[working_time_regimes]([id]),
        CONSTRAINT [FK_RRA_Role]
            FOREIGN KEY ([role_id])   REFERENCES [dbo].[Roles]([id]),
        CONSTRAINT [FK_RRA_Pays]
            FOREIGN KEY ([pays_id])   REFERENCES [dbo].[pays]([id]),
        CONSTRAINT [FK_RRA_AssignedBy]
            FOREIGN KEY ([assigned_by]) REFERENCES [dbo].[Users]([id])
    );

    CREATE NONCLUSTERED INDEX [IX_RRA_Role_Pays]
        ON [dbo].[regime_role_assignments]([role_id],[pays_id]);
    CREATE NONCLUSTERED INDEX [IX_RRA_Regime]
        ON [dbo].[regime_role_assignments]([regime_id]);
END
GO

-- =============================================================================
-- BLOCK 3: Create regime_assignment_history (did not exist)
-- =============================================================================
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES
               WHERE TABLE_NAME='regime_assignment_history' AND TABLE_SCHEMA='dbo')
BEGIN
    CREATE TABLE [dbo].[regime_assignment_history] (
        [id]               BIGINT IDENTITY(1,1)  NOT NULL,
        [assignment_level] VARCHAR(20)           NOT NULL,
        [target_id]        BIGINT                NOT NULL,
        [old_regime_id]    BIGINT                NULL,
        [new_regime_id]    BIGINT                NULL,
        [effective_from]   DATE                  NULL,
        [effective_to]     DATE                  NULL,
        [reason]           NVARCHAR(500)         NULL,
        [changed_by]       BIGINT                NOT NULL,
        [changed_at]       DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),

        CONSTRAINT [PK_regime_assignment_history]
            PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_RAH_OldRegime]
            FOREIGN KEY ([old_regime_id]) REFERENCES [dbo].[working_time_regimes]([id]),
        CONSTRAINT [FK_RAH_NewRegime]
            FOREIGN KEY ([new_regime_id]) REFERENCES [dbo].[working_time_regimes]([id]),
        CONSTRAINT [FK_RAH_ChangedBy]
            FOREIGN KEY ([changed_by])    REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [CK_RAH_Level]
            CHECK ([assignment_level] IN ('EMPLOYEE','ROLE','DEFAULT'))
    );

    CREATE NONCLUSTERED INDEX [IX_RAH_Target]
        ON [dbo].[regime_assignment_history]([assignment_level],[target_id]);
END
GO

-- =============================================================================
-- BLOCK 4: Add ADMIN_REGIMES to CK_RolePermissions_Permission constraint
-- Current constraint has 48 values. Adding ADMIN_REGIMES = 49 total.
-- =============================================================================
IF EXISTS (SELECT 1 FROM sys.check_constraints
           WHERE name='CK_RolePermissions_Permission'
           AND parent_object_id=OBJECT_ID('dbo.RolePermissions'))
    ALTER TABLE dbo.RolePermissions DROP CONSTRAINT CK_RolePermissions_Permission;
GO

ALTER TABLE dbo.RolePermissions
    ADD CONSTRAINT CK_RolePermissions_Permission CHECK (
        permission IN (
            'MANAGE_EVENTS','CREATE_USER','GET_USERS','UPDATE_USER','DELETE_USER',
            'GET_PAYS','CREATE_PAYS','UPDATE_PAYS','DELETE_PAYS',
            'GET_HOLIDAYS','CREATE_HOLIDAY','UPDATE_HOLIDAY','DELETE_HOLIDAY',
            'GET_PERMISSIONS','GET_ROLES','CREATE_ROLE','UPDATE_ROLE','DELETE_ROLE',
            'GET_LEAVES','ADD_LEAVE','RESPONSE_LEAVE','GET_GLOBAL_LEAVES',
            'GET_CATEGORIES','CREATE_CATEGORY','UPDATE_CATEGORY','DELETE_CATEGORY','SETTLE_LEAVES',
            'GET_TSR','CREATE_TSR','RESPOND_TSR','GET_GLOBAL_TSR',
            'VIEW_DASHBOARD','HR_CREATE_PROFILE','HR_UPDATE_PROFILE','HR_ARCHIVE_PROFILE',
            'VIEW_CANDIDATES','CREATE_CANDIDATE','EDIT_CANDIDATE','ACCEPT_REJECT_CANDIDATE',
            'IT_PROVISIONING','HR_ONBOARDING','VIEW_WORKFLOW','HR_ADMIN_ROLES',
            'VIEW_NOTIFICATIONS','ADMIN_EVENTS','ADMIN_LISTS','ADMIN_NOTIFICATIONS',
            'ADMIN_ROLES','ADMIN_REGIMES'
        )
    );
GO

-- =============================================================================
-- BLOCK 5: Seed ADMIN_REGIMES permission
-- Roles with HR management responsibility:
-- 13 = Administrateur, 17 = DRH, 24 = Ressources Humaines (RH)
-- =============================================================================
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'ADMIN_REGIMES'
FROM [dbo].[Roles] r
WHERE r.frenchName IN ('Administrateur','Directeur des Ressources Humaines (DRH)','Ressources Humaines (RH)')
  AND (r.deleted = 0 OR r.deleted IS NULL)
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp
      WHERE rp.role_id = r.id AND rp.permission = 'ADMIN_REGIMES'
  );
GO
