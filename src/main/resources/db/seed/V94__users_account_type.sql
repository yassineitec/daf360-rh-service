-- =============================================================================
-- V94__users_account_type.sql
--
-- Adds [dbo].[Users].account_type so test logins, duplicated imports and service
-- accounts can be excluded from every list of PEOPLE in one place.
--
-- WHY NOT "must have an employee profile"
-- -----------------------------------------------------------------------------
-- That was the first idea and the data killed it. 155 active users have no
-- employee_profiles row, and nearly all of them are real: the DRH, the PDG, the
-- IT lead, two HR assistants, ~25 site managers, ~100 collaborators. Their HR
-- file is simply not filled in yet. Profile absence means "incomplete HR file",
-- never "not a person" — filtering on it would have removed the company's own
-- management from every picker.
--
-- The signal belongs to the ACCOUNT, so it lives here.
--
-- SAFE BY CONSTRUCTION: the column defaults to 'EMPLOYEE', so applying this
-- migration changes nothing. Behaviour changes only when rows are deliberately
-- reclassified — see dev_classify_ghost_users.sql, which proposes candidates and
-- leaves the UPDATE to a human.
--
-- Additive only: [dbo].[Users] is shared with the portal and the timesheet
-- application. Neither reads this column, so neither is affected.
-- =============================================================================

USE [DAF360_HR];

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'Users' AND COLUMN_NAME = 'account_type'
)
BEGIN
    ALTER TABLE [dbo].[Users]
        ADD [account_type] VARCHAR(20) NOT NULL
            CONSTRAINT [DF_Users_account_type] DEFAULT ('EMPLOYEE');
    PRINT 'Added: Users.account_type (default EMPLOYEE)';
END
ELSE PRINT 'Skipped: Users.account_type (already exists)';
GO

-- No CHECK constraint, on purpose and for the same reason as notifications.module:
-- an unknown value must degrade to "not listed as a person" rather than block an
-- INSERT into a table three applications write to. The vocabulary is enforced in
-- code (UserScope), where a bad value is a compile error rather than a 500.

-- Index only if the table is large enough to care. The filter is always combined
-- with role_id / pays_id predicates that are far more selective, so it earns its
-- place as an INCLUDE rather than a leading key — left out deliberately until a
-- plan shows it is needed.

-- ── Verify ───────────────────────────────────────────────────────────────────
-- Expect: every row EMPLOYEE immediately after this migration.
SELECT account_type, COUNT(*) AS nb
FROM [dbo].[Users]
GROUP BY account_type
ORDER BY nb DESC;

PRINT 'V94 complete. Nothing is excluded yet — run the classification next.';
