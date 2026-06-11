-- =============================================================================
-- V23: Dimension tables (nationalities, grades, disciplines, nog_levels,
--      departments, banks) + it_assets normalisation
-- SQL Server syntax — fully idempotent
-- =============================================================================

-- ===========================================================================
-- BLOCK 1: Create dimension tables
-- ===========================================================================

-- 1.1 nationalities (global — no pays_id)
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='nationalities')
CREATE TABLE [dbo].[nationalities] (
    [id]       BIGINT IDENTITY(1,1) NOT NULL,
    [label_fr] NVARCHAR(100) NOT NULL,
    [label_en] NVARCHAR(100) NOT NULL,
    [iso_code] VARCHAR(5)   NULL,
    [is_active] BIT NOT NULL DEFAULT 1,
    CONSTRAINT [PK_nationalities] PRIMARY KEY CLUSTERED ([id] ASC),
    CONSTRAINT [UQ_nat_label_fr] UNIQUE ([label_fr])
);
GO

-- 1.2 grades
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='grades')
CREATE TABLE [dbo].[grades] (
    [id]         BIGINT IDENTITY(1,1) NOT NULL,
    [pays_id]    BIGINT NOT NULL,
    [code]       NVARCHAR(50)  NOT NULL,
    [label_fr]   NVARCHAR(100) NOT NULL,
    [label_en]   NVARCHAR(100) NOT NULL,
    [sort_order] INT NOT NULL DEFAULT 0,
    [is_active]  BIT NOT NULL DEFAULT 1,
    CONSTRAINT [PK_grades] PRIMARY KEY CLUSTERED ([id] ASC),
    CONSTRAINT [FK_grades_pays] FOREIGN KEY ([pays_id]) REFERENCES [dbo].[pays]([id]),
    CONSTRAINT [UQ_grades_pays_code] UNIQUE ([pays_id],[code])
);
GO

-- 1.3 disciplines
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='disciplines')
CREATE TABLE [dbo].[disciplines] (
    [id]         BIGINT IDENTITY(1,1) NOT NULL,
    [pays_id]    BIGINT NOT NULL,
    [code]       NVARCHAR(50)  NOT NULL,
    [label_fr]   NVARCHAR(100) NOT NULL,
    [label_en]   NVARCHAR(100) NOT NULL,
    [sort_order] INT NOT NULL DEFAULT 0,
    [is_active]  BIT NOT NULL DEFAULT 1,
    CONSTRAINT [PK_disciplines] PRIMARY KEY CLUSTERED ([id] ASC),
    CONSTRAINT [FK_disciplines_pays] FOREIGN KEY ([pays_id]) REFERENCES [dbo].[pays]([id]),
    CONSTRAINT [UQ_disciplines_pays_code] UNIQUE ([pays_id],[code])
);
GO

-- 1.4 nog_levels
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='nog_levels')
CREATE TABLE [dbo].[nog_levels] (
    [id]          BIGINT IDENTITY(1,1) NOT NULL,
    [pays_id]     BIGINT NOT NULL,
    [code]        NVARCHAR(20)  NOT NULL,
    [label_fr]    NVARCHAR(100) NOT NULL,
    [label_en]    NVARCHAR(100) NOT NULL,
    [level_order] INT NOT NULL DEFAULT 0,
    [is_active]   BIT NOT NULL DEFAULT 1,
    CONSTRAINT [PK_nog_levels] PRIMARY KEY CLUSTERED ([id] ASC),
    CONSTRAINT [FK_nog_levels_pays] FOREIGN KEY ([pays_id]) REFERENCES [dbo].[pays]([id]),
    CONSTRAINT [UQ_nog_levels_pays_code] UNIQUE ([pays_id],[code])
);
GO

