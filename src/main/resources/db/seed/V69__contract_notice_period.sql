-- ============================================================
-- V69 — Préavis figé sur le contrat
--
-- ⚠️ The module 500s until this is applied (ddl-auto: none, mapped columns).
--
-- THIS IS THE SOURCE OF TRUTH. One préavis per contract, written when the contract is
-- created and never recomputed. A new négociation means a new contract, which is exactly
-- how the business rule reads: the préavis is not editable after onboarding completion.
--
-- WHY FROZEN AND NOT RESOLVED ON READ: the grade default and the offer both move — the
-- grade because an admin retunes it, the offer because job_offers is mutated in place on
-- renegotiation. Resolving on read would silently rewrite what an employee is owed months
-- after they signed. The trial period already works this way (date_fin_periode_essai is
-- computed once in doCreateContract), so this keeps the two symmetric.
--
-- notice_period_source records WHERE the figure came from, so the UI can say
-- "30 j (défaut du grade)" vs "45 j (négocié)" instead of showing an unexplained number:
--   GRADE_DEFAULT — copied from grades.notice_period_days
--   NEGOTIATED    — copied from the accepted offer (job_offers.notice_period_days)
--   MANUAL        — typed by RH on the contract form / onboarding Contrat step
--
-- NULL days = unknown, never 0. A contract created before this migration has no figure and
-- must say so; the offboarding resolver falls back to the grade only to avoid a blank on
-- historical files.
--
--   sqlcmd -S <server> -d DAF360_HR -i V69__contract_notice_period.sql
-- Re-runnable: guarded columns, no seed.
-- Created: 2026-08-06
-- ============================================================

IF COL_LENGTH('dbo.employee_contracts', 'notice_period_days') IS NULL
  ALTER TABLE [dbo].[employee_contracts]
    ADD [notice_period_days] INT NULL;
GO

IF COL_LENGTH('dbo.employee_contracts', 'notice_period_source') IS NULL
  ALTER TABLE [dbo].[employee_contracts]
    ADD [notice_period_source] VARCHAR(20) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_EmpContract_NoticePeriodDays')
  ALTER TABLE [dbo].[employee_contracts]
    ADD CONSTRAINT [CK_EmpContract_NoticePeriodDays]
        CHECK ([notice_period_days] IS NULL OR [notice_period_days] >= 0);
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_EmpContract_NoticePeriodSource')
  ALTER TABLE [dbo].[employee_contracts]
    ADD CONSTRAINT [CK_EmpContract_NoticePeriodSource]
        CHECK ([notice_period_source] IS NULL
               OR [notice_period_source] IN ('GRADE_DEFAULT', 'NEGOTIATED', 'MANUAL'));
GO

-- Deliberately NOT backfilled. Copying today's grade default onto contracts signed before
-- this existed would fabricate an agreement. They read as unknown, and the offboarding
-- resolver falls back to the grade so the file is not blank.
PRINT '--- Active contracts with NO préavis (pre-V69; offboarding falls back to the grade) ---';
SELECT COUNT(*) AS contracts_without_notice
FROM [dbo].[employee_contracts]
WHERE is_active = 1 AND [notice_period_days] IS NULL;
GO

PRINT 'V69 applied: employee_contracts.notice_period_days / notice_period_source.';
GO
