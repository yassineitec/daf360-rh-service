USE [DAF360_HR];
GO

-- break_legal_rules: entity-specific legal break deduction thresholds
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='break_legal_rules' AND TABLE_SCHEMA='dbo')
BEGIN
    CREATE TABLE [dbo].[break_legal_rules] (
        [id]                BIGINT IDENTITY(1,1) NOT NULL,
        [pays_id]           BIGINT               NOT NULL,
        [label_fr]          NVARCHAR(255)        NOT NULL,
        [label_en]          NVARCHAR(255)        NOT NULL,
        [min_work_hours]    NUMERIC(5,2)         NOT NULL,
        [max_work_hours]    NUMERIC(5,2)         NULL,
        [deduction_min]     INT                  NOT NULL,
        [applies_to_days]   VARCHAR(50)          NOT NULL DEFAULT 'ALL',
        [effective_from]    DATE                 NOT NULL,
        [effective_to]      DATE                 NULL,
        [is_active]         BIT                  NOT NULL DEFAULT 1,
        [created_at]        DATETIMEOFFSET       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_break_legal_rules] PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_BLR_Pays] FOREIGN KEY ([pays_id]) REFERENCES [dbo].[pays]([id])
    );
    CREATE NONCLUSTERED INDEX [IX_BLR_Pays] ON [dbo].[break_legal_rules]([pays_id]);
END
GO

-- break_templates: per-regime break configurations (with denormalized pays_id)
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='break_templates' AND TABLE_SCHEMA='dbo')
BEGIN
    CREATE TABLE [dbo].[break_templates] (
        [id]                    BIGINT IDENTITY(1,1) NOT NULL,
        [pays_id]               BIGINT               NOT NULL,
        [regime_id]             BIGINT               NOT NULL,
        [label_fr]              NVARCHAR(255)        NOT NULL,
        [label_en]              NVARCHAR(255)        NOT NULL,
        [deduction_type]        VARCHAR(20)          NOT NULL DEFAULT 'AUTO',
        [duration_min]          INT                  NOT NULL,
        [applies_to_days]       VARCHAR(50)          NOT NULL DEFAULT 'ALL',
        [min_work_hours_trigger] NUMERIC(5,2)        NULL,
        [sort_order]            INT                  NOT NULL DEFAULT 0,
        [is_active]             BIT                  NOT NULL DEFAULT 1,
        [created_at]            DATETIMEOFFSET       NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_break_templates] PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_BT_Pays]   FOREIGN KEY ([pays_id])   REFERENCES [dbo].[pays]([id]),
        CONSTRAINT [FK_BT_Regime] FOREIGN KEY ([regime_id]) REFERENCES [dbo].[working_time_regimes]([id]),
        CONSTRAINT [CK_BT_DeductionType] CHECK ([deduction_type] IN ('MANDATORY','AUTO','OPTIONAL'))
    );
    CREATE NONCLUSTERED INDEX [IX_BT_Pays]   ON [dbo].[break_templates]([pays_id]);
    CREATE NONCLUSTERED INDEX [IX_BT_Regime] ON [dbo].[break_templates]([regime_id]);
END
GO

-- Add ADMIN_BREAKS permission to CHECK constraint (extend existing 49 values to 50)
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name='CK_RolePermissions_Permission'
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
            'ADMIN_ROLES','ADMIN_REGIMES','ADMIN_BREAKS'
        )
    );
GO

-- Seed ADMIN_BREAKS for Administrateur + DRH + RH
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'ADMIN_BREAKS'
FROM [dbo].[Roles] r
WHERE r.frenchName IN ('Administrateur','Directeur des Ressources Humaines (DRH)','Ressources Humaines (RH)')
  AND (r.deleted = 0 OR r.deleted IS NULL)
  AND NOT EXISTS (SELECT 1 FROM [dbo].[RolePermissions] rp WHERE rp.role_id = r.id AND rp.permission = 'ADMIN_BREAKS');
GO
