-- V37: Interview Scheduling
-- Creates interview_types and candidate_interviews tables.
-- interview_types: admin-managed per pays, seeds 3 default types per existing pays.
-- candidate_interviews: linked to candidates with anti-duplication filtered index.

-- ── interview_types ────────────────────────────────────────────────────────────
CREATE TABLE [dbo].[interview_types] (
    [id]          BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [pays_id]     BIGINT NOT NULL,
    [name]        NVARCHAR(150) NOT NULL,
    [description] NVARCHAR(500) NULL,
    [order_index] INT NOT NULL DEFAULT 0,
    [is_active]   BIT NOT NULL DEFAULT 1,
    [created_at]  DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    [updated_at]  DATETIMEOFFSET(6) NULL,
    CONSTRAINT [FK_InterviewType_Pays]
        FOREIGN KEY ([pays_id]) REFERENCES [dbo].[pays]([id])
);
GO

-- ── candidate_interviews ───────────────────────────────────────────────────────
CREATE TABLE [dbo].[candidate_interviews] (
    [id]                BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [candidate_id]      BIGINT NOT NULL,
    [interview_type_id] BIGINT NOT NULL,
    [scheduled_at]      DATETIMEOFFSET(6) NOT NULL,
    [location]          NVARCHAR(255) NULL,
    [interviewer_notes] NVARCHAR(1000) NULL,
    [status]            NVARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    [result]            NVARCHAR(10) NULL,
    [sequence_number]   INT NOT NULL DEFAULT 1,
    [created_by]        BIGINT NOT NULL,
    [created_at]        DATETIMEOFFSET(6) NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    [updated_at]        DATETIMEOFFSET(6) NULL,
    CONSTRAINT [FK_CandidateInterview_Candidate]
        FOREIGN KEY ([candidate_id]) REFERENCES [dbo].[candidates]([id]),
    CONSTRAINT [FK_CandidateInterview_InterviewType]
        FOREIGN KEY ([interview_type_id]) REFERENCES [dbo].[interview_types]([id])
);
GO

-- Anti-duplication: at most one PLANNED interview per type per candidate
CREATE UNIQUE INDEX [UX_CandidateInterview_PlannedType]
    ON [dbo].[candidate_interviews] ([candidate_id], [interview_type_id])
    WHERE ([status] = 'PLANNED');
GO

-- ── Seed: 3 default interview types per existing pays ─────────────────────────
INSERT INTO [dbo].[interview_types] ([pays_id], [name], [description], [order_index], [is_active])
SELECT p.[id], N'Entretien RH', N'Entretien avec le service Ressources Humaines', 1, 1
FROM [dbo].[pays] p
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[interview_types] it
    WHERE it.[pays_id] = p.[id] AND it.[name] = N'Entretien RH'
);
GO

INSERT INTO [dbo].[interview_types] ([pays_id], [name], [description], [order_index], [is_active])
SELECT p.[id], N'Entretien Technique', N'Entretien technique avec un expert métier', 2, 1
FROM [dbo].[pays] p
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[interview_types] it
    WHERE it.[pays_id] = p.[id] AND it.[name] = N'Entretien Technique'
);
GO

INSERT INTO [dbo].[interview_types] ([pays_id], [name], [description], [order_index], [is_active])
SELECT p.[id], N'Entretien Direction', N'Entretien final avec la direction', 3, 1
FROM [dbo].[pays] p
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[interview_types] it
    WHERE it.[pays_id] = p.[id] AND it.[name] = N'Entretien Direction'
);
GO
