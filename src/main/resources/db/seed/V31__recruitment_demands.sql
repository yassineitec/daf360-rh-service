-- =============================================================================
-- V31: Demandes de recrutement (Expression de besoin)
--      1. Table recruitment_demands
--      2. FK column recruitment_demand_id on candidates
--      3. Seed URGENCY_LEVEL + EMPLOYMENT_TYPE configurable lists
--      4. Update CK_RolePermissions_Permission (+3 new perms → 53 total)
--      5. Seed RolePermissions for appropriate roles
-- =============================================================================
USE [DAF360_HR];
GO

-- ============================================================================
-- 1. recruitment_demands table
-- ============================================================================
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='recruitment_demands')
BEGIN
    CREATE TABLE [dbo].[recruitment_demands] (
        [id]                    BIGINT IDENTITY(1,1)  NOT NULL,

        [created_by_user_id]    BIGINT                NOT NULL,
        [pays_id]               BIGINT                NOT NULL,

        [job_title]             NVARCHAR(255)         NOT NULL,
        [department]            NVARCHAR(255)         NULL,
        [required_profile]      NVARCHAR(2000)        NOT NULL,
        [scope_of_work]         NVARCHAR(2000)        NOT NULL,
        [urgency_level_id]      BIGINT                NOT NULL,
        [employment_type_id]    BIGINT                NULL,
        [target_start_date]     DATE                  NULL,
        [headcount]             INT                   NOT NULL DEFAULT 1,
        [budget_range]          NVARCHAR(100)         NULL,
        [additional_notes]      NVARCHAR(1000)        NULL,

        [statut]                VARCHAR(30)           NOT NULL DEFAULT 'EN_ATTENTE',
        [submitted_at]          DATETIMEOFFSET(6)     NOT NULL
                                DEFAULT SYSDATETIMEOFFSET(),
        [reviewed_by_user_id]   BIGINT                NULL,
        [reviewed_at]           DATETIMEOFFSET(6)     NULL,
        [review_comment]        NVARCHAR(500)         NULL,

        [candidate_count]       INT                   NOT NULL DEFAULT 0,

        [created_at]            DATETIMEOFFSET(6)     NOT NULL
                                DEFAULT SYSDATETIMEOFFSET(),
        [updated_at]            DATETIMEOFFSET(6)     NULL,

        CONSTRAINT [PK_recruitment_demands]
            PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_RecDemand_CreatedBy]
            FOREIGN KEY ([created_by_user_id])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [FK_RecDemand_Pays]
            FOREIGN KEY ([pays_id])
            REFERENCES [dbo].[pays]([id]),
        CONSTRAINT [FK_RecDemand_UrgencyLevel]
            FOREIGN KEY ([urgency_level_id])
            REFERENCES [dbo].[configurable_list_values]([id]),
        CONSTRAINT [FK_RecDemand_EmploymentType]
            FOREIGN KEY ([employment_type_id])
            REFERENCES [dbo].[configurable_list_values]([id]),
        CONSTRAINT [FK_RecDemand_ReviewedBy]
            FOREIGN KEY ([reviewed_by_user_id])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [CK_RecDemand_Statut]
            CHECK ([statut] IN (
                'EN_ATTENTE','APPROUVEE','REJETEE','ANNULEE','CLOTUREE'))
    );

    CREATE NONCLUSTERED INDEX [IX_RecDemand_Statut]
        ON [dbo].[recruitment_demands]([statut]);
    CREATE NONCLUSTERED INDEX [IX_RecDemand_Pays]
        ON [dbo].[recruitment_demands]([pays_id]);
    CREATE NONCLUSTERED INDEX [IX_RecDemand_CreatedBy]
        ON [dbo].[recruitment_demands]([created_by_user_id]);
END
GO

-- ============================================================================
-- 2. Add FK column to candidates
-- ============================================================================
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='candidates' AND COLUMN_NAME='recruitment_demand_id')
BEGIN
    ALTER TABLE [dbo].[candidates]
        ADD [recruitment_demand_id] BIGINT NULL;

    ALTER TABLE [dbo].[candidates]
        ADD CONSTRAINT [FK_Candidate_RecDemand]
        FOREIGN KEY ([recruitment_demand_id])
        REFERENCES [dbo].[recruitment_demands]([id]);
END
GO

-- ============================================================================
-- 3a. Seed URGENCY_LEVEL list type
-- ============================================================================
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='URGENCY_LEVEL')
    INSERT INTO [dbo].[configurable_list_types]
        (code, label_fr, label_en, is_per_pays, is_system, created_at)
    VALUES ('URGENCY_LEVEL', N'Niveau d''urgence', 'Urgency level', 0, 0, GETDATE());
GO

