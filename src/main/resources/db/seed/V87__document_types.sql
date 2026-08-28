-- =============================================================================
-- V87__document_types.sql
-- Rend le vocabulaire des types de document ET leur destination SharePoint
-- modifiables en base, par pays.
--
-- Remplace deux constantes Java qui devaient etre editees ensemble puis
-- redeployees pour ajouter un seul type :
--   - EmployeeDocumentService.DOCUMENT_TYPES  (le vocabulaire accepte)
--   - DocumentFolderMapping.BY_TYPE           (le sous-dossier, code en dur)
-- Aucune des deux n'est supprimee ici : le service retombe sur elles quand un
-- pays n'a aucune ligne, parce que rh-service n'a pas Flyway et qu'un serveur
-- ou ce script n'a pas ete applique doit degrader, pas refuser tout upload.
--
-- ── POURQUOI PAR PAYS ────────────────────────────────────────────────────────
-- La CNSS est tunisienne, et l'Egypte n'a pas d'equivalent au meme endroit de
-- l'arborescence. Un vocabulaire global obligeait donc a proposer a l'Egypte des
-- types qui n'y menent nulle part. La cle est (pays_id, code) : le meme code peut
-- exister pour un pays et pas pour l'autre, avec des libelles differents.
--
-- employee_documents.document_type reste le code SEUL, sans pays : le profil
-- porte deja son pays_id, donc la paire est reconstituable. Aucune migration de
-- donnees existantes n'est necessaire.
--
-- ── POURQUOI LE CHEMIN VIT DANS sharepoint_locations ─────────────────────────
-- V84 a cree (pays_id, doc_kind, path_template) et l'onglet d'administration
-- l'edite deja. Un type de document EST un doc_kind de ce point de vue, donc les
-- chemins des documents deviennent editables sans nouvel ecran. V84 tolere
-- explicitement un doc_kind absent de l'enum Java ("une valeur absente de l'enum
-- est ignoree a la lecture"), ce qui est exactement ce cas.
--
-- ── LIBELLES EN BASE, PAS EN i18n ────────────────────────────────────────────
-- label_fr/label_en sont des colonnes et non des cles PROFILES.DOC_TYPES.*, sinon
-- ajouter un type imposerait encore un deploiement du frontend — ce qui annulerait
-- l'interet de la table. Le frontend retombe sur le code brut si un libelle manque.
--
-- Idempotente : creation gardee par IF NOT EXISTS, seeds gardes par NOT EXISTS sur
-- la ligne. Reexecuter ne reecrase jamais un libelle ni un chemin edite a la main.
-- =============================================================================

-- ── 1. Le vocabulaire, par pays ──────────────────────────────────────────────
IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME = 'document_types'
)
CREATE TABLE [dbo].[document_types] (
    [id]         BIGINT         IDENTITY(1,1) NOT NULL,
    [pays_id]    BIGINT         NOT NULL,
    -- 100 comme employee_documents.document_type : la colonne qui stocke la valeur
    -- dicte la longueur, sinon un code acceptable ici serait tronque a l'upload.
    [code]       VARCHAR(100)   NOT NULL,
    [label_fr]   NVARCHAR(120)  NOT NULL,
    [label_en]   NVARCHAR(120)  NULL,
    [is_active]  BIT            NOT NULL CONSTRAINT [DF_doc_types_is_active] DEFAULT (1),
    [sort_order] INT            NOT NULL CONSTRAINT [DF_doc_types_sort]      DEFAULT (100),
    [updated_at] DATETIMEOFFSET NULL,
    [updated_by] BIGINT         NULL,
    CONSTRAINT [PK_document_types]      PRIMARY KEY ([id]),
    CONSTRAINT [UQ_doc_types_pays_code] UNIQUE ([pays_id], [code]),
    CONSTRAINT [FK_doc_types_pays]      FOREIGN KEY ([pays_id])
        REFERENCES [dbo].[pays] ([id])
);
GO

-- ── 2. Seed du vocabulaire existant ──────────────────────────────────────────
-- Les 16 codes de EmployeeDocumentService.DOCUMENT_TYPES, avec les libelles
-- francais actuellement dans PROFILES.DOC_TYPES (fr.json) — a l'identique, pour
-- que rien ne change visuellement le jour de l'application.
--
-- Seuls les pays QUI ONT DES EMPLOYES sont seedes (TN, EG). Un pays sans ligne
-- retombe sur la liste Java, donc ne pas le seeder ne casse rien ; le seeder
-- l'aurait dote d'une arborescence inventee, ce que V82/V84 refusent deja de faire.
INSERT INTO [dbo].[document_types] ([pays_id], [code], [label_fr], [label_en], [sort_order], [updated_at])
SELECT p.[id], v.[code], v.[label_fr], v.[label_en], v.[sort_order], SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    ('CONTRACT',            N'Contrat de travail',   N'Employment contract',   10),
    ('CONTRACT_SIGNED',     N'Contrat signé',        N'Signed contract',       20),
    ('AMENDMENT',           N'Avenant',              N'Amendment',             30),
    ('ID_CARD',             N'CIN',                  N'ID card',               40),
    ('PASSPORT',            N'Passeport',            N'Passport',              50),
    ('RESIDENCE_PERMIT',    N'Titre de séjour',      N'Residence permit',      60),
    ('DIPLOMA',             N'Diplôme',              N'Diploma',               70),
    ('CV',                  N'CV',                   N'CV',                    80),
    ('MEDICAL_CERTIFICATE', N'Certificat médical',   N'Medical certificate',   90),
    ('RIB',                 N'RIB bancaire',         N'Bank details',         100),
    ('CNSS',                N'CNSS',                 N'CNSS',                 110),
    ('TAX_FORM',            N'Document fiscal',      N'Tax form',             120),
    ('PHOTO',               N'Photo d''identité',    N'ID photo',             130),
    ('RESIGNATION',         N'Lettre de démission',  N'Resignation letter',   140),
    ('DISCHARGE',           N'Décharge',             N'Discharge',            150),
    ('OTHER',               N'Autre',                N'Other',                160)
) AS v ([code], [label_fr], [label_en], [sort_order])
WHERE p.[iso_code] IN ('TN', 'EG')
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[document_types] dt
      WHERE dt.[pays_id] = p.[id] AND dt.[code] = v.[code]
  );
