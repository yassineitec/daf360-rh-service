-- ============================================================
-- V57 — Offboarding: stage-1 (Déclaration) fields
--
-- The declaration is now a step you FILL IN, not just what was captured when the
-- file was opened: a file can be started from a profile with nothing but a departure
-- type, and it sits in the Déclaration column until these are set.
--
-- This is the stage-1 subset of OFFBOARDING-BACKEND-CHANGES.md §1a. The stage-2
-- manager/HR validation pairs and the later-stage columns are deliberately NOT here —
-- there is no endpoint that writes them yet, and shipping columns nothing fills makes
-- it impossible to tell "not done" from "not implemented".
--
-- rh-service has NO Flyway. Apply by hand:
--   sqlcmd -S <server> -d DAF360_HR -i V57__offboarding_declaration_fields.sql
-- Re-runnable: every column is guarded.
-- Created: 2026-08-04
-- ============================================================

-- ── offboarding_workflow_instances — declaration columns ─────

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'justification_document_url') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [justification_document_url] NVARCHAR(500) NULL;
GO

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'justification_document_name') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [justification_document_name] NVARCHAR(255) NULL;
GO

-- "3 mois", "1 mois", "Aucun" — a label, not a duration: the notice period comes from
-- a convention collective per pays and is not arithmetic we do here.
IF COL_LENGTH('dbo.offboarding_workflow_instances', 'notice_period_label') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [notice_period_label] NVARCHAR(50) NULL;
GO

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'notice_waiver_requested') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [notice_waiver_requested] BIT NOT NULL
      CONSTRAINT [DF_offboarding_notice_waiver] DEFAULT 0;
GO

-- Distinct from last_working_day: theoretical = trigger date + notice per the
-- convention; last_working_day = what the two sides actually agreed.
IF COL_LENGTH('dbo.offboarding_workflow_instances', 'theoretical_exit_date') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [theoretical_exit_date] DATE NULL;
GO

PRINT 'V57 — offboarding declaration columns present.';
GO
