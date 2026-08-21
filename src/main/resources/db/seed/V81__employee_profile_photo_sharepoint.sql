-- =============================================================================
-- V81__employee_profile_photo_sharepoint.sql
-- Colonnes nécessaires pour mirorer la photo de profil employé vers SharePoint
-- (cf. docs/superpowers/specs/2026-08-18-profile-photo-sharepoint-design.md).
--
-- pays.photo_sharepoint_location : même convention que document_templates.
-- sharepoint_location ({employeeFolder} = espace réservé littéral, résolu au
-- moment de l'upload/du téléchargement, PAS un jeton Handlebars). Valeur par
-- défaut posée UNIQUEMENT pour la Tunisie — seule structure SharePoint réelle
-- confirmée à ce jour. Le sous-dossier "Identity Documents" est PROVISOIRE :
-- pas encore confirmé contre l'arborescence réelle (cf. mémoire
-- project-sharepoint-architecture) — à corriger ici si le nom réel diffère.
-- Tous les autres pays restent NULL : pas de structure réelle connue pour eux,
-- mieux vaut vide que deviné (même politique que V74).
--
-- employee_profiles.photo_sharepoint_url : trace du lien SharePoint obtenu si
-- l'upload a réussi — même rôle que generated_documents.sharepoint_url. NULL
-- est la valeur normale et attendue tant que SharePoint n'est pas configuré,
-- que le pays n'a pas de photo_sharepoint_location, ou que le nom d'employé
-- est ambigu.
--
-- Idempotente : ADD gardé par IF NOT EXISTS ; l'UPDATE ne touche que les
-- lignes encore NULL (ne réécrase jamais une valeur déjà éditée à la main).
-- =============================================================================

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'pays' AND COLUMN_NAME = 'photo_sharepoint_location'
)
    ALTER TABLE [dbo].[pays] ADD [photo_sharepoint_location] NVARCHAR(500) NULL;
GO

UPDATE [dbo].[pays]
SET photo_sharepoint_location = 'Tunisia/01_HR/01_Contracts-Employment/{employeeFolder}/Identity Documents'
WHERE iso_code = 'TN' AND photo_sharepoint_location IS NULL;
GO

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'employee_profiles' AND COLUMN_NAME = 'photo_sharepoint_url'
)
    ALTER TABLE [dbo].[employee_profiles] ADD [photo_sharepoint_url] NVARCHAR(1000) NULL;
GO
