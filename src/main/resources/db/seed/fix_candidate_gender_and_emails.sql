-- ============================================================================
-- Add candidates.gender + backfill gender/email demo data.
-- One script, run once. GO separators are required because SQL Server cannot
-- reference the newly-added `gender` column in the same batch that adds it.
--   1. Add the gender column (idempotent — same guard as V40).
--   2. Backfill gender from the first name (canonical GENDER codes: MALE/FEMALE/
--      UNSPECIFIED — see GenderNormalizer / V9 seed).
--   3. Rebuild email_personal from first/last name across varied domains.
--      The id is appended so the emails stay unique (email_personal is a natural
--      key: CandidateService#existsByEmailPersonal). Drop the ".id" part only if
--      no unique constraint exists on the column.
-- ============================================================================

USE [DAF360_HR];
GO

-- 1) Column ---------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME = 'candidates' AND COLUMN_NAME = 'gender')
    ALTER TABLE [dbo].[candidates] ADD [gender] NVARCHAR(30) NULL;
GO

-- 2) Gender from first name ----------------------------------------------
UPDATE [dbo].[candidates]
SET gender =
    CASE LOWER(LTRIM(RTRIM(first_name)))
        -- Female first names present in the data
        WHEN 'nour'   THEN 'FEMALE'
        WHEN 'rania'  THEN 'FEMALE'
        WHEN 'ines'   THEN 'FEMALE'
        WHEN 'mariem' THEN 'FEMALE'
        WHEN 'sarra'  THEN 'FEMALE'
        -- Male first names present in the data
        WHEN 'khalil'        THEN 'MALE'
        WHEN 'ali yacine'    THEN 'MALE'
        WHEN 'aziz'          THEN 'MALE'
        WHEN 'yassine'       THEN 'MALE'
        WHEN 'mohamed amine' THEN 'MALE'
        WHEN 'skander'       THEN 'MALE'
        WHEN 'achref'        THEN 'MALE'
        WHEN 'hamza'         THEN 'MALE'
        -- Placeholder / ambiguous names ("test", …)
        ELSE 'UNSPECIFIED'
    END;
GO

-- 3) Email from names (unique via id, rotating domains) -------------------
UPDATE [dbo].[candidates]
SET email_personal =
    LOWER(REPLACE(REPLACE(LTRIM(RTRIM(first_name)), ' ', ''), '-', '')) + '.' +
    LOWER(REPLACE(REPLACE(LTRIM(RTRIM(last_name)),  ' ', ''), '-', '')) + '.' +
    CAST(id AS NVARCHAR(20)) + '@' +
    CASE (id % 4)
        WHEN 0 THEN 'gmail.com'
        WHEN 1 THEN 'hotmail.com'
        WHEN 2 THEN 'yahoo.com'
        ELSE        'outlook.com'
    END;
GO

PRINT 'candidates.gender added + gender/email backfilled.';
GO
