-- =============================================================================
-- V95__users_last_login.sql
--
-- Adds [dbo].[Users].last_login_at, for the new "Utilisateurs" admin screen.
--
-- WHY A NEW COLUMN RATHER THAN A DERIVED VALUE
-- -----------------------------------------------------------------------------
-- The table already carries `refresh_token` and `token_expires_at`, which look
-- like they could answer "when did this person last sign in". They cannot:
-- AzureOAuth2SuccessHandler sets `refresh_token` at login and NEVER writes
-- `token_expires_at`, so that column holds nothing usable. Any screen deriving a
-- last-login from it would be inventing data.
--
-- Nothing else can fill this in either: rh-service does not handle sign-in. The
-- portal owns the login flow and is the only writer — see
-- AzureOAuth2SuccessHandler.
--
-- Consequence to expect: every row is NULL until people start logging in again.
-- The screen shows « jamais » rather than a fabricated date, which is the honest
-- answer for an account created but never used — precisely the ghost accounts the
-- screen exists to surface.
-- =============================================================================

USE [DAF360_HR];

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'Users' AND COLUMN_NAME = 'last_login_at'
)
BEGIN
    ALTER TABLE [dbo].[Users] ADD [last_login_at] DATETIMEOFFSET(6) NULL;
    PRINT 'Added: Users.last_login_at';
END
ELSE PRINT 'Skipped: Users.last_login_at (already exists)';
GO

-- ── Verify ───────────────────────────────────────────────────────────────────
-- Expect every row NULL right after this migration.
SELECT COUNT(*)                                                  AS total,
       SUM(CASE WHEN last_login_at IS NULL THEN 1 ELSE 0 END)    AS never_logged_in
FROM [dbo].[Users];

PRINT 'V95 complete. Values appear as people sign in.';
