-- ============================================================
-- V62 — Offboarding stage 5: a schedulable exit interview
--
-- ⚠️ The module 500s until this is applied (ddl-auto: none, mapped columns).
--
-- The design's primary action on stage 5 is **Planifier**, but the API could only ever
-- record an interview that had already happened: `conducted_by` and `conducted_date` were
-- both NOT NULL, so a *planned* interview was unrepresentable. And because
-- `saveExitInterview` threw ALREADY_EXISTS on a second call, the UI — which prefills the
-- existing record for editing — could never save an edit either. That 409 is live today.
--
--   sqlcmd -S <server> -d DAF360_HR -i V62__offboarding_exit_interview_schedulable.sql
-- Re-runnable: guarded columns, guarded relaxations, idempotent backfill.
-- Created: 2026-08-04
-- ============================================================

IF COL_LENGTH('dbo.exit_interviews', 'scheduled_at') IS NULL
  ALTER TABLE [dbo].[exit_interviews]
    ADD [scheduled_at] DATETIMEOFFSET(6) NULL;
GO

IF COL_LENGTH('dbo.exit_interviews', 'status') IS NULL
BEGIN
  ALTER TABLE [dbo].[exit_interviews]
    ADD [status] NVARCHAR(20) NOT NULL
      CONSTRAINT [DF_exit_interviews_status] DEFAULT 'PENDING';
  PRINT 'exit_interviews.status added.';
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_exit_interview_status')
  ALTER TABLE [dbo].[exit_interviews]
    ADD CONSTRAINT [CK_exit_interview_status]
      CHECK ([status] IN ('PENDING','SCHEDULED','DONE'));
GO

-- ── Relax the two NOT NULLs ──────────────────────────────────
-- A scheduled interview has neither a conductor nor a conducted date yet. These being NOT
-- NULL is the single reason the API could not express "planned".
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'exit_interviews' AND COLUMN_NAME = 'conducted_by'
             AND IS_NULLABLE = 'NO')
  ALTER TABLE [dbo].[exit_interviews] ALTER COLUMN [conducted_by] BIGINT NULL;
GO

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME = 'exit_interviews' AND COLUMN_NAME = 'conducted_date'
             AND IS_NULLABLE = 'NO')
  ALTER TABLE [dbo].[exit_interviews] ALTER COLUMN [conducted_date] DATE NULL;
GO

-- ── Backfill: every existing row IS a conducted interview ────
-- Nothing could be stored before this migration unless it had already happened, so the
-- correct status for existing rows is DONE, not the DEFAULT 'PENDING'.
UPDATE [dbo].[exit_interviews]
SET [status] = 'DONE'
WHERE [conducted_date] IS NOT NULL
  AND ([status] IS NULL OR [status] = 'PENDING');

PRINT 'V62 — ' + CAST(@@ROWCOUNT AS VARCHAR) + ' existing interview(s) marked DONE.';
GO
