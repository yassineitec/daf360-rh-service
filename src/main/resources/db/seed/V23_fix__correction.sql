-- =============================================================================
-- V23_FIX: Correction script after V23 partial execution failure
-- Run ONCE in SSMS after V23 — fixes 3 issues:
--   1. it_asset_types was empty (INSERT failed due to DEFAULT 1 not applied)
--   2. it_assets backfill failed (asset_type_id was NULL)
--   3. laptop_provided/mouse_provided etc. could not be dropped (DEFAULT constraints)
-- =============================================================================
USE [DAF360_HR];
GO

-- =============================================================================
-- FIX 1: Re-seed it_asset_types with explicit is_active = 1
-- =============================================================================

IF NOT EXISTS (SELECT 1 FROM [dbo].[it_asset_types] WHERE code = 'LAPTOP')
    INSERT INTO [dbo].[it_asset_types] (code, label_fr, label_en, sort_order, is_active)
    VALUES ('LAPTOP', N'Ordinateur portable', N'Laptop', 1, 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[it_asset_types] WHERE code = 'MOUSE')
    INSERT INTO [dbo].[it_asset_types] (code, label_fr, label_en, sort_order, is_active)
    VALUES ('MOUSE', N'Souris', N'Mouse', 2, 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[it_asset_types] WHERE code = 'KEYBOARD')
    INSERT INTO [dbo].[it_asset_types] (code, label_fr, label_en, sort_order, is_active)
    VALUES ('KEYBOARD', N'Clavier', N'Keyboard', 3, 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[it_asset_types] WHERE code = 'SCREEN')
    INSERT INTO [dbo].[it_asset_types] (code, label_fr, label_en, sort_order, is_active)
    VALUES ('SCREEN', N'Écran', N'Screen', 4, 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[it_asset_types] WHERE code = 'HEADSET')
    INSERT INTO [dbo].[it_asset_types] (code, label_fr, label_en, sort_order, is_active)
    VALUES ('HEADSET', N'Casque audio', N'Headset', 5, 1);
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[it_asset_types] WHERE code = 'DOCKING_STATION')
    INSERT INTO [dbo].[it_asset_types] (code, label_fr, label_en, sort_order, is_active)
    VALUES ('DOCKING_STATION', N'Station d''accueil', N'Docking station', 6, 1);
GO

-- =============================================================================
-- FIX 2: Re-run it_assets backfill (only if laptop_provided column still exists)
-- =============================================================================

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'it_provisioning' AND COLUMN_NAME = 'laptop_provided')
BEGIN
    -- LAPTOP
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'LAPTOP'),
           ISNULL(ip.laptop_provided, 0),
           ip.laptop_sn,
           ip.laptop_brand,
           ip.laptop_asset_tag,
           ISNULL(ip.laptop_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'LAPTOP')
    );
END
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'it_provisioning' AND COLUMN_NAME = 'mouse_provided')
BEGIN
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'MOUSE'),
           ISNULL(ip.mouse_provided, 0),
           ip.mouse_sn, ip.mouse_brand, ip.mouse_asset_tag,
           ISNULL(ip.mouse_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'MOUSE')
    );
END
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'it_provisioning' AND COLUMN_NAME = 'keyboard_provided')
BEGIN
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'KEYBOARD'),
           ISNULL(ip.keyboard_provided, 0),
           ip.keyboard_sn, ip.keyboard_brand, ip.keyboard_asset_tag,
           ISNULL(ip.keyboard_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'KEYBOARD')
    );
END
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'it_provisioning' AND COLUMN_NAME = 'screen_provided')
BEGIN
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'SCREEN'),
           ISNULL(ip.screen_provided, 0),
           ip.screen_sn, ip.screen_brand, ip.screen_asset_tag,
           ISNULL(ip.screen_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'SCREEN')
    );
END
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'it_provisioning' AND COLUMN_NAME = 'headset_provided')
BEGIN
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'HEADSET'),
           ISNULL(ip.headset_provided, 0),
           ip.headset_sn, ip.headset_brand, ip.headset_asset_tag,
           ISNULL(ip.headset_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'HEADSET')
    );
