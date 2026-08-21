-- =============================================================================
-- V78__document_templates_sharepoint_location.sql
-- Emplacement SharePoint éditable par maquette — préparation pour l'intégration
-- SharePoint (pas encore câblée : aucun appel Graph API n'est fait aujourd'hui,
-- ce champ est purement stocké/affiché dans l'écran d'admin des maquettes en
-- attendant les identifiants SharePoint/Azure AD).
--
-- {employeeFolder} est un espace réservé littéral (PAS un jeton Handlebars comme
-- {{clé}} dans html_content) : il sera résolu au moment de l'enregistrement réel,
-- une fois l'intégration SharePoint construite, en le remplaçant par le nom du
-- dossier employé ("Prénom NOM").
--
-- Valeur par défaut posée UNIQUEMENT pour la Tunisie : c'est la seule structure
-- SharePoint réelle confirmée à ce jour (arborescence donnée par l'utilisateur
-- 2026-08-16, cf. mémoire project-sharepoint-architecture). Les autres pays
-- restent NULL — pas de structure réelle connue pour eux, mieux vaut vide que
-- deviné.
--
-- Idempotente : ADD gardé par IF NOT EXISTS ; l'UPDATE ne touche que les lignes
-- encore NULL (ne réécrase jamais une valeur déjà éditée par un admin).
-- =============================================================================

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'document_templates' AND COLUMN_NAME = 'sharepoint_location'
)
    ALTER TABLE [dbo].[document_templates] ADD [sharepoint_location] NVARCHAR(500) NULL;
GO

UPDATE dt
SET dt.sharepoint_location = '01_HR/01_Contracts-Employment/{employeeFolder}/HR Requests'
FROM [dbo].[document_templates] dt
JOIN [dbo].[pays] p ON p.id = dt.pays_id
WHERE p.iso_code = 'TN' AND dt.sharepoint_location IS NULL;
GO