GO

-- ── 3. Seed des chemins, repris de DocumentFolderMapping ─────────────────────
-- La base employe est calculee comme SharePointPaths.employeeFolderBase le fait en
-- Java : pays.photo_sharepoint_location coupe APRES {employeeFolder}. C'est la
-- meme entree que le code utilise aujourd'hui, donc les chemins produits sont
-- identiques a ceux vers lesquels les documents partent deja.
--
-- Un pays dont photo_sharepoint_location ne contient pas le jeton est ignore
-- (CHARINDEX = 0) : sans base fiable, NO_CONFIG vaut mieux qu'un chemin devine.
--
-- "Administrative Documents" est le DEFAULT_SUBFOLDER de la carte Java, cree a la
-- demande par le mkdir -p de l'upload. Les trois autres dossiers existent deja.
INSERT INTO [dbo].[sharepoint_locations] ([pays_id], [doc_kind], [path_template], [is_active], [updated_at])
SELECT p.[id], m.[code],
       LEFT(p.[photo_sharepoint_location],
            CHARINDEX('{employeeFolder}', p.[photo_sharepoint_location]) + LEN('{employeeFolder}') - 1)
       + '/' + m.[subfolder],
       1, SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
CROSS JOIN (VALUES
    -- Tout ce qui modifie la relation de travail, y compris sa fin.
    ('CONTRACT',            N'Employment Contracts & Amendments'),
    ('CONTRACT_SIGNED',     N'Employment Contracts & Amendments'),
    ('AMENDMENT',           N'Employment Contracts & Amendments'),
    ('RESIGNATION',         N'Employment Contracts & Amendments'),
    ('DISCHARGE',           N'Employment Contracts & Amendments'),
    -- Qui est la personne. PHOTO les rejoint : la photo de profil s'y miroite deja.
    ('ID_CARD',             N'Identity Documents'),
    ('PASSPORT',            N'Identity Documents'),
    ('RESIDENCE_PERMIT',    N'Identity Documents'),
    ('PHOTO',               N'Identity Documents'),
    -- Un arret de travail justifie une absence : il se classe avec les conges.
    ('MEDICAL_CERTIFICATE', N'Time Off & Leaves'),
    -- Les cinq sans foyer naturel + OTHER, dans le dossier que l'app cree elle-meme.
    ('DIPLOMA',             N'Administrative Documents'),
    ('CV',                  N'Administrative Documents'),
    ('RIB',                 N'Administrative Documents'),
    ('CNSS',                N'Administrative Documents'),
    ('TAX_FORM',            N'Administrative Documents'),
    ('OTHER',               N'Administrative Documents')
) AS m ([code], [subfolder])
WHERE p.[iso_code] IN ('TN', 'EG')
  AND p.[photo_sharepoint_location] IS NOT NULL
  AND CHARINDEX('{employeeFolder}', p.[photo_sharepoint_location]) > 0
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[sharepoint_locations] l
      WHERE l.[pays_id] = p.[id] AND l.[doc_kind] = m.[code]
  );
GO

-- ── Verification ─────────────────────────────────────────────────────────────
-- Les chemins produits, a comparer avec l'arborescence reelle :
--
-- SELECT p.iso_code, l.doc_kind, dt.label_fr, l.path_template
-- FROM sharepoint_locations l
-- JOIN pays p ON p.id = l.pays_id
-- LEFT JOIN document_types dt ON dt.pays_id = l.pays_id AND dt.code = l.doc_kind
-- WHERE l.doc_kind NOT IN ('PHOTO', 'PAYSLIP', 'SALARY_CERTIFICATE')
-- ORDER BY p.iso_code, dt.sort_order;
--
-- ⚠ SEUL CHANGEMENT DE COMPORTEMENT DE CE SCRIPT — le type de document PHOTO.
-- Le code 'PHOTO' existe des deux cotes : c'est un DocKind (la photo de profil,
-- ligne creee par V84) ET un type de document uploadable. La cle de
-- sharepoint_locations etant (pays_id, doc_kind), ils PARTAGENT une ligne, et le
-- garde NOT EXISTS ci-dessus laisse gagner celle de V84.
--
-- Consequence concrete : une photo deposee via l'onglet Documents part desormais
-- dans le chemin PHOTO de V84 (aujourd'hui ".../Identity Documents/Profile Photo"
-- pour la Tunisie) au lieu de ".../Identity Documents" que donnait
-- DocumentFolderMapping. C'est le meme dossier que la photo de profil, donc les
-- deux chemins d'arrivee d'une photo convergent enfin — mais c'est bien un
-- deplacement, pas une equivalence, et les fichiers deja deposes restent ou ils
-- sont (rien ne persiste le dossier resolu).
--
-- Pour l'eviter : renommer le type de document en 'ID_PHOTO' dans le seed 2 et
-- lui donner sa propre ligne de chemin. Tous les autres types sont strictement
-- iso-comportement.