END
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'it_provisioning' AND COLUMN_NAME = 'docking_station_provided')
BEGIN
    INSERT INTO [dbo].[it_assets] (provisioning_id, asset_type_id, provided, serial_number, brand_model, asset_tag, status)
    SELECT ip.id,
           (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'DOCKING_STATION'),
           ISNULL(ip.docking_station_provided, 0),
           ip.docking_station_sn, ip.docking_station_brand, ip.docking_station_asset_tag,
           ISNULL(ip.docking_station_status, 'BON_ETAT')
    FROM [dbo].[it_provisioning] ip
    WHERE NOT EXISTS (
        SELECT 1 FROM [dbo].[it_assets] ia
        WHERE ia.provisioning_id = ip.id
          AND ia.asset_type_id = (SELECT id FROM [dbo].[it_asset_types] WHERE code = 'DOCKING_STATION')
    );
END
GO

-- =============================================================================
-- FIX 3: Drop remaining hardware columns from it_provisioning
--         BIT columns (*_provided) have auto-generated DEFAULT constraints
--         that must be dropped first before the column can be dropped.
-- =============================================================================

DECLARE @tbl   NVARCHAR(128) = N'it_provisioning';
DECLARE @col   NVARCHAR(128);
DECLARE @cname NVARCHAR(256);
DECLARE @sql   NVARCHAR(500);

DECLARE col_cur CURSOR FOR
    SELECT c.COLUMN_NAME
    FROM INFORMATION_SCHEMA.COLUMNS c
    WHERE c.TABLE_NAME = @tbl
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

OPEN col_cur;
FETCH NEXT FROM col_cur INTO @col;

WHILE @@FETCH_STATUS = 0
BEGIN
    -- Step 1: drop any DEFAULT constraint on this column
    SELECT @cname = dc.name
    FROM sys.default_constraints dc
    JOIN sys.columns sc ON sc.object_id = dc.parent_object_id
                        AND sc.column_id = dc.parent_column_id
    JOIN sys.tables st ON st.object_id = dc.parent_object_id
    WHERE st.name = @tbl AND sc.name = @col;

    IF @cname IS NOT NULL
    BEGIN
        SET @sql = N'ALTER TABLE [dbo].[' + @tbl + N'] DROP CONSTRAINT [' + @cname + N']';
        EXEC sp_executesql @sql;
        SET @cname = NULL;
    END

    -- Step 2: drop the column itself
    SET @sql = N'ALTER TABLE [dbo].[' + @tbl + N'] DROP COLUMN [' + @col + N']';
    EXEC sp_executesql @sql;

    FETCH NEXT FROM col_cur INTO @col;
END

CLOSE col_cur;
DEALLOCATE col_cur;
GO

-- =============================================================================
-- VERIFICATION — run these SELECTs to confirm the fix applied correctly
-- =============================================================================

SELECT 'it_asset_types' AS tbl, COUNT(*) AS rows FROM [dbo].[it_asset_types]
UNION ALL
SELECT 'it_assets',      COUNT(*) FROM [dbo].[it_assets]
UNION ALL
SELECT 'grades',         COUNT(*) FROM [dbo].[grades]
UNION ALL
SELECT 'disciplines',    COUNT(*) FROM [dbo].[disciplines]
UNION ALL
SELECT 'departments',    COUNT(*) FROM [dbo].[departments]
UNION ALL
SELECT 'banks',          COUNT(*) FROM [dbo].[banks]
UNION ALL
SELECT 'nationalities',  COUNT(*) FROM [dbo].[nationalities];
GO

-- Confirm hardware columns are gone
SELECT COLUMN_NAME
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_NAME = 'it_provisioning'
  AND COLUMN_NAME LIKE '%_provided'
ORDER BY COLUMN_NAME;
-- Expected: 0 rows (all *_provided columns dropped)
GO
