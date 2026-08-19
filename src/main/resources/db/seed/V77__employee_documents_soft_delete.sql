-- ============================================================
-- V77 — employee_documents : suppression logique + amorce de stockage externe
--
-- Pourquoi : l'onglet Documents du profil n'avait aucun moyen de retirer une
-- pièce. Une suppression physique effacerait la trace qu'un document a existé,
-- alors que c'est exactement ce qu'un dossier RH doit pouvoir montrer (qui a
-- déposé quoi, quand, et qui l'a retiré). D'où une suppression LOGIQUE.
--
-- Les deux dernières colonnes (storage_provider / external_id) sont la couture
-- pour SharePoint. L'intégration N'EXISTE PAS aujourd'hui : le seul champ qui la
-- mentionne est `sharepointLocation` sur les maquettes de documents, saisi côté
-- Angular et jamais persisté (aucune colonne, aucune propriété d'entité) — son
-- libellé dit lui-même « pas encore actif ». Poser ces deux colonnes maintenant
-- coûte une migration triviale ; les ajouter plus tard coûterait une migration
-- sous pression, avec des lignes déjà en production à qualifier.
--   . storage_provider = 'LOCAL'      → file_url est un chemin sur le disque du service
--   . storage_provider = 'SHAREPOINT' → file_url portera la webUrl Graph, external_id
--                                       l'identifiant driveItem
--
-- ATTENTION : ddl-auto est none. EmployeeDocument mappe ces colonnes : la liste
-- des documents renvoie 500 tant que ce script n'est pas appliqué.
--
--   sqlcmd -S <server> -d DAF360_HR -i V77__employee_documents_soft_delete.sql
-- Re-jouable : chaque colonne est gardée par COL_LENGTH.
-- Created: 2026-08-18
-- ============================================================

IF COL_LENGTH('dbo.employee_documents', 'is_deleted') IS NULL
  ALTER TABLE [dbo].[employee_documents]
    ADD [is_deleted] BIT NOT NULL
      CONSTRAINT [DF_emp_docs_is_deleted] DEFAULT 0;
GO

IF COL_LENGTH('dbo.employee_documents', 'deleted_at') IS NULL
  ALTER TABLE [dbo].[employee_documents] ADD [deleted_at] DATETIMEOFFSET(6) NULL;
GO

IF COL_LENGTH('dbo.employee_documents', 'deleted_by') IS NULL
  ALTER TABLE [dbo].[employee_documents] ADD [deleted_by] BIGINT NULL;
GO

-- 'LOCAL' pour tout l'existant : c'est bien où sont les fichiers aujourd'hui
-- (<STORAGE_PATH>/hr/<profileId>/<uuid>.<ext>).
IF COL_LENGTH('dbo.employee_documents', 'storage_provider') IS NULL
  ALTER TABLE [dbo].[employee_documents]
    ADD [storage_provider] NVARCHAR(20) NOT NULL
      CONSTRAINT [DF_emp_docs_storage] DEFAULT 'LOCAL';
GO

IF COL_LENGTH('dbo.employee_documents', 'external_id') IS NULL
  ALTER TABLE [dbo].[employee_documents] ADD [external_id] NVARCHAR(255) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_emp_docs_storage')
  ALTER TABLE [dbo].[employee_documents]
    ADD CONSTRAINT [CK_emp_docs_storage] CHECK ([storage_provider] IN ('LOCAL','SHAREPOINT'));
GO

-- Cohérence : une ligne supprimée porte une date, une ligne vivante n'en porte pas.
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_emp_docs_deleted')
  ALTER TABLE [dbo].[employee_documents]
    ADD CONSTRAINT [CK_emp_docs_deleted] CHECK (
        ([is_deleted] = 0 AND [deleted_at] IS NULL)
     OR ([is_deleted] = 1 AND [deleted_at] IS NOT NULL));
GO

-- La liste de l'onglet filtre is_deleted = 0 sur chaque lecture : l'index le sert.
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'IX_emp_docs_profile_live'
                 AND object_id = OBJECT_ID('dbo.employee_documents'))
  CREATE NONCLUSTERED INDEX [IX_emp_docs_profile_live]
    ON [dbo].[employee_documents]([employee_profile_id])
    INCLUDE ([document_type], [verification_status], [uploaded_at])
    WHERE [is_deleted] = 0;
GO

/*
 * Note sur les types de document : `document_type` reste un NVARCHAR libre, sans
 * contrainte. Trois vocabulaires coexistent dans le code (la liste du select de
 * l'onglet, les libellés de l'assistant d'onboarding, et 'CONTRACT_SIGNED' écrit
 * par OnboardingService.linkContractDocument). Le service valide désormais la
 * valeur contre un catalogue unique côté Java ; le CHECK n'est PAS posé ici
 * exprès, pour ne pas faire échouer l'application du script sur des lignes
 * historiques portant un ancien code.
 *
 * Pour voir ce qui existe réellement en base avant d'envisager un CHECK :
 *   SELECT document_type, COUNT(*) FROM employee_documents GROUP BY document_type;
 */

SELECT  [storage_provider],
        [is_deleted],
        COUNT(*) AS lignes
FROM    [dbo].[employee_documents]
GROUP BY [storage_provider], [is_deleted];
GO