-- 1.5 departments
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='departments')
CREATE TABLE [dbo].[departments] (
    [id]        BIGINT IDENTITY(1,1) NOT NULL,
    [pays_id]   BIGINT NOT NULL,
    [code]      NVARCHAR(50)  NOT NULL,
    [label_fr]  NVARCHAR(100) NOT NULL,
    [label_en]  NVARCHAR(100) NOT NULL,
    [parent_id] BIGINT NULL,
    [is_active] BIT NOT NULL DEFAULT 1,
    CONSTRAINT [PK_departments] PRIMARY KEY CLUSTERED ([id] ASC),
    CONSTRAINT [FK_departments_pays] FOREIGN KEY ([pays_id]) REFERENCES [dbo].[pays]([id]),
    CONSTRAINT [FK_departments_parent] FOREIGN KEY ([parent_id]) REFERENCES [dbo].[departments]([id]),
    CONSTRAINT [UQ_departments_pays_code] UNIQUE ([pays_id],[code])
);
GO

-- 1.6 banks
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='banks')
CREATE TABLE [dbo].[banks] (
    [id]         BIGINT IDENTITY(1,1) NOT NULL,
    [pays_id]    BIGINT NOT NULL,
    [code]       NVARCHAR(30)  NOT NULL,
    [label_fr]   NVARCHAR(100) NOT NULL,
    [label_en]   NVARCHAR(100) NOT NULL,
    [swift_code] VARCHAR(11)   NULL,
    [is_active]  BIT NOT NULL DEFAULT 1,
    CONSTRAINT [PK_banks] PRIMARY KEY CLUSTERED ([id] ASC),
    CONSTRAINT [FK_banks_pays] FOREIGN KEY ([pays_id]) REFERENCES [dbo].[pays]([id]),
    CONSTRAINT [UQ_banks_pays_code] UNIQUE ([pays_id],[code])
);
GO

-- ===========================================================================
-- BLOCK 2: Seed dimension tables from existing TEXT data
-- ===========================================================================

-- Seed nationalities from employee_profiles (explicit is_active=1 — DEFAULT not applied by INSERT...SELECT in SQL Server)
INSERT INTO [dbo].[nationalities] (label_fr, label_en, is_active)
SELECT DISTINCT LTRIM(RTRIM(nationality)), LTRIM(RTRIM(nationality)), 1
FROM [dbo].[employee_profiles]
WHERE nationality IS NOT NULL AND LTRIM(RTRIM(nationality)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] n WHERE n.label_fr = LTRIM(RTRIM(employee_profiles.nationality)));
GO

-- Seed nationalities from candidates
INSERT INTO [dbo].[nationalities] (label_fr, label_en, is_active)
SELECT DISTINCT LTRIM(RTRIM(nationality)), LTRIM(RTRIM(nationality)), 1
FROM [dbo].[candidates]
WHERE nationality IS NOT NULL AND LTRIM(RTRIM(nationality)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[nationalities] n WHERE n.label_fr = LTRIM(RTRIM(candidates.nationality)));
GO

-- Seed grades from employee_profiles
INSERT INTO [dbo].[grades] (pays_id, code, label_fr, label_en, sort_order, is_active)
SELECT DISTINCT ep.pays_id, LTRIM(RTRIM(ep.grade)), LTRIM(RTRIM(ep.grade)), LTRIM(RTRIM(ep.grade)),
       ROW_NUMBER() OVER (PARTITION BY ep.pays_id ORDER BY ep.grade), 1
FROM [dbo].[employee_profiles] ep
WHERE ep.grade IS NOT NULL AND LTRIM(RTRIM(ep.grade)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[grades] g WHERE g.pays_id = ep.pays_id AND g.label_fr = LTRIM(RTRIM(ep.grade)));
GO

-- Seed grades from candidates (applied_grade)
INSERT INTO [dbo].[grades] (pays_id, code, label_fr, label_en, sort_order, is_active)
SELECT DISTINCT c.pays_id, LTRIM(RTRIM(c.applied_grade)), LTRIM(RTRIM(c.applied_grade)), LTRIM(RTRIM(c.applied_grade)), 0, 1
FROM [dbo].[candidates] c
WHERE c.applied_grade IS NOT NULL AND LTRIM(RTRIM(c.applied_grade)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[grades] g WHERE g.pays_id = c.pays_id AND g.label_fr = LTRIM(RTRIM(c.applied_grade)));
GO

