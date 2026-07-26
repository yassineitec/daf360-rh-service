USE [DAF360_HR];
GO

-- ============================================================
-- V50 — Candidate hiring cost simulation
-- Adds salary fields to candidates, payroll contract code to
-- configurable list values, and the candidate_cost_approvals
-- approval workflow table.
-- Apply manually: run each GO-separated batch sequentially.
-- ============================================================

-- 1. Salary fields on candidates
IF COL_LENGTH('[dbo].[candidates]', 'salaire_net_candidat') IS NULL
BEGIN
    ALTER TABLE [dbo].[candidates]
        ADD [salaire_net_candidat] DECIMAL(18,4) NULL,
            [salaire_net_rh]       DECIMAL(18,4) NULL;
END
GO

-- 2. Payroll contract code on configurable list values
IF COL_LENGTH('[dbo].[configurable_list_values]', 'payroll_contract_code') IS NULL
BEGIN
    ALTER TABLE [dbo].[configurable_list_values]
        ADD [payroll_contract_code] NVARCHAR(20) NULL;
END
GO

-- 3. Candidate cost approvals table
IF OBJECT_ID(N'[dbo].[candidate_cost_approvals]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[candidate_cost_approvals] (
        [id]                    BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        [candidate_id]          BIGINT NOT NULL,
        [pays_id]               BIGINT NOT NULL,
        [fiscal_year]           INT    NOT NULL,
        [salaire_net_rh]        DECIMAL(18,4)    NOT NULL,
        [salaire_net_candidat]  DECIMAL(18,4)    NULL,
        [contract_type_code]    NVARCHAR(20)     NOT NULL,
        [simulation_snapshot]   NVARCHAR(MAX)    NOT NULL,
        [status]                NVARCHAR(20)     NOT NULL DEFAULT 'PENDING',
        [submitted_by]          BIGINT           NOT NULL,
        [submitted_at]          DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [approved_by]           BIGINT           NULL,
        [approved_at]           DATETIMEOFFSET(6) NULL,
        [approval_notes]        NVARCHAR(1000)   NULL,
        CONSTRAINT [FK_CCA_Candidate] FOREIGN KEY ([candidate_id])
            REFERENCES [dbo].[candidates]([id]) ON DELETE CASCADE,
        CONSTRAINT [CK_CCA_Status] CHECK ([status] IN ('PENDING', 'APPROVED', 'REJECTED'))
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('[dbo].[candidate_cost_approvals]') AND name = 'IX_cca_pays_status')
    CREATE INDEX IX_cca_pays_status ON [dbo].[candidate_cost_approvals]([pays_id], [status]);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id = OBJECT_ID('[dbo].[candidate_cost_approvals]') AND name = 'IX_cca_candidate')
    CREATE INDEX IX_cca_candidate ON [dbo].[candidate_cost_approvals]([candidate_id]);
GO
