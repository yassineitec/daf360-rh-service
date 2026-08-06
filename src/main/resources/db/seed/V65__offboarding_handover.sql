-- ============================================================
-- V65 — Offboarding stage 3 (Passation): window and free-text PV
--
-- ⚠️ The module 500s until this is applied (ddl-auto: none, mapped columns).
--
-- WHY: the passation had a successor and an uploadable PV, and nothing else. The manager who
-- runs it could not say when it starts, so no duration could be computed, and the PV could
-- only be a file — a manager who wants to write three lines had to produce a document to
-- attach. Both are what the stage is actually made of.
--
--   · handover_started_at  → the manager sets it; the window is [start .. last_working_day].
--                            DATE, not a timestamp: a passation is planned in days.
--   · handover_minutes_text → the PV written in place. Coexists with the uploaded file: either
--                            one satisfies the stage, and a manager may well do both (write
--                            the summary, attach the signed scan).
--
-- The duration itself is NOT stored. It is derived from the window, the pays' weekend days
-- and the employee's validated absences, all of which move — a stored figure would be wrong
-- the moment a leave request is approved.
--
--   sqlcmd -S <server> -d DAF360_HR -i V65__offboarding_handover.sql
-- Re-runnable: guarded columns, and the backfill only touches rows that are still NULL.
-- Created: 2026-08-05
-- ============================================================

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'handover_started_at') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [handover_started_at] DATE NULL;
GO

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'handover_minutes_text') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [handover_minutes_text] NVARCHAR(MAX) NULL;
GO

-- Files already past the declaration get a default start so their passation panel shows a
-- window instead of an empty one. The trigger date is the honest default: it is the day the
-- departure became known, which is when a handover starts in practice. The manager can move
-- it, and a NULL start simply renders as "à définir" — so this backfill is a convenience,
-- not a correctness requirement.
UPDATE [dbo].[offboarding_workflow_instances]
   SET [handover_started_at] = [trigger_date]
 WHERE [handover_started_at] IS NULL
   AND [status] IN ('PENDING', 'IN_PROGRESS', 'BLOCKED')
   AND [trigger_date] IS NOT NULL;
GO

PRINT 'V65 applied: handover_started_at / handover_minutes_text on offboarding_workflow_instances.';
GO