-- Seed disciplines from employee_profiles
INSERT INTO [dbo].[disciplines] (pays_id, code, label_fr, label_en, sort_order, is_active)
SELECT DISTINCT ep.pays_id, LTRIM(RTRIM(ep.discipline)), LTRIM(RTRIM(ep.discipline)), LTRIM(RTRIM(ep.discipline)),
       ROW_NUMBER() OVER (PARTITION BY ep.pays_id ORDER BY ep.discipline), 1
FROM [dbo].[employee_profiles] ep
WHERE ep.discipline IS NOT NULL AND LTRIM(RTRIM(ep.discipline)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[disciplines] d WHERE d.pays_id = ep.pays_id AND d.label_fr = LTRIM(RTRIM(ep.discipline)));
GO

-- Seed disciplines from candidates (applied_discipline)
INSERT INTO [dbo].[disciplines] (pays_id, code, label_fr, label_en, sort_order, is_active)
SELECT DISTINCT c.pays_id, LTRIM(RTRIM(c.applied_discipline)), LTRIM(RTRIM(c.applied_discipline)), LTRIM(RTRIM(c.applied_discipline)), 0, 1
FROM [dbo].[candidates] c
WHERE c.applied_discipline IS NOT NULL AND LTRIM(RTRIM(c.applied_discipline)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[disciplines] d WHERE d.pays_id = c.pays_id AND d.label_fr = LTRIM(RTRIM(c.applied_discipline)));
GO

-- Seed nog_levels from employee_profiles
INSERT INTO [dbo].[nog_levels] (pays_id, code, label_fr, label_en, level_order, is_active)
SELECT DISTINCT ep.pays_id, LTRIM(RTRIM(ep.nog_level)), LTRIM(RTRIM(ep.nog_level)), LTRIM(RTRIM(ep.nog_level)),
       ROW_NUMBER() OVER (PARTITION BY ep.pays_id ORDER BY ep.nog_level), 1
FROM [dbo].[employee_profiles] ep
WHERE ep.nog_level IS NOT NULL AND LTRIM(RTRIM(ep.nog_level)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[nog_levels] nl WHERE nl.pays_id = ep.pays_id AND nl.label_fr = LTRIM(RTRIM(ep.nog_level)));
GO

-- Seed departments from employee_profiles
INSERT INTO [dbo].[departments] (pays_id, code, label_fr, label_en, is_active)
SELECT DISTINCT ep.pays_id, LTRIM(RTRIM(ep.department)), LTRIM(RTRIM(ep.department)), LTRIM(RTRIM(ep.department)), 1
FROM [dbo].[employee_profiles] ep
WHERE ep.department IS NOT NULL AND LTRIM(RTRIM(ep.department)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[departments] d WHERE d.pays_id = ep.pays_id AND d.label_fr = LTRIM(RTRIM(ep.department)));
GO

-- Seed departments from candidates
INSERT INTO [dbo].[departments] (pays_id, code, label_fr, label_en, is_active)
SELECT DISTINCT c.pays_id, LTRIM(RTRIM(c.department)), LTRIM(RTRIM(c.department)), LTRIM(RTRIM(c.department)), 1
FROM [dbo].[candidates] c
WHERE c.department IS NOT NULL AND LTRIM(RTRIM(c.department)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[departments] d WHERE d.pays_id = c.pays_id AND d.label_fr = LTRIM(RTRIM(c.department)));
GO

