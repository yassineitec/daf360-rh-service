-- ============================================================
-- V59 — Offboarding stage 2 (Validation Manager & RH)
--
-- Stage 2 was drawn but had NO backing at all: every field the panel rendered was an
-- optional TypeScript-only property that no column, entity or endpoint ever provided.
-- It also read `validated_at` as a fallback — the STAGE-7 file validation — so the
-- manager panel turned green the moment the file was closed, retroactively claiming an
-- approval that never happened.
--
-- Two stamps, deliberately separate from `validated_by` / `validated_at`:
--   manager_* — the departing employee's manager acknowledges the departure and comments
--   hr_*      — RH validates and adjusts the final date
-- `validated_by/at` stays the file-level closure (stage 7). Overloading it is exactly
-- what produced the false green.
--
-- rh-service has NO Flyway. Apply by hand:
--   sqlcmd -S <server> -d DAF360_HR -i V59__offboarding_validation_fields.sql
-- Re-runnable: every column is guarded and the backfill is idempotent.
-- Created: 2026-08-04
-- ============================================================

-- ── Manager decision ─────────────────────────────────────────
IF COL_LENGTH('dbo.offboarding_workflow_instances', 'manager_validated_by') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [manager_validated_by] BIGINT NULL;
GO

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'manager_validated_at') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [manager_validated_at] DATETIMEOFFSET(6) NULL;
GO

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'manager_comment') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [manager_comment] NVARCHAR(1000) NULL;
GO

-- ── RH validation / adjustment ───────────────────────────────
IF COL_LENGTH('dbo.offboarding_workflow_instances', 'hr_validated_by') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [hr_validated_by] BIGINT NULL;
GO

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'hr_validated_at') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [hr_validated_at] DATETIMEOFFSET(6) NULL;
GO

-- Feeds the solde de tout compte: notice paid but not served.
IF COL_LENGTH('dbo.offboarding_workflow_instances', 'notice_paid_not_worked') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [notice_paid_not_worked] BIT NOT NULL
      CONSTRAINT [DF_offboarding_notice_paid_not_worked] DEFAULT 0;
GO

-- ── Backfill: do not freeze files that are already in flight ──
--
-- Stage 2 becomes a real gate — stages 3 to 7 are locked until both stamps exist. Every
-- instance that already exists was created under a model where stage 2 gated nothing, so
-- without this they would all regress to "waiting on a validation nobody was ever asked
-- for", and their IT returns, exit interviews and settlements would lock.
--
-- So: stamp both, for every pre-existing instance, attributed to whoever opened the file
-- and dated from its creation. Only files created after this migration get the real gate.
-- The comment says plainly that this was inferred, not recorded — an audit trail that
-- claims a manager approved something is worse than one that admits it does not know.
UPDATE [dbo].[offboarding_workflow_instances]
SET [manager_validated_by] = [initiated_by],
    [manager_validated_at] = [created_at],
    [manager_comment]      = N'(Reprise V59 — dossier ouvert avant l''étape de validation ; avis manager non enregistré à l''époque.)',
    [hr_validated_by]      = [initiated_by],
    [hr_validated_at]      = [created_at]
WHERE [manager_validated_at] IS NULL
  AND [hr_validated_at] IS NULL;

PRINT 'V59 — validation columns present; ' + CAST(@@ROWCOUNT AS VARCHAR) + ' pre-existing instance(s) backfilled.';
GO
