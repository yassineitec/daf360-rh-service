-- V73: Multiple interviewers per candidate interview
-- candidate_interviews.interviewer_user_id becomes the "lead" interviewer (kept for
-- backward compatibility and for the earliest indexes/queries); the full panel lives
-- in candidate_interview_interviewers, backfilled from the existing single column.

CREATE TABLE [dbo].[candidate_interview_interviewers] (
    [id]           BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [interview_id] BIGINT NOT NULL,
    [user_id]      BIGINT NOT NULL,
    CONSTRAINT [FK_InterviewInterviewer_Interview]
        FOREIGN KEY ([interview_id]) REFERENCES [dbo].[candidate_interviews]([id])
        ON DELETE CASCADE
);
GO

-- One row per (interview, user): the panel is a set, not a list.
CREATE UNIQUE INDEX [UX_InterviewInterviewer_InterviewUser]
    ON [dbo].[candidate_interview_interviewers] ([interview_id], [user_id]);
GO

-- Lookup by user drives the "my interviews" calendar feed.
CREATE INDEX [IX_InterviewInterviewer_User]
    ON [dbo].[candidate_interview_interviewers] ([user_id]);
GO

-- ── Backfill: every existing single interviewer becomes a panel of one ────────
INSERT INTO [dbo].[candidate_interview_interviewers] ([interview_id], [user_id])
SELECT ci.[id], ci.[interviewer_user_id]
FROM [dbo].[candidate_interviews] ci
WHERE ci.[interviewer_user_id] IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[candidate_interview_interviewers] p
      WHERE p.[interview_id] = ci.[id] AND p.[user_id] = ci.[interviewer_user_id]
  );
GO
