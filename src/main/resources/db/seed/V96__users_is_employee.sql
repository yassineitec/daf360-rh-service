-- =============================================================================
-- V96__users_is_employee.sql
--
-- Replaces [dbo].[Users].account_type (V94) with a binary [is_employee] flag.
--
-- WHY THE THREE-VALUE VOCABULARY GOES AWAY
-- -----------------------------------------------------------------------------
-- V94 introduced EMPLOYEE | TEST | SERVICE. The third value earned its place only
-- through one consequence: because SERVICE meant "not a person but must keep
-- working", the replica feed consumed by finance and payroll had to filter
-- differently from the pickers (`<> 'TEST'` there, `= 'EMPLOYEE'` here). That
-- asymmetry was the only thing the extra value bought, and it is a liability:
-- two predicates that must never be confused, for a distinction nothing else in
-- the application reads.
--
-- A single question is enough — IS THIS A PERSON? — and the replica no longer
-- needs an opinion: /api/hr/users-for-sync goes back to mirroring every active
-- account, because it is a technical mirror of accounts, not a list of people.
-- Only lists of PEOPLE filter, and they filter on one boolean.
--
-- Migration is lossless in the direction that matters: EMPLOYEE → 1, anything
-- else → 0. Since V94 left every row EMPLOYEE, every row becomes 1 and nothing
-- changes behaviourally.
-- =============================================================================

USE [DAF360_HR];

-- ── 1. New binary column ─────────────────────────────────────────────────────
IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'Users' AND COLUMN_NAME = 'is_employee'
)
BEGIN
    ALTER TABLE [dbo].[Users]
        ADD [is_employee] BIT NOT NULL
            CONSTRAINT [DF_Users_is_employee] DEFAULT (1);
    PRINT 'Added: Users.is_employee (default 1)';
END
ELSE PRINT 'Skipped: Users.is_employee (already exists)';
GO

-- ── 2. Carry over anything V94 had already classified ────────────────────────
IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'Users' AND COLUMN_NAME = 'account_type'
)
BEGIN
    UPDATE [dbo].[Users]
       SET [is_employee] = CASE WHEN [account_type] = 'EMPLOYEE' THEN 1 ELSE 0 END;
    PRINT 'Carried account_type over to is_employee';
END
GO

-- ── 3. Drop account_type, default constraint first ───────────────────────────
-- A DEFAULT is a named object on the column; dropping the column while it exists
-- fails. The name is looked up rather than assumed, because V94's explicit name
-- only applies where V94 created it — an environment that got the column another
-- way will carry an auto-generated DF__Users__account__XXXX.
DECLARE @df SYSNAME;
SELECT @df = dc.name
FROM sys.default_constraints dc
JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id
WHERE dc.parent_object_id = OBJECT_ID('dbo.Users') AND c.name = 'account_type';

IF @df IS NOT NULL
BEGIN
    EXEC('ALTER TABLE [dbo].[Users] DROP CONSTRAINT [' + @df + ']');
    PRINT 'Dropped default constraint on account_type';
END

IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'Users' AND COLUMN_NAME = 'account_type'
)
BEGIN
    ALTER TABLE [dbo].[Users] DROP COLUMN [account_type];
    PRINT 'Dropped: Users.account_type';
END
ELSE PRINT 'Skipped: account_type (already gone)';
GO

-- ── Verify ───────────────────────────────────────────────────────────────────
-- Expect employees = total right after this migration, since V94 left every row
-- EMPLOYEE. Non-employees appear only once someone reclassifies them, from
-- Administration → Utilisateurs.
SELECT COUNT(*)                                              AS total,
       SUM(CASE WHEN is_employee = 1 THEN 1 ELSE 0 END)      AS employees,
       SUM(CASE WHEN is_employee = 0 THEN 1 ELSE 0 END)      AS not_employees
FROM [dbo].[Users];

PRINT 'V96 complete.';
