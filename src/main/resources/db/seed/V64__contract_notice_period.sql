-- ============================================================
-- V64 — Préavis (notice period) on contract_type_config
--
-- ⚠️ The module 500s until this is applied (ddl-auto: none, mapped columns).
--
-- WHY HERE: the préavis was typed by hand into the offboarding declaration
-- (`notice_period_label`, V57), so every file could carry a different figure for the
-- same contract. It is a property of the contract, not of the departure — and
-- `contract_type_config` already holds the trial-period pair, the indemnity rate and the
-- CIVP/stage rules for the same (pays × contract type) key, and is already editable from
-- the admin screen through EmployeeLifecycleController.updateConfig.
--
-- No per-employee override: a negotiated contract that departs from the convention is out
-- of scope. When it is needed, it belongs on employee_contracts, not here.
--
-- NULLABLE, not NOT NULL DEFAULT 0: a NULL says "not configured for this pays yet", which
-- the resolver reports as unknown instead of silently offering a same-day exit.
--
-- THE SEEDED VALUES ARE DEFAULTS TO REVIEW, not legal advice. Tunisian practice for a CDI
-- is one month for a non-cadre and three for a cadre; the contract types that end at their
-- own term get 0. Adjust per pays in the admin screen.
--
--   sqlcmd -S <server> -d DAF360_HR -i V64__contract_notice_period.sql
-- Re-runnable: guarded columns, and the seed only fills rows that are still NULL.
-- Created: 2026-08-05
-- ============================================================

IF COL_LENGTH('dbo.contract_type_config', 'notice_period_days_standard') IS NULL
  ALTER TABLE [dbo].[contract_type_config]
    ADD [notice_period_days_standard] INT NULL;
GO

IF COL_LENGTH('dbo.contract_type_config', 'notice_period_days_manager') IS NULL
  ALTER TABLE [dbo].[contract_type_config]
    ADD [notice_period_days_manager] INT NULL;
GO

-- Seed every existing (pays × type) row by its type code. `WHERE ... IS NULL` makes this
-- re-runnable and, more importantly, never overwrites a value an admin has since changed.
UPDATE [dbo].[contract_type_config]
   SET [notice_period_days_standard] = 30,
       [notice_period_days_manager]  = 90
 WHERE [contract_type_code] = 'CDI'
   AND [notice_period_days_standard] IS NULL;
GO

UPDATE [dbo].[contract_type_config]
   SET [notice_period_days_standard] = 30,
       [notice_period_days_manager]  = 30
 WHERE [contract_type_code] = 'DETACHEMENT'
   AND [notice_period_days_standard] IS NULL;
GO

-- CDD, CIVP, STAGE and PORTAGE end at their own term: leaving early is a rupture, not a
-- préavis, so 0 is the configured answer rather than an unfilled one.
UPDATE [dbo].[contract_type_config]
   SET [notice_period_days_standard] = 0,
       [notice_period_days_manager]  = 0
 WHERE [contract_type_code] IN ('CDD', 'CIVP', 'STAGE', 'PORTAGE')
   AND [notice_period_days_standard] IS NULL;
GO

PRINT 'V64 applied: notice_period_days_standard / _manager on contract_type_config.';
GO