-- Seed banks from employee_profiles
INSERT INTO [dbo].[banks] (pays_id, code, label_fr, label_en, is_active)
SELECT DISTINCT ep.pays_id, LTRIM(RTRIM(ep.bank_name)), LTRIM(RTRIM(ep.bank_name)), LTRIM(RTRIM(ep.bank_name)), 1
FROM [dbo].[employee_profiles] ep
WHERE ep.bank_name IS NOT NULL AND LTRIM(RTRIM(ep.bank_name)) != ''
AND NOT EXISTS (SELECT 1 FROM [dbo].[banks] b WHERE b.pays_id = ep.pays_id AND b.label_fr = LTRIM(RTRIM(ep.bank_name)));
GO

-- ===========================================================================
-- BLOCK 3: Add FK columns to employee_profiles
-- ===========================================================================

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='nationality_id')
    ALTER TABLE [dbo].[employee_profiles] ADD [nationality_id] BIGINT NULL CONSTRAINT [FK_ep_nationality] REFERENCES [dbo].[nationalities]([id]);
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='grade_id')
    ALTER TABLE [dbo].[employee_profiles] ADD [grade_id] BIGINT NULL CONSTRAINT [FK_ep_grade] REFERENCES [dbo].[grades]([id]);
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='discipline_id')
    ALTER TABLE [dbo].[employee_profiles] ADD [discipline_id] BIGINT NULL CONSTRAINT [FK_ep_discipline] REFERENCES [dbo].[disciplines]([id]);
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='nog_level_id')
    ALTER TABLE [dbo].[employee_profiles] ADD [nog_level_id] BIGINT NULL CONSTRAINT [FK_ep_nog_level] REFERENCES [dbo].[nog_levels]([id]);
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='department_id')
    ALTER TABLE [dbo].[employee_profiles] ADD [department_id] BIGINT NULL CONSTRAINT [FK_ep_department] REFERENCES [dbo].[departments]([id]);
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='bank_id')
    ALTER TABLE [dbo].[employee_profiles] ADD [bank_id] BIGINT NULL CONSTRAINT [FK_ep_bank] REFERENCES [dbo].[banks]([id]);
GO

-- ===========================================================================
-- BLOCK 4: Backfill FK columns in employee_profiles
-- ===========================================================================

UPDATE ep SET ep.nationality_id = n.id
FROM [dbo].[employee_profiles] ep
JOIN [dbo].[nationalities] n ON n.label_fr = LTRIM(RTRIM(ep.nationality))
WHERE ep.nationality IS NOT NULL AND ep.nationality_id IS NULL;
GO

UPDATE ep SET ep.grade_id = g.id
FROM [dbo].[employee_profiles] ep
JOIN [dbo].[grades] g ON g.pays_id = ep.pays_id AND g.label_fr = LTRIM(RTRIM(ep.grade))
WHERE ep.grade IS NOT NULL AND ep.grade_id IS NULL;
GO

UPDATE ep SET ep.discipline_id = d.id
FROM [dbo].[employee_profiles] ep
JOIN [dbo].[disciplines] d ON d.pays_id = ep.pays_id AND d.label_fr = LTRIM(RTRIM(ep.discipline))
WHERE ep.discipline IS NOT NULL AND ep.discipline_id IS NULL;
GO

UPDATE ep SET ep.nog_level_id = nl.id
FROM [dbo].[employee_profiles] ep
JOIN [dbo].[nog_levels] nl ON nl.pays_id = ep.pays_id AND nl.label_fr = LTRIM(RTRIM(ep.nog_level))
WHERE ep.nog_level IS NOT NULL AND ep.nog_level_id IS NULL;
GO

UPDATE ep SET ep.department_id = d.id
FROM [dbo].[employee_profiles] ep
JOIN [dbo].[departments] d ON d.pays_id = ep.pays_id AND d.label_fr = LTRIM(RTRIM(ep.department))
WHERE ep.department IS NOT NULL AND ep.department_id IS NULL;
GO

UPDATE ep SET ep.bank_id = b.id
FROM [dbo].[employee_profiles] ep
JOIN [dbo].[banks] b ON b.pays_id = ep.pays_id AND b.label_fr = LTRIM(RTRIM(ep.bank_name))
WHERE ep.bank_name IS NOT NULL AND ep.bank_id IS NULL;
GO

