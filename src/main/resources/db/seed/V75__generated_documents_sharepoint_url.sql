-- =============================================================================
-- V75__generated_documents_sharepoint_url.sql
-- Trace, pour chaque document généré, le lien SharePoint obtenu si l'upload a
-- réussi (cf. GraphSharePointService / PdfDocumentService.saveGeneratedDocument).
--
-- NULL est la valeur attendue et normale pour :
--   - toute maquette sans sharepoint_location configuré (document_templates),
--   - tout document dont l'employé n'a pas de prénom/nom exploitable,
--   - un dossier employé ambigu (plusieurs employés au même nom, cf.
--     hasAmbiguousEmployeeFolder) — dépôt refusé par sécurité,
--   - SharePoint non configuré (tenant/client id/secret vides),
--   - tout échec réseau/auth/permissions côté Microsoft Graph.
-- Dans tous ces cas la copie locale (file_url) reste la référence faisant foi ;
-- ce champ n'est qu'un lien additionnel, jamais une dépendance bloquante.
--
-- Idempotente : ADD gardé par IF NOT EXISTS.
-- =============================================================================

IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_NAME = 'generated_documents' AND COLUMN_NAME = 'sharepoint_url'
)
    ALTER TABLE [dbo].[generated_documents] ADD [sharepoint_url] NVARCHAR(1000) NULL;
GO
