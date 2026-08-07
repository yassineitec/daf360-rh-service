-- ============================================================
-- V68 — Négociation du préavis sur l'offre
--
-- ⚠️ The module 500s until this is applied (ddl-auto: none, mapped columns).
--
-- WHY: the préavis is negotiated, and the negotiation happens when the offer is made —
-- the same conversation as the salary. Putting it anywhere else means the agreed figure is
-- reconstructed from memory when the contract is drawn up.
--
-- `notice_period_days` starts from the candidate's grade default (V64) and is overridable.
-- `notice_period_note` is where a derogation is justified, since job_offers is mutated in
-- place on renegotiation and the previous rounds survive only in the audit log.
--
-- This is an INPUT to the contract, never a source after hiring: once the contract exists
-- it carries its own frozen value (V69) and the offer is not read again.
--
--   sqlcmd -S <server> -d DAF360_HR -i V68__offer_notice_period.sql
-- Re-runnable: guarded columns.
-- Created: 2026-08-06
-- ============================================================

IF COL_LENGTH('dbo.job_offers', 'notice_period_days') IS NULL
  ALTER TABLE [dbo].[job_offers]
    ADD [notice_period_days] INT NULL;
GO

IF COL_LENGTH('dbo.job_offers', 'notice_period_note') IS NULL
  ALTER TABLE [dbo].[job_offers]
    ADD [notice_period_note] NVARCHAR(255) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_JobOffer_NoticePeriodDays')
  ALTER TABLE [dbo].[job_offers]
    ADD CONSTRAINT [CK_JobOffer_NoticePeriodDays]
        CHECK ([notice_period_days] IS NULL OR [notice_period_days] >= 0);
GO

-- Deliberately NOT backfilled from the grade default. An offer already sent carries the
-- terms that were actually communicated to the candidate; inventing a figure after the
-- fact would put a number in a document nobody agreed to. Open offers show it as unset.
PRINT 'V68 applied: job_offers.notice_period_days / notice_period_note.';
GO