-- ===========================================================================
-- BLOCK 5: Add FK columns to candidates
-- ===========================================================================

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='candidates' AND COLUMN_NAME='nationality_id')
    ALTER TABLE [dbo].[candidates] ADD [nationality_id] BIGINT NULL CONSTRAINT [FK_cand_nationality] REFERENCES [dbo].[nationalities]([id]);
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='candidates' AND COLUMN_NAME='applied_grade_id')
    ALTER TABLE [dbo].[candidates] ADD [applied_grade_id] BIGINT NULL CONSTRAINT [FK_cand_grade] REFERENCES [dbo].[grades]([id]);
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='candidates' AND COLUMN_NAME='applied_discipline_id')
    ALTER TABLE [dbo].[candidates] ADD [applied_discipline_id] BIGINT NULL CONSTRAINT [FK_cand_discipline] REFERENCES [dbo].[disciplines]([id]);
GO
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='candidates' AND COLUMN_NAME='department_id')
    ALTER TABLE [dbo].[candidates] ADD [department_id] BIGINT NULL CONSTRAINT [FK_cand_department] REFERENCES [dbo].[departments]([id]);
GO

-- ===========================================================================
-- BLOCK 6: Backfill FK columns in candidates
-- ===========================================================================

UPDATE c SET c.nationality_id = n.id
FROM [dbo].[candidates] c
JOIN [dbo].[nationalities] n ON n.label_fr = LTRIM(RTRIM(c.nationality))
WHERE c.nationality IS NOT NULL AND c.nationality_id IS NULL;
GO

UPDATE c SET c.applied_grade_id = g.id
FROM [dbo].[candidates] c
JOIN [dbo].[grades] g ON g.pays_id = c.pays_id AND g.label_fr = LTRIM(RTRIM(c.applied_grade))
WHERE c.applied_grade IS NOT NULL AND c.applied_grade_id IS NULL;
GO

UPDATE c SET c.applied_discipline_id = d.id
FROM [dbo].[candidates] c
JOIN [dbo].[disciplines] d ON d.pays_id = c.pays_id AND d.label_fr = LTRIM(RTRIM(c.applied_discipline))
WHERE c.applied_discipline IS NOT NULL AND c.applied_discipline_id IS NULL;
GO

UPDATE c SET c.department_id = dept.id
FROM [dbo].[candidates] c
JOIN [dbo].[departments] dept ON dept.pays_id = c.pays_id AND dept.label_fr = LTRIM(RTRIM(c.department))
WHERE c.department IS NOT NULL AND c.department_id IS NULL;
GO

-- ===========================================================================
-- BLOCK 7: Drop old text columns from employee_profiles
--          (only after backfill is complete)
-- ===========================================================================

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='nationality')
    ALTER TABLE [dbo].[employee_profiles] DROP COLUMN [nationality];
GO
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='grade')
    ALTER TABLE [dbo].[employee_profiles] DROP COLUMN [grade];
GO
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='discipline')
    ALTER TABLE [dbo].[employee_profiles] DROP COLUMN [discipline];
GO
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='nog_level')
    ALTER TABLE [dbo].[employee_profiles] DROP COLUMN [nog_level];
GO
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='department')
    ALTER TABLE [dbo].[employee_profiles] DROP COLUMN [department];
GO
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='employee_profiles' AND COLUMN_NAME='bank_name')
    ALTER TABLE [dbo].[employee_profiles] DROP COLUMN [bank_name];
GO

-- ===========================================================================
-- BLOCK 8: Drop old text columns from candidates
-- ===========================================================================

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='candidates' AND COLUMN_NAME='nationality')
    ALTER TABLE [dbo].[candidates] DROP COLUMN [nationality];
GO
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='candidates' AND COLUMN_NAME='applied_grade')
    ALTER TABLE [dbo].[candidates] DROP COLUMN [applied_grade];
