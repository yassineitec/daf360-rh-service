-- ============================================================
-- V64 — Préavis (notice period) default per GRADE
--
-- ⚠️ The module 500s until this is applied (ddl-auto: none, mapped column).
--
-- WHY GRADES: the préavis is negotiated per employee, and the only thing that is
-- configuration is the DEFAULT the negotiation starts from. That default belongs to the
-- grade (séniorité), not to the country × contract type: two people in the same country on
-- the same CDI can owe different préavis, and that is the normal case, not the exception.
--
-- `grades` is already scoped per pays (`grades.pays_id`), so "a different default per
-- country" comes for free without a second key.
--
-- THE CHAIN THIS OPENS:
--     grades.notice_period_days           default, here
--   → job_offers.notice_period_days       negotiated at offer time (V68)
--   → employee_contracts.notice_period_days  frozen when the contract is created (V69)
--
-- Only the contract is ever read afterwards. The grade is a fallback for rows that predate
-- V69, and the offer is an input to the contract — never a source after hiring.
--
-- NULLABLE, AND DELIBERATELY NOT SEEDED: a NULL says "no default agreed for this grade",
-- which the resolver reports as unknown. A seeded 30/90 would look like a decided figure
-- and nobody would ever revisit it. The grades admin screen flags unset grades instead.
--
--   sqlcmd -S <server> -d DAF360_HR -i V64__contract_notice_period.sql
-- Re-runnable: guarded column, no seed.
-- Created: 2026-08-05 · Rewritten 2026-08-06 (was pays × contract_type — wrong premise)
-- ============================================================

IF COL_LENGTH('dbo.grades', 'notice_period_days') IS NULL
  ALTER TABLE [dbo].[grades]
    ADD [notice_period_days] INT NULL;
GO

-- A negative préavis is not a thing; 0 is (a contract that ends at its own term).
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_Grades_NoticePeriodDays')
  ALTER TABLE [dbo].[grades]
    ADD CONSTRAINT [CK_Grades_NoticePeriodDays]
        CHECK ([notice_period_days] IS NULL OR [notice_period_days] >= 0);
GO

PRINT '--- Active grades with NO préavis default (negotiation will start from nothing) ---';
SELECT g.pays_id, g.code, g.label_fr
FROM [dbo].[grades] g
WHERE g.is_active = 1 AND g.[notice_period_days] IS NULL
ORDER BY g.pays_id, g.sort_order;
GO

PRINT 'V64 applied: grades.notice_period_days (nullable, unseeded by design).';
GO