DECLARE @ulId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='URGENCY_LEVEL');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@ulId AND value_code='FAIBLE')
    INSERT INTO [dbo].[configurable_list_values]
        (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
    VALUES (@ulId, 'FAIBLE', N'Faible', 'Low', 1, 1, 0, SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@ulId AND value_code='NORMAL')
    INSERT INTO [dbo].[configurable_list_values]
        (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
    VALUES (@ulId, 'NORMAL', N'Normal', 'Normal', 2, 1, 0, SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@ulId AND value_code='URGENT')
    INSERT INTO [dbo].[configurable_list_values]
        (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
    VALUES (@ulId, 'URGENT', N'Urgent', 'Urgent', 3, 1, 0, SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@ulId AND value_code='TRES_URGENT')
    INSERT INTO [dbo].[configurable_list_values]
        (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
    VALUES (@ulId, 'TRES_URGENT', N'Très urgent', 'Very urgent', 4, 1, 0, SYSDATETIMEOFFSET());
GO

-- ============================================================================
-- 3b. Seed EMPLOYMENT_TYPE list type
-- ============================================================================
IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_types] WHERE code='EMPLOYMENT_TYPE')
    INSERT INTO [dbo].[configurable_list_types]
        (code, label_fr, label_en, is_per_pays, is_system, created_at)
    VALUES ('EMPLOYMENT_TYPE', N'Type de contrat', 'Employment type', 0, 0, GETDATE());
GO

DECLARE @etId BIGINT = (SELECT id FROM [dbo].[configurable_list_types] WHERE code='EMPLOYMENT_TYPE');

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@etId AND value_code='CDI')
    INSERT INTO [dbo].[configurable_list_values]
        (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
    VALUES (@etId, 'CDI', N'CDI — Contrat à durée indéterminée', 'Permanent contract', 1, 1, 0, SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@etId AND value_code='CDD')
    INSERT INTO [dbo].[configurable_list_values]
        (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
    VALUES (@etId, 'CDD', N'CDD — Contrat à durée déterminée', 'Fixed-term contract', 2, 1, 0, SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@etId AND value_code='STAGE')
    INSERT INTO [dbo].[configurable_list_values]
        (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
    VALUES (@etId, 'STAGE', N'Stage', 'Internship', 3, 1, 0, SYSDATETIMEOFFSET());

IF NOT EXISTS (SELECT 1 FROM [dbo].[configurable_list_values] WHERE list_type_id=@etId AND value_code='FREELANCE')
    INSERT INTO [dbo].[configurable_list_values]
        (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
    VALUES (@etId, 'FREELANCE', N'Freelance / Consultant', 'Freelance', 4, 1, 0, SYSDATETIMEOFFSET());
GO

-- ============================================================================
-- 4. Update CK_RolePermissions_Permission (50 existing + 3 new = 53)
-- ============================================================================
IF EXISTS (SELECT 1 FROM sys.check_constraints
           WHERE name='CK_RolePermissions_Permission'
           AND parent_object_id=OBJECT_ID('dbo.RolePermissions'))
    ALTER TABLE [dbo].[RolePermissions] DROP CONSTRAINT CK_RolePermissions_Permission;
GO

ALTER TABLE [dbo].[RolePermissions]
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
            'ADMIN_ROLES','ADMIN_REGIMES','ADMIN_BREAKS',
            'RH_VIEW_RECRUITMENT_DEMAND',
            'RH_CREATE_RECRUITMENT_DEMAND',
            'RH_APPROVE_RECRUITMENT_DEMAND'
        )
    );
GO

-- ============================================================================
-- 5. Seed RolePermissions
-- ============================================================================

-- RH_VIEW_RECRUITMENT_DEMAND + RH_CREATE_RECRUITMENT_DEMAND
-- → Manager, Responsable Technique, Responsable RH, Admin, DRH
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, p.permission
FROM [dbo].[Roles] r
CROSS JOIN (VALUES
    ('RH_VIEW_RECRUITMENT_DEMAND'),
    ('RH_CREATE_RECRUITMENT_DEMAND')
) p(permission)
WHERE r.frenchName IN (
    'Manager', 'Responsable Technique', 'Responsable RH',
    'DRH', 'Administrateur'
)
AND (r.deleted IS NULL OR r.deleted = 0)
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id AND rp.permission = p.permission
);

-- RH_APPROVE_RECRUITMENT_DEMAND → Directeur, DRH, Administrateur
INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT r.id, 'RH_APPROVE_RECRUITMENT_DEMAND'
FROM [dbo].[Roles] r
WHERE r.frenchName IN ('Directeur', 'DRH', 'Administrateur')
AND (r.deleted IS NULL OR r.deleted = 0)
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[RolePermissions] rp
    WHERE rp.role_id = r.id
    AND rp.permission = 'RH_APPROVE_RECRUITMENT_DEMAND'
);
GO
