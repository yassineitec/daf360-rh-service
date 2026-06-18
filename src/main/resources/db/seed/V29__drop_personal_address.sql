-- personal_address was a duplicate of home_address (0 rows populated).
-- home_address is the canonical address field used throughout profile management.
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'personal_address'
)
    ALTER TABLE [dbo].[employee_profiles] DROP COLUMN [personal_address];
