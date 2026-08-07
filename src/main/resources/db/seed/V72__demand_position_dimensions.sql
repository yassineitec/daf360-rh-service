-- ============================================================
-- V72 — Grade, discipline and department FKs on recruitment_demands
--
-- ⚠️ The module 500s until this is applied (ddl-auto: none, mapped columns).
-- ⚠️ PREREQUISITE: V32 must be applied first — it creates the table's other position columns.
--
-- WHY: a recruitment demand describes a POSITION, and a position is described everywhere else
-- in the app by the same three dimension tables the employee profiles use — grades,
-- disciplines, hr_departments. The demand had none of them: no grade, no discipline, and its
-- department was a free-text NVARCHAR(255) holding a label.
--
-- Three concrete costs of that, all of which this removes:
--
--   1. The candidate form's prefill (pick a vacancy → fill the position fields) could only
--      fill THREE fields, because the demand did not carry grade or discipline.
--   2. That prefill matched the department BY LABEL STRING against the departments list —
--      guesswork that breaks the moment a label is edited.
--   3. The préavis default lives on the GRADE (V64). Without a grade on the demand, the
--      default could not be known until the contract was created, which is the last possible
--      moment rather than the first.
--
-- The existing `department` NVARCHAR column STAYS. Demands already carry labels in it and
-- rewriting them into FKs would need a name match — the exact guesswork this replaces. New
-- demands write both: the id for resolution, the label so old readers keep working.
--
--   sqlcmd -S <server> -d DAF360_HR -i V72__demand_position_dimensions.sql
-- Re-runnable: guarded columns and constraints.
-- Created: 2026-08-07
-- ============================================================

IF COL_LENGTH('dbo.recruitment_demands', 'grade_id') IS NULL
  ALTER TABLE [dbo].[recruitment_demands] ADD [grade_id] BIGINT NULL;
GO

IF COL_LENGTH('dbo.recruitment_demands', 'discipline_id') IS NULL
  ALTER TABLE [dbo].[recruitment_demands] ADD [discipline_id] BIGINT NULL;
GO

IF COL_LENGTH('dbo.recruitment_demands', 'department_id') IS NULL
  ALTER TABLE [dbo].[recruitment_demands] ADD [department_id] BIGINT NULL;
GO

-- FKs to the same dimension tables employee_profiles points at, so a vacancy and the profile
-- it becomes cannot name different things. NO CASCADE: deactivating a grade must not delete
-- the demands that referenced it (the dimension tables soft-delete via is_active).
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'FK_demand_grade')
  ALTER TABLE [dbo].[recruitment_demands]
    ADD CONSTRAINT [FK_demand_grade] FOREIGN KEY ([grade_id])
        REFERENCES [dbo].[grades]([id]);
GO

IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'FK_demand_discipline')
  ALTER TABLE [dbo].[recruitment_demands]
    ADD CONSTRAINT [FK_demand_discipline] FOREIGN KEY ([discipline_id])
        REFERENCES [dbo].[disciplines]([id]);
GO

IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'FK_demand_department')
  ALTER TABLE [dbo].[recruitment_demands]
    ADD CONSTRAINT [FK_demand_department] FOREIGN KEY ([department_id])
        REFERENCES [dbo].[departments]([id]);
GO

-- Backfills department_id where the stored label matches exactly ONE active department of the
-- demand's own entity. Deliberately conservative: an ambiguous or unmatched label is left
-- alone rather than guessed at, and the report below lists what stayed behind.
UPDATE rd
   SET rd.[department_id] = d.[id]
FROM [dbo].[recruitment_demands] rd
CROSS APPLY (
    SELECT TOP 2 h.[id]
    FROM [dbo].[departments] h
    WHERE h.[is_active] = 1
      AND h.[pays_id]   = rd.[pays_id]
      AND h.[label_fr]  = rd.[department]
) d
WHERE rd.[department_id] IS NULL
  AND rd.[department] IS NOT NULL
  AND (SELECT COUNT(*) FROM [dbo].[departments] h2
       WHERE h2.[is_active] = 1 AND h2.[pays_id] = rd.[pays_id]
         AND h2.[label_fr] = rd.[department]) = 1;
GO

PRINT '--- Demands whose department label did not resolve to exactly one department ---';
SELECT rd.id, rd.pays_id, rd.department
FROM [dbo].[recruitment_demands] rd
WHERE rd.[department_id] IS NULL AND rd.[department] IS NOT NULL;
GO

PRINT 'V72 applied: recruitment_demands.grade_id / discipline_id / department_id.';
GO
