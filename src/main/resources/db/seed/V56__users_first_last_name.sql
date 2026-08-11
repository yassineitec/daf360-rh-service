-- V56: Add first_name / last_name columns to Users table.
-- Backfill existing rows via it_provisionings which links user_id → candidate_id.
-- Splitting fullName is avoided because compound names would split incorrectly.

IF NOT EXISTS (
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_NAME = 'Users' AND COLUMN_NAME = 'first_name'
)
  ALTER TABLE [dbo].[Users] ADD [first_name] NVARCHAR(100) NULL;
GO

IF NOT EXISTS (
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_NAME = 'Users' AND COLUMN_NAME = 'last_name'
)
  ALTER TABLE [dbo].[Users] ADD [last_name] NVARCHAR(100) NULL;
GO

-- Backfill from candidates via it_provisionings join
UPDATE u
SET    u.[first_name] = c.[first_name],
       u.[last_name]  = c.[last_name]
FROM   [dbo].[Users] u
JOIN   [dbo].[it_provisionings] ip ON ip.[user_id]     = u.[id]
JOIN   [dbo].[candidates]       c  ON c.[id]           = ip.[candidate_id]
WHERE  u.[first_name] IS NULL;
GO
