-- =============================================================================
-- V32: Enhanced recruitment demand form
--      1. Add 8 new columns to recruitment_demands
--      2. Move employment_type_id from recruitment_demands → candidates
--      3. Seed CSP_CATEGORY, EXPERIENCE_LEVEL, EDUCATION_LEVEL list types
-- =============================================================================
USE [DAF360_HR];
GO

-- ============================================================================
-- 1. New columns on recruitment_demands
-- ============================================================================

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='recruitment_demands' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='recruitment_reason')
    ALTER TABLE [dbo].[recruitment_demands] ADD [recruitment_reason] VARCHAR(50) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='recruitment_demands' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='need_description')
    ALTER TABLE [dbo].[recruitment_demands] ADD [need_description] NVARCHAR(4000) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='recruitment_demands' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='job_exact_title')
    ALTER TABLE [dbo].[recruitment_demands] ADD [job_exact_title] NVARCHAR(255) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='recruitment_demands' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='csp_category_id')
    ALTER TABLE [dbo].[recruitment_demands] ADD [csp_category_id] BIGINT NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='recruitment_demands' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='experience_level_id')
    ALTER TABLE [dbo].[recruitment_demands] ADD [experience_level_id] BIGINT NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='recruitment_demands' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='education_level_id')
    ALTER TABLE [dbo].[recruitment_demands] ADD [education_level_id] BIGINT NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='recruitment_demands' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='technical_skills')
    ALTER TABLE [dbo].[recruitment_demands] ADD [technical_skills] NVARCHAR(2000) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='recruitment_demands' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='soft_skills')
    ALTER TABLE [dbo].[recruitment_demands] ADD [soft_skills] NVARCHAR(1000) NULL;
GO

-- CHECK constraint on recruitment_reason (3 fixed values)
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints
    WHERE parent_object_id = OBJECT_ID('dbo.recruitment_demands')
    AND name = 'CK_RecDemand_Reason')
    ALTER TABLE [dbo].[recruitment_demands]
        ADD CONSTRAINT [CK_RecDemand_Reason]
        CHECK ([recruitment_reason] IN ('CREATION_POSTE','REMPLACEMENT','ACCROISSEMENT'));
GO

-- FK for csp_category_id
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys
    WHERE parent_object_id = OBJECT_ID('dbo.recruitment_demands')
    AND name = 'FK_RecDemand_CSP')
    ALTER TABLE [dbo].[recruitment_demands]
        ADD CONSTRAINT [FK_RecDemand_CSP]
        FOREIGN KEY ([csp_category_id])
        REFERENCES [dbo].[configurable_list_values]([id]);
GO

-- FK for experience_level_id
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys
    WHERE parent_object_id = OBJECT_ID('dbo.recruitment_demands')
    AND name = 'FK_RecDemand_ExpLevel')
    ALTER TABLE [dbo].[recruitment_demands]
        ADD CONSTRAINT [FK_RecDemand_ExpLevel]
        FOREIGN KEY ([experience_level_id])
        REFERENCES [dbo].[configurable_list_values]([id]);
GO

-- FK for education_level_id
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys
    WHERE parent_object_id = OBJECT_ID('dbo.recruitment_demands')
    AND name = 'FK_RecDemand_EduLevel')
    ALTER TABLE [dbo].[recruitment_demands]
        ADD CONSTRAINT [FK_RecDemand_EduLevel]
        FOREIGN KEY ([education_level_id])
        REFERENCES [dbo].[configurable_list_values]([id]);
GO

-- ============================================================================
-- 2. Move employment_type_id from recruitment_demands → candidates
-- ============================================================================

-- 2a. Add employment_type_id to candidates (if missing)
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='candidates' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='employment_type_id')
BEGIN
    ALTER TABLE [dbo].[candidates] ADD [employment_type_id] BIGINT NULL;

    ALTER TABLE [dbo].[candidates]
        ADD CONSTRAINT [FK_Candidate_EmploymentType]
        FOREIGN KEY ([employment_type_id])
        REFERENCES [dbo].[configurable_list_values]([id]);
END
GO

-- 2b. Drop employment_type_id from recruitment_demands
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME='recruitment_demands' AND TABLE_SCHEMA='dbo'
    AND COLUMN_NAME='employment_type_id')