GO
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='candidates' AND COLUMN_NAME='applied_discipline')
    ALTER TABLE [dbo].[candidates] DROP COLUMN [applied_discipline];
GO
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='candidates' AND COLUMN_NAME='department')
    ALTER TABLE [dbo].[candidates] DROP COLUMN [department];
GO

-- ===========================================================================
-- BLOCK 9: Create it_asset_types
-- ===========================================================================

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='it_asset_types')
BEGIN
    CREATE TABLE [dbo].[it_asset_types] (
        [id]         BIGINT IDENTITY(1,1) NOT NULL,
        [code]       VARCHAR(30)   NOT NULL,
        [label_fr]   NVARCHAR(100) NOT NULL,
        [label_en]   NVARCHAR(100) NOT NULL,
        [sort_order] INT NOT NULL DEFAULT 0,
        [is_active]  BIT NOT NULL DEFAULT 1,
        CONSTRAINT [PK_it_asset_types] PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [UQ_it_asset_types_code] UNIQUE ([code])
    );

    INSERT INTO [dbo].[it_asset_types] (code, label_fr, label_en, sort_order, is_active) VALUES
        ('LAPTOP',           N'Ordinateur portable', N'Laptop',         1, 1),
        ('MOUSE',            N'Souris',              N'Mouse',          2, 1),
        ('KEYBOARD',         N'Clavier',             N'Keyboard',       3, 1),
        ('SCREEN',           N'Écran',               N'Screen',         4, 1),
        ('HEADSET',          N'Casque audio',        N'Headset',        5, 1),
        ('DOCKING_STATION',  N'Station d''accueil',  N'Docking station',6, 1);
END
GO

-- ===========================================================================
-- BLOCK 10: Create it_assets
-- ===========================================================================

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='it_assets')
BEGIN
    CREATE TABLE [dbo].[it_assets] (
        [id]               BIGINT IDENTITY(1,1) NOT NULL,
        [provisioning_id]  BIGINT NOT NULL,
        [asset_type_id]    BIGINT NOT NULL,
        [provided]         BIT    NOT NULL DEFAULT 0,
        [serial_number]    NVARCHAR(100) NULL,
        [brand_model]      NVARCHAR(150) NULL,
        [asset_tag]        NVARCHAR(100) NULL,
        [status]           NVARCHAR(50)  NOT NULL DEFAULT 'BON_ETAT',
        CONSTRAINT [PK_it_assets] PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_it_assets_prov] FOREIGN KEY ([provisioning_id]) REFERENCES [dbo].[it_provisioning]([id]),
        CONSTRAINT [FK_it_assets_type] FOREIGN KEY ([asset_type_id])   REFERENCES [dbo].[it_asset_types]([id]),
        CONSTRAINT [UQ_it_assets_prov_type] UNIQUE ([provisioning_id],[asset_type_id]),
        CONSTRAINT [CK_it_asset_status] CHECK ([status] IN ('NEUF','BON_ETAT','USAGE','EN_REPARATION','DEFECTUEUX'))
    );

    CREATE NONCLUSTERED INDEX [IX_it_assets_prov] ON [dbo].[it_assets]([provisioning_id]);
END
GO

