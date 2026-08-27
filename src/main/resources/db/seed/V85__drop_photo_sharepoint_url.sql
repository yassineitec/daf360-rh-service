-- =============================================================================
-- V85__drop_photo_sharepoint_url.sql
-- Supprime employee_profiles.photo_sharepoint_url : colonne ECRITE mais JAMAIS LUE.
--
-- Constat sur l'environnement de test (2026-08-26) :
--   SELECT COUNT(user_id), photo_sharepoint_url FROM employee_profiles
--   GROUP BY photo_sharepoint_url;   ->   101 | NULL
--
-- Un seul groupe, NULL partout. La colonne n'etait renseignee que par un upload
-- de photo VIA L'APPLICATION (EmployeeProfileService.mirrorPhotoToSharePoint), et
-- personne n'avait televerse de photo par ce chemin : les fichiers ont ete deposes
-- a la main dans SharePoint. Et surtout, servePhoto() ne l'a JAMAIS lue — elle
-- recalculait le chemin. La photo d'un employe s'affichait donc parfaitement avec
-- la colonne a NULL, ce qui a ete verifie sur les profils 32 et 12.
--
-- Autrement dit : donnee derivee qui se faisait passer pour une source de verite.
-- Le chemin est desormais recalcule par SharePointResolver (V84) et le vrai segment
-- de dossier est memorise dans employee_sharepoint_folders, qui est explicitement
-- un cache.
--
-- generated_documents.sharepoint_url est CONSERVEE : meme si aucun frontend ne la
-- lit, elle trace le depot d'un document legal genere, ce qui a une valeur d'audit
-- que la photo de profil n'a pas.
--
-- Idempotente : le DROP est garde par IF EXISTS, et la contrainte par defaut
-- eventuelle est supprimee avant (SQL Server refuse de supprimer une colonne
-- portant encore un DEFAULT).
-- =============================================================================

DECLARE @constraint SYSNAME = (
    SELECT dc.name
    FROM sys.default_constraints dc
    JOIN sys.columns c ON c.object_id = dc.parent_object_id
                      AND c.column_id = dc.parent_column_id
    WHERE dc.parent_object_id = OBJECT_ID(N'[dbo].[employee_profiles]')
      AND c.name = 'photo_sharepoint_url'
);
IF @constraint IS NOT NULL
    EXEC(N'ALTER TABLE [dbo].[employee_profiles] DROP CONSTRAINT [' + @constraint + N']');
GO

IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'employee_profiles'
      AND COLUMN_NAME = 'photo_sharepoint_url'
)
    ALTER TABLE [dbo].[employee_profiles] DROP COLUMN [photo_sharepoint_url];
GO

-- ── Verification ─────────────────────────────────────────────────────────────
-- SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
-- WHERE TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'photo_sharepoint_url';
-- (doit ne rien retourner)
