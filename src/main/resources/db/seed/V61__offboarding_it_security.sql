-- ============================================================
-- V61 — Offboarding stage 4 (Informatique & Matériel)
--
-- ⚠️ The module 500s until this is applied. ddl-auto is none, and both
-- OffboardingWorkflowInstance and OffboardingAssetReturn now map the columns below, so
-- every SELECT names something SQL Server does not have. Apply before deploying.
--
--   sqlcmd -S <server> -d DAF360_HR -i V61__offboarding_it_security.sql
-- Re-runnable: guarded columns, guarded backfill.
-- Created: 2026-08-04
-- ============================================================

-- ── Instance: account deactivation + décharge ────────────────
IF COL_LENGTH('dbo.offboarding_workflow_instances', 'account_deactivation_at') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [account_deactivation_at] DATETIMEOFFSET(6) NULL;
GO

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'discharge_document_url') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [discharge_document_url] NVARCHAR(500) NULL;
GO

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'discharge_document_name') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [discharge_document_name] NVARCHAR(255) NULL;
GO

-- ── Asset returns: serial number as its own field ────────────
IF COL_LENGTH('dbo.offboarding_asset_returns', 'serial_number') IS NULL
  ALTER TABLE [dbo].[offboarding_asset_returns]
    ADD [serial_number] NVARCHAR(100) NULL;
GO

-- Explicit flag. The UI derives urgency from `expected_return_date < today` when this is
-- 0, which cannot express "chase this one regardless of the date".
IF COL_LENGTH('dbo.offboarding_asset_returns', 'is_urgent') IS NULL
  ALTER TABLE [dbo].[offboarding_asset_returns]
    ADD [is_urgent] BIT NOT NULL
      CONSTRAINT [DF_asset_returns_is_urgent] DEFAULT 0;
GO

-- ── Backfill: pull the serial out of the description ─────────
--
-- `seedItAssetReturns` built descriptions as
--   "<brandModel> (<typeLabel>) — S/N <serial>"
-- so the serial has been smuggled into a display string since V44. The separator is a
-- literal the code always emitted, which makes this exactly reversible: take what follows
-- it as the serial, and cut it from the description so the UI does not show the same number
-- on two lines.
--
-- Deliberately narrow: only rows matching that separator, only where serial_number is
-- still empty. A description written by hand through "Ajouter un équipement" is untouched.
UPDATE [dbo].[offboarding_asset_returns]
SET [serial_number]     = LTRIM(RTRIM(SUBSTRING(
                            [asset_description],
                            CHARINDEX(N' — S/N ', [asset_description]) + 7,
                            LEN([asset_description])))),
    [asset_description] = LTRIM(RTRIM(LEFT(
                            [asset_description],
                            CHARINDEX(N' — S/N ', [asset_description]) - 1)))
WHERE [serial_number] IS NULL
  AND CHARINDEX(N' — S/N ', [asset_description]) > 1;

PRINT 'V61 — ' + CAST(@@ROWCOUNT AS VARCHAR) + ' asset return(s) had their serial number extracted.';
GO
