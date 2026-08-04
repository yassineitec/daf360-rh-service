-- ============================================================
-- V63 — Offboarding stage 6: Solde de tout compte
--
-- ⚠️ The module 500s until this is applied (ddl-auto: none, mapped columns).
--
-- WHY LINES AND NOT A CALCULATION: the three amounts a STC is made of need data
-- rh-service does not hold.
--   · congés payés        → a leave BALANCE. No such table exists in DAF360_HR.
--   · indemnité de rupture → a per-pays convention scale. Exists nowhere.
--   · prorata 13ᵉ mois     → net salary + seniority. THIS one is computable:
--                            employee_profiles.salaire_net_rh (V24) + hire_date.
-- Salary and leave belong to payroll-service. So the amounts are stored as editable
-- lines that RH owns, with the 13ᵉ mois offered as a suggestion they can override —
-- which replaces the spreadsheet without inventing legal figures. `is_suggested` records
-- which figures the system proposed, so an audit can tell them from a human's.
--
--   sqlcmd -S <server> -d DAF360_HR -i V63__offboarding_settlement.sql
-- Re-runnable: guarded column, guarded table.
-- Created: 2026-08-04
-- ============================================================

IF COL_LENGTH('dbo.offboarding_workflow_instances', 'settlement_execution_date') IS NULL
  ALTER TABLE [dbo].[offboarding_workflow_instances]
    ADD [settlement_execution_date] DATE NULL;
GO

IF OBJECT_ID('dbo.offboarding_settlement_lines', 'U') IS NULL
BEGIN
  CREATE TABLE [dbo].[offboarding_settlement_lines] (
    [id]                   BIGINT IDENTITY(1,1) NOT NULL,
    [workflow_instance_id] BIGINT               NOT NULL,
    [label]                NVARCHAR(255)        NOT NULL,
    -- Same precision as employee_profiles.salaire_net_* so a suggestion round-trips exactly.
    [amount]               DECIMAL(12,3)        NOT NULL DEFAULT 0,
    -- Signed on purpose: a STC also carries deductions (avance sur salaire, matériel non
    -- restitué). Forcing positives would push the sign into the label.
    [is_suggested]         BIT                  NOT NULL DEFAULT 0,
    [order_index]          INT                  NOT NULL DEFAULT 0,
    [created_by]           BIGINT               NULL,
    [created_at]           DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    [updated_at]           DATETIMEOFFSET(6)    NULL,
    CONSTRAINT [PK_offboarding_settlement_lines] PRIMARY KEY ([id]),
    CONSTRAINT [FK_settlement_workflow] FOREIGN KEY ([workflow_instance_id])
      REFERENCES [dbo].[offboarding_workflow_instances]([id])
  );
  CREATE NONCLUSTERED INDEX [IX_settlement_workflow]
    ON [dbo].[offboarding_settlement_lines]([workflow_instance_id], [order_index]);
  PRINT 'offboarding_settlement_lines created.';
END
ELSE
  PRINT 'offboarding_settlement_lines already present.';
GO
