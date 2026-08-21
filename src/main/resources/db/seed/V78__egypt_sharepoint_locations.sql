-- =============================================================================
-- V78__egypt_sharepoint_locations.sql
-- Wire Egypt into the SharePoint integration built for Tunisia (V74/V76 for
-- document generation, V77 for profile photos) — same {employeeFolder}
-- placeholder convention, same folder-resolution code (Users.fullName-based),
-- no application code changes needed, only the per-pays destination strings.
--
-- Confirmed live via Microsoft Graph (2026-08-18) before writing this migration:
-- the real Egypt tree root is EGY/01_HR/01_Contracts-Employment/{employeeFolder}/
-- (NOT the EMP000001_FirstName_LastName / discipline-subfolder convention noted
-- in an earlier exploratory session — that structure has since been reorganized
-- to match Tunisia's: plain "Prénom NOM" folders, same 4 subfolders per employee
-- — Employment Contracts & Amendments / Evaluations & Performance Reviews /
-- HR Requests / Time Off & Leaves). 130 real employee folders confirmed present.
-- No "Identity Documents" subfolder exists yet there either — same as Tunisia
-- before V77, it will auto-create on first photo upload via the existing
-- mkdir-p logic in GraphSharePointService.
--
-- A DB-vs-real-folder audit (95 DB employees vs 130 real folders) found 6 name
-- mismatches (e.g. DB "Ahmed Ossama" vs real folder "Ahmed OSAMA") — logged in
-- project memory, NOT auto-corrected here, same "never guess" policy as the
-- Tunisia rollout's own near-misses.
--
-- Idempotente : l'UPDATE ne touche que les lignes encore NULL.
-- =============================================================================

UPDATE dt
SET dt.sharepoint_location = 'EGY/01_HR/01_Contracts-Employment/{employeeFolder}/HR Requests'
FROM [dbo].[document_templates] dt
JOIN [dbo].[pays] p ON p.id = dt.pays_id
WHERE p.iso_code = 'EG' AND dt.sharepoint_location IS NULL;
GO

UPDATE [dbo].[pays]
SET photo_sharepoint_location = 'EGY/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents'
WHERE iso_code = 'EG' AND photo_sharepoint_location IS NULL;
GO
