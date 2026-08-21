-- ============================================================
-- V75 — Repair the interview scheduling schema
--
-- THE BUG: POST /api/hr/candidates/{id}/interviews returns a bare 500
-- (code INTERNAL_ERROR) on the first interview created for a candidate.
--
-- Two objects the code requires are absent from the schema the migrations build:
--
--   1. candidate_interviews.interviewer_user_id
--      V37 created the table WITHOUT this column, but CandidateInterview maps it and
--      CandidateInterviewService.create() always writes it (the panel's first entry
--      becomes the lead). V73 reads it in its backfill without ever adding it, so the
--      column exists in no migration at all — only in seed_recruitment.sql, which is
--      test data and was never applied to prod. rh-service runs ddl-auto=none under
--      both the 'local' and 'prod' profiles, so Hibernate does not add it either.
--      → INSERT fails with "Invalid column name 'interviewer_user_id'".
--
--   2. candidate_interview_interviewers (V73)
--      V73 is a manual-apply seed whose own backfill step reads the column above, so
--      it aborts halfway on any database missing it. create() touches this table twice:
--      the double-booking check (CandidateInterviewRepository.findInterviewerConflicts
--      has an EXISTS over the panel entity) and savePanel().
--      → "Invalid object name 'dbo.candidate_interview_interviewers'".
--
-- WHY THE TAB STILL LOADS: listByCandidate() calls panelsByInterview(), which returns
-- an empty map without querying when the candidate has no interviews yet. So the empty
-- "Entretiens" tab renders fine and only the first save fails — which is what makes this
-- look like a save bug rather than a missing table.
--
-- Ordering matters: the column is added BEFORE the table, because the backfill at the
-- end reads the column. That is the step V73 could not complete.
--
--   sqlcmd -S <server> -d DAF360_HR -i V75__interview_lead_and_panel_fix.sql
--
-- Re-runnable: every block is guarded. Safe on a database where V73 fully applied —
-- it then only runs the backfill, which is itself a NOT EXISTS anti-join.
-- Created: 2026-08-17
-- ============================================================

USE [DAF360_HR];
GO

-- ── 1. The lead interviewer column ───────────────────────────────────────────
-- Nullable on purpose: an interview may be scheduled before its panel is decided,
-- and create() writes NULL when the panel is empty.
IF COL_LENGTH('dbo.candidate_interviews', 'interviewer_user_id') IS NULL
BEGIN
    ALTER TABLE [dbo].[candidate_interviews] ADD [interviewer_user_id] BIGINT NULL;
    PRINT 'V75: added candidate_interviews.interviewer_user_id';
END
ELSE
    PRINT 'V75: candidate_interviews.interviewer_user_id already present';
GO

-- Drives the "my interviews" calendar feed, which filters on the lead directly.
IF COL_LENGTH('dbo.candidate_interviews', 'interviewer_user_id') IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys.indexes
                   WHERE name = 'IX_CandidateInterview_Interviewer'
                     AND object_id = OBJECT_ID('dbo.candidate_interviews'))
BEGIN
    CREATE INDEX [IX_CandidateInterview_Interviewer]
        ON [dbo].[candidate_interviews] ([interviewer_user_id]);
    PRINT 'V75: created IX_CandidateInterview_Interviewer';
END
GO

-- ── 2. The panel table (V73, re-applied idempotently) ────────────────────────
IF OBJECT_ID('dbo.candidate_interview_interviewers', 'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[candidate_interview_interviewers] (
        [id]           BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        [interview_id] BIGINT NOT NULL,
        [user_id]      BIGINT NOT NULL,
        CONSTRAINT [FK_InterviewInterviewer_Interview]
            FOREIGN KEY ([interview_id]) REFERENCES [dbo].[candidate_interviews]([id])
            ON DELETE CASCADE
    );
    PRINT 'V75: created candidate_interview_interviewers';
END
ELSE
    PRINT 'V75: candidate_interview_interviewers already present';
GO

-- One row per (interview, user): the panel is a set, not a list. savePanel() relies on
-- this index — it replaces the panel wholesale and flushes the deletes first precisely
-- so re-adding the same interviewer cannot collide here.
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'UX_InterviewInterviewer_InterviewUser'
                 AND object_id = OBJECT_ID('dbo.candidate_interview_interviewers'))
BEGIN
    CREATE UNIQUE INDEX [UX_InterviewInterviewer_InterviewUser]
        ON [dbo].[candidate_interview_interviewers] ([interview_id], [user_id]);
    PRINT 'V75: created UX_InterviewInterviewer_InterviewUser';
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'IX_InterviewInterviewer_User'
                 AND object_id = OBJECT_ID('dbo.candidate_interview_interviewers'))
BEGIN
    CREATE INDEX [IX_InterviewInterviewer_User]
        ON [dbo].[candidate_interview_interviewers] ([user_id]);
    PRINT 'V75: created IX_InterviewInterviewer_User';
END
GO

-- ── 3. Backfill — V73's step, now that the column it reads exists ────────────
-- Every pre-panel interviewer becomes a panel of one. A no-op on a fresh column
-- (all NULL) and on a database where V73 already ran.
INSERT INTO [dbo].[candidate_interview_interviewers] ([interview_id], [user_id])
SELECT ci.[id], ci.[interviewer_user_id]
FROM [dbo].[candidate_interviews] ci
WHERE ci.[interviewer_user_id] IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[candidate_interview_interviewers] p
      WHERE p.[interview_id] = ci.[id] AND p.[user_id] = ci.[interviewer_user_id]
  );
PRINT CONCAT('V75: backfilled ', @@ROWCOUNT, ' panel row(s) from the lead column');
GO

-- ── 4. Verification ──────────────────────────────────────────────────────────
SELECT COL_LENGTH('dbo.candidate_interviews', 'interviewer_user_id') AS lead_col_bytes,
       OBJECT_ID('dbo.candidate_interview_interviewers')             AS panel_table_id,
       (SELECT COUNT(*) FROM [dbo].[candidate_interview_interviewers]) AS panel_rows;
GO

PRINT 'V75 applied.';
GO
