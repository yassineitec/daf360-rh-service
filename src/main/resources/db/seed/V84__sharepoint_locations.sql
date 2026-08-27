-- =============================================================================
-- V84__sharepoint_locations.sql
-- Modele unifie des chemins SharePoint : une configuration par (pays, type de
-- document) + un cache de resolution par (employe, type de document).
--
-- Remplace a terme les trois emplacements ad-hoc actuels :
--   - pays.photo_sharepoint_location            (photo de profil)
--   - document_templates.sharepoint_location    (documents generes)
--   - DocumentFolderMapping (carte Java codee en dur, donc un redeploiement
--     pour deplacer un dossier)
-- Aucun n'est supprime ici : la phase A n'a pas le droit de casser l'existant.
-- SharePointLocationService lit d'abord sharepoint_locations et retombe sur
-- pays.photo_sharepoint_location pour PHOTO, le temps d'une release — rh-service
-- n'a pas Flyway, donc un serveur ou ce script n'a pas ete applique doit
-- degrader, pas tomber.
--
-- doc_kind : vocabulaire porte par l'enum Java DocKind. Une valeur absente de
-- l'enum est ignoree a la lecture (jamais une erreur) — on peut donc preparer
-- une ligne en base avant que le code qui la consomme existe.
--
-- path_template : jetons litteraux, PAS du Handlebars.
--   {employeeFolder}  obligatoire — le dossier de l'employe, convention
--                     "Prenom NOM" (prenom capitalise, nom en majuscules),
--                     c'est-a-dire le format de Users.fullName
--   {year}            uniquement pour les types annuels (cf. DocKind)
--
-- Idempotente : creations gardees par IF NOT EXISTS, seeds gardes par NOT EXISTS
-- sur la ligne — reexecuter ce script ne reecrase jamais un chemin edite a la
-- main depuis l'admin.
-- =============================================================================

-- ── 1. Configuration : un chemin par (pays, type de document) ────────────────
IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'sharepoint_locations'
)
CREATE TABLE [dbo].[sharepoint_locations] (
    [id]            BIGINT         IDENTITY(1,1) NOT NULL,
    [pays_id]       BIGINT         NOT NULL,
    [doc_kind]      VARCHAR(40)    NOT NULL,
    [path_template] NVARCHAR(500)  NOT NULL,
    [is_active]     BIT            NOT NULL CONSTRAINT [DF_sp_loc_is_active] DEFAULT (1),
    [updated_at]    DATETIMEOFFSET NULL,
    [updated_by]    BIGINT         NULL,
    CONSTRAINT [PK_sharepoint_locations]  PRIMARY KEY ([id]),
    CONSTRAINT [UQ_sp_loc_pays_kind]      UNIQUE ([pays_id], [doc_kind]),
    CONSTRAINT [FK_sp_loc_pays]           FOREIGN KEY ([pays_id])
        REFERENCES [dbo].[pays] ([id])
);
GO

-- ── 2. Cache de resolution : le vrai segment de dossier de chaque employe ────
-- Table VOLONTAIREMENT creuse : pas de ligne pour les employes qui se resolvent
-- du premier coup. Une ligne existe soit parce qu'une decouverte a abouti
-- (source = DISCOVERED), soit parce qu'un administrateur a corrige un dossier
-- que la convention ne permet pas de deviner (source = MANUAL).
--
-- MANUAL n'est JAMAIS ecrase par une decouverte : sans cette regle, une
-- correction saisie a la main disparait au prochain rafraichissement du cache
-- et le meme incident se rejoue.
--
-- status/last_error servent aussi de cache NEGATIF : un echec est memorise avec
-- sa raison et sa date, ce qui evite de rejouer ~5 appels Graph a chaque rendu
-- d'avatar pour un employe qui n'a pas de photo.
IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'employee_sharepoint_folders'
)
CREATE TABLE [dbo].[employee_sharepoint_folders] (
    [employee_profile_id] BIGINT         NOT NULL,
    [doc_kind]            VARCHAR(40)    NOT NULL,
    [folder_segment]      NVARCHAR(255)  NULL,
    [source]              VARCHAR(16)    NOT NULL,
    [status]              VARCHAR(24)    NOT NULL,
    [resolved_at]         DATETIMEOFFSET NOT NULL,
    [last_error]          NVARCHAR(500)  NULL,
    CONSTRAINT [PK_emp_sp_folders] PRIMARY KEY ([employee_profile_id], [doc_kind]),
    CONSTRAINT [CK_emp_sp_folders_source] CHECK ([source] IN ('DISCOVERED','MANUAL')),
    CONSTRAINT [FK_emp_sp_folders_profile] FOREIGN KEY ([employee_profile_id])
        REFERENCES [dbo].[employee_profiles] ([id])
);
GO

-- ── 3. Seed PHOTO : copie exacte de la configuration actuelle ────────────────
-- Reprend pays.photo_sharepoint_location tel quel, pour TOUS les pays qui en
-- ont un. Objectif : apres application de ce script, le comportement observable
-- est identique au comportement actuel. Aucun chemin n'est devine ici.
INSERT INTO [dbo].[sharepoint_locations] ([pays_id], [doc_kind], [path_template], [is_active], [updated_at])
SELECT p.[id], 'PHOTO', p.[photo_sharepoint_location], 1, SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
WHERE p.[photo_sharepoint_location] IS NOT NULL
  AND LTRIM(RTRIM(p.[photo_sharepoint_location])) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[sharepoint_locations] l
      WHERE l.[pays_id] = p.[id] AND l.[doc_kind] = 'PHOTO'
  );
GO

-- ── 4. Seed Paie : Tunisie uniquement ────────────────────────────────────────
-- Arborescence cible donnee par le metier (2026-08-26) :
--   Tunisia/01_HR/03_Payroll-Admin/{employeeFolder}/01_Pay-Slip/{annee}/
--       FirstName_LASTNAME_Month_Year.PDF
--   Tunisia/01_HR/03_Payroll-Admin/{employeeFolder}/02_Salary-Certificate/
--
-- Ces dossiers N'EXISTENT PAS ENCORE sur le site : c'est la cible, pas l'etat.
-- Consequence assumee : le resolveur repond FOLDER_MISSING pour ces deux types
-- jusqu'a leur creation, ce qui est un degrade propre (liste vide cote employe)
-- et non une erreur.
--
-- Les autres pays restent sans ligne : aucune arborescence de paie connue pour
-- eux, et NO_CONFIG vaut mieux qu'un chemin devine (meme politique que V81).
INSERT INTO [dbo].[sharepoint_locations] ([pays_id], [doc_kind], [path_template], [is_active], [updated_at])
SELECT p.[id], v.[doc_kind], v.[path_template], 1, SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('PAYSLIP',            N'Tunisia/01_HR/03_Payroll-Admin/{employeeFolder}/01_Pay-Slip/{year}'),
    ('SALARY_CERTIFICATE', N'Tunisia/01_HR/03_Payroll-Admin/{employeeFolder}/02_Salary-Certificate')
) AS v ([doc_kind], [path_template])
WHERE p.[iso_code] = 'TN'
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[sharepoint_locations] l
      WHERE l.[pays_id] = p.[id] AND l.[doc_kind] = v.[doc_kind]
  );
GO

-- ── Verification ─────────────────────────────────────────────────────────────
-- SELECT p.iso_code, l.doc_kind, l.path_template
-- FROM sharepoint_locations l JOIN pays p ON p.id = l.pays_id
-- ORDER BY p.iso_code, l.doc_kind;
