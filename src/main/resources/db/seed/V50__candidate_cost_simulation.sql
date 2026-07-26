-- 1. Salary fields on candidates
ALTER TABLE [dbo].[candidates]
    ADD [salaire_net_candidat] DECIMAL(18,4) NULL,
        [salaire_net_rh]       DECIMAL(18,4) NULL;
GO

-- 2. Payroll contract code on configurable list values
ALTER TABLE [dbo].[configurable_list_values]
    ADD [payroll_contract_code] NVARCHAR(20) NULL;
GO

-- 3. Candidate cost approvals table
CREATE TABLE [dbo].[candidate_cost_approvals] (
    [id]                    BIGINT IDENTITY(1,1) PRIMARY KEY,
    [candidate_id]          BIGINT NOT NULL REFERENCES [dbo].[candidates]([id]) ON DELETE CASCADE,
    [pays_id]               BIGINT NOT NULL,
    [fiscal_year]           INT    NOT NULL,
    [salaire_net_rh]        DECIMAL(18,4) NOT NULL,
    [salaire_net_candidat]  DECIMAL(18,4) NULL,
    [contract_type_code]    NVARCHAR(20)  NOT NULL,
    [simulation_snapshot]   NVARCHAR(MAX) NOT NULL,
    [status]                NVARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    [submitted_by]          BIGINT NOT NULL,
    [submitted_at]          DATETIMEOFFSET NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    [approved_by]           BIGINT NULL,
    [approved_at]           DATETIMEOFFSET NULL,
    [approval_notes]        NVARCHAR(1000) NULL
);
GO

CREATE INDEX IX_cca_pays_status ON [dbo].[candidate_cost_approvals]([pays_id], [status]);
GO