-- ===========================================================================
-- BLOCK 11: Backfill it_assets from it_provisioning old columns
--           (only if the old columns still exist)
-- ===========================================================================

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='it_provisioning' AND COLUMN_NAME='laptop_provided')
BEGIN
    -- LAPTOP
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code='LAPTOP'),
           ISNULL(ip.laptop_provided, 0),
           ip.laptop_sn, ip.laptop_brand, ip.laptop_asset_tag,
           ISNULL(ip.laptop_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code='LAPTOP')
    );

    -- MOUSE
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code='MOUSE'),
           ISNULL(ip.mouse_provided, 0),
           ip.mouse_sn, ip.mouse_brand, ip.mouse_asset_tag,
           ISNULL(ip.mouse_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code='MOUSE')
    );

    -- KEYBOARD
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code='KEYBOARD'),
           ISNULL(ip.keyboard_provided, 0),
           ip.keyboard_sn, ip.keyboard_brand, ip.keyboard_asset_tag,
           ISNULL(ip.keyboard_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code='KEYBOARD')
    );

    -- SCREEN
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code='SCREEN'),
           ISNULL(ip.screen_provided, 0),
           ip.screen_sn, ip.screen_brand, ip.screen_asset_tag,
           ISNULL(ip.screen_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code='SCREEN')
    );

    -- HEADSET
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code='HEADSET'),
           ISNULL(ip.headset_provided, 0),
           ip.headset_sn, ip.headset_brand, ip.headset_asset_tag,
           ISNULL(ip.headset_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code='HEADSET')
    );

    -- DOCKING_STATION
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code='DOCKING_STATION'),
           ISNULL(ip.docking_station_provided, 0),
           ip.docking_station_sn, ip.docking_station_brand, ip.docking_station_asset_tag,
           ISNULL(ip.docking_station_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code='DOCKING_STATION')
    );
END
GO

-- ===========================================================================
-- BLOCK 12: Drop old hardware columns from it_provisioning
--           BIT columns (*_provided) have auto-generated DEFAULT constraints
--           → must drop the DEFAULT constraint BEFORE dropping the column.
-- ===========================================================================

DECLARE @hw_tbl   NVARCHAR(128) = N'it_provisioning';
DECLARE @hw_col   NVARCHAR(128);
DECLARE @hw_cname NVARCHAR(256);
DECLARE @hw_sql   NVARCHAR(500);

DECLARE hw_cur CURSOR FOR
    SELECT c.COLUMN_NAME
    FROM INFORMATION_SCHEMA.COLUMNS c
    WHERE c.TABLE_NAME   = @hw_tbl
      AND c.TABLE_SCHEMA = 'dbo'
      AND c.COLUMN_NAME IN (
          'laptop_provided',   'laptop_sn',   'laptop_brand',   'laptop_asset_tag',   'laptop_status',
          'mouse_provided',    'mouse_sn',    'mouse_brand',    'mouse_asset_tag',    'mouse_status',
          'keyboard_provided', 'keyboard_sn', 'keyboard_brand', 'keyboard_asset_tag', 'keyboard_status',
          'screen_provided',   'screen_sn',   'screen_brand',   'screen_asset_tag',   'screen_status',
          'headset_provided',  'headset_sn',  'headset_brand',  'headset_asset_tag',  'headset_status',
          'docking_station_provided', 'docking_station_sn', 'docking_station_brand',
          'docking_station_asset_tag', 'docking_station_status'
      );

OPEN hw_cur;
FETCH NEXT FROM hw_cur INTO @hw_col;

WHILE @@FETCH_STATUS = 0
BEGIN
    -- Step 1: find and drop any DEFAULT constraint on this column
    SELECT @hw_cname = dc.name
    FROM sys.default_constraints dc
    JOIN sys.columns sc ON sc.object_id = dc.parent_object_id
                        AND sc.column_id = dc.parent_column_id
    JOIN sys.tables st ON st.object_id = dc.parent_object_id
    WHERE st.name = @hw_tbl AND sc.name = @hw_col;

    IF @hw_cname IS NOT NULL
    BEGIN
        SET @hw_sql = N'ALTER TABLE [dbo].[' + @hw_tbl + N'] DROP CONSTRAINT [' + @hw_cname + N']';
        EXEC sp_executesql @hw_sql;
        SET @hw_cname = NULL;
    END

    -- Step 2: drop the column
    SET @hw_sql = N'ALTER TABLE [dbo].[' + @hw_tbl + N'] DROP COLUMN [' + @hw_col + N']';
    EXEC sp_executesql @hw_sql;

    FETCH NEXT FROM hw_cur INTO @hw_col;
END

CLOSE hw_cur;
DEALLOCATE hw_cur;
GO
