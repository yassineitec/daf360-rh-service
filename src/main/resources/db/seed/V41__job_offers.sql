-- V41: Job Offers (offer / salary-negotiation stage of recruitment)
-- 1. Extends CK_Candidate_Status to allow the new OFFER_SENT status.
-- 2. Creates the [dbo].[job_offers] table (one offer per candidate).
--
-- NOTE (deploy): rh-service has no Flyway runner — apply this script manually
-- against DAF360_HR BEFORE deploying the code, or candidate/offer writes will 500.

USE [DAF360_HR];
GO

-- ── 1. Allow OFFER_SENT in the candidate status check constraint ──────────────
-- Drop whatever CHECK constraint currently guards candidates.status (name may
-- vary across environments), then recreate it with OFFER_SENT included.
DECLARE @ck sysname;
SELECT @ck = cc.name
FROM sys.check_constraints cc
JOIN sys.columns col
     ON col.object_id = cc.parent_object_id
    AND col.column_id = cc.parent_column_id
WHERE cc.parent_object_id = OBJECT_ID(N'[dbo].[candidates]')
  AND col.name = 'status';

IF @ck IS NOT NULL
    EXEC('ALTER TABLE [dbo].[candidates] DROP CONSTRAINT [' + @ck + ']');
GO

ALTER TABLE [dbo].[candidates] WITH CHECK
    ADD CONSTRAINT [CK_Candidate_Status] CHECK ([status] IN (
        'PENDING', 'ACCEPTED', 'OFFER_SENT', 'REJECTED',
        'IT_IN_PROGRESS', 'EMAIL_RECEIVED', 'HR_IN_PROGRESS', 'HIRED', 'ARCHIVED'
    ));
GO

-- ── 2. job_offers ─────────────────────────────────────────────────────────────
IF OBJECT_ID(N'[dbo].[job_offers]', N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[job_offers] (
        [id]               BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        [candidate_id]     BIGINT NOT NULL,
        [asked_salary]     DECIMAL(12,3) NULL,
        [proposed_salary]  DECIMAL(12,3) NULL,
        [salary_note]      NVARCHAR(255) NULL,
        [expected_hire_date] DATE NULL,
        [expiry_date]      DATE NULL,
        [sent_at]          DATETIMEOFFSET(6) NULL,
        [decided_at]       DATETIMEOFFSET(6) NULL,
        [status]           NVARCHAR(20) NOT NULL DEFAULT 'SENT',
        [rejection_reason] NVARCHAR(500) NULL,
        [created_by]       BIGINT NOT NULL,
        [created_at]       DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        [updated_at]       DATETIMEOFFSET(6) NULL,
        CONSTRAINT [FK_JobOffer_Candidate]
            FOREIGN KEY ([candidate_id]) REFERENCES [dbo].[candidates]([id]),
        CONSTRAINT [UQ_JobOffer_Candidate] UNIQUE ([candidate_id]),
        CONSTRAINT [CK_JobOffer_Status] CHECK ([status] IN (
            'SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED'
        ))
    );
END
GO