BEGIN
    -- Drop FK first
    IF OBJECT_ID('dbo.FK_RecDemand_EmploymentType', 'F') IS NOT NULL
        ALTER TABLE [dbo].[recruitment_demands]
            DROP CONSTRAINT [FK_RecDemand_EmploymentType];

    ALTER TABLE [dbo].[recruitment_demands]
        DROP COLUMN [employment_type_id];
END
GO

-- ============================================================================
-- 3. Seed new configurable list types
-- ============================================================================

INSERT INTO [dbo].[configurable_list_types] (code, label_fr, label_en, description, created_at)
SELECT v.code, v.lf, v.le, v.descr, SYSDATETIMEOFFSET()
FROM (VALUES
    ('CSP_CATEGORY',   N'Catégorie socio-professionnelle', N'Socio-professional category', N'Ouvrier, ETAM, Cadre'),
    ('EXPERIENCE_LEVEL', N'Niveau d''expérience',          N'Experience level',             N'Junior, Confirmé, Senior'),
    ('EDUCATION_LEVEL',  N'Diplôme minimum requis',        N'Minimum education level',      N'Bac, BTS/DUT, Licence, Master/Ingénieur')
) v(code, lf, le, descr)
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[configurable_list_types] t WHERE t.code = v.code
);
GO

-- Seed CSP_CATEGORY values
INSERT INTO [dbo].[configurable_list_values]
    (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
SELECT t.id, v.vc, v.lf, v.le, v.ord, 1, 0, SYSDATETIMEOFFSET()
FROM [dbo].[configurable_list_types] t
CROSS JOIN (VALUES
    ('OUVRIER', N'Ouvrier',  N'Blue-collar worker', 1),
    ('ETAM',    N'ETAM',     N'Technician / Supervisor', 2),
    ('CADRE',   N'Cadre',    N'Executive / Manager', 3)
) v(vc, lf, le, ord)
WHERE t.code = 'CSP_CATEGORY'
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[configurable_list_values] x
    WHERE x.list_type_id = t.id AND x.value_code = v.vc
);
GO

-- Seed EXPERIENCE_LEVEL values
INSERT INTO [dbo].[configurable_list_values]
    (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
SELECT t.id, v.vc, v.lf, v.le, v.ord, 1, 0, SYSDATETIMEOFFSET()
FROM [dbo].[configurable_list_types] t
CROSS JOIN (VALUES
    ('JUNIOR',   N'Junior (0-2 ans)',   N'Junior (0-2 yrs)',  1),
    ('CONFIRME', N'Confirmé (3-5 ans)', N'Mid-level (3-5 yrs)', 2),
    ('SENIOR',   N'Sénior (6-10 ans)', N'Senior (6-10 yrs)', 3),
    ('EXPERT',   N'Expert (+10 ans)',   N'Expert (10+ yrs)', 4)
) v(vc, lf, le, ord)
WHERE t.code = 'EXPERIENCE_LEVEL'
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[configurable_list_values] x
    WHERE x.list_type_id = t.id AND x.value_code = v.vc
);
GO

-- Seed EDUCATION_LEVEL values
INSERT INTO [dbo].[configurable_list_values]
    (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
SELECT t.id, v.vc, v.lf, v.le, v.ord, 1, 0, SYSDATETIMEOFFSET()
FROM [dbo].[configurable_list_types] t
CROSS JOIN (VALUES
    ('BAC',        N'Bac',                      N'High school diploma', 1),
    ('BTS_DUT',    N'BTS / DUT',                N'BTS / DUT', 2),
    ('LICENCE',    N'Licence (Bac+3)',           N'Bachelor', 3),
    ('MASTER_ING', N'Master / Ingénieur (Bac+5)', N'Master / Engineer', 4),
    ('DOCTORAT',   N'Doctorat',                 N'PhD', 5)
) v(vc, lf, le, ord)
WHERE t.code = 'EDUCATION_LEVEL'
AND NOT EXISTS (
    SELECT 1 FROM [dbo].[configurable_list_values] x
    WHERE x.list_type_id = t.id AND x.value_code = v.vc
);
GO
