-- Restore personal_address as the canonical address column,
-- migrating any data from home_address before dropping it.

-- 1. Re-add personal_address if V29 already removed it
IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'personal_address'
)
    ALTER TABLE [dbo].[employee_profiles] ADD [personal_address] nvarchar(500) NULL;

-- 2. Copy existing data from home_address into personal_address
UPDATE [dbo].[employee_profiles]
SET    [personal_address] = [home_address]
WHERE  [home_address] IS NOT NULL
  AND  ([personal_address] IS NULL OR [personal_address] = '');

-- 3. Drop home_address
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'home_address'
)
    ALTER TABLE [dbo].[employee_profiles] DROP COLUMN [home_address];
