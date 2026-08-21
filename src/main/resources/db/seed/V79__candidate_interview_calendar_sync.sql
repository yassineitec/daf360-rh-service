-- =============================================================================
-- V79__candidate_interview_calendar_sync.sql
-- Ajoute le suivi de synchronisation Outlook/Teams pour les entretiens candidat
-- (cf. docs/superpowers/specs/2026-08-19-interview-calendar-sync-design.md).
--
-- Trois colonnes nullable, additives — un entretien reste utilisable normalement
-- même si la synchronisation calendrier échoue ou n'est pas configurée :
--   - graph_event_id       : id de l'événement Graph, pour PATCH/annulation ultérieurs
--   - graph_organizer_email: sous quelle boîte mail l'événement existe réellement —
--                            peut diverger de l'intervieweur principal ACTUEL après
--                            une modification (c'est justement pourquoi on la trace
--                            séparément), un événement Graph ne peut pas changer
--                            d'organisateur après création
--   - graph_join_url       : lien Teams, affiché directement dans l'app
--
-- Idempotente : ADD gardé par IF NOT EXISTS.
-- =============================================================================

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'candidate_interviews' AND COLUMN_NAME = 'graph_event_id'
)
    ALTER TABLE [dbo].[candidate_interviews] ADD [graph_event_id] NVARCHAR(255) NULL;
GO

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'candidate_interviews' AND COLUMN_NAME = 'graph_organizer_email'
)
    ALTER TABLE [dbo].[candidate_interviews] ADD [graph_organizer_email] NVARCHAR(255) NULL;
GO

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'candidate_interviews' AND COLUMN_NAME = 'graph_join_url'
)
    ALTER TABLE [dbo].[candidate_interviews] ADD [graph_join_url] NVARCHAR(1000) NULL;
GO
