-- ─────────────────────────────────────────────────────────────────────────────
-- Types de demande : description anglaise (request_type_catalog.description_en)
--
-- La description n'existait qu'en français (colonne `description`) : en interface anglaise,
-- /rh/admin?tab=request-types affichait donc la description française. Ajoute la colonne
-- (facultative — vide, l'interface retombe sur la description française) et la remplit pour
-- les 13 types par défaut (RequestTypeCatalogService.DEFAULT_TYPES).
--
-- Le remplissage ne touche une ligne que si sa description française est encore le texte
-- d'origine : une description réécrite à la main ne reçoit pas une traduction qui ne lui
-- correspondrait plus.
--
-- Idempotent. À appliquer à la main (pas de Flyway), AVANT de déployer le backend.
-- ─────────────────────────────────────────────────────────────────────────────
SET XACT_ABORT ON;
SET NOCOUNT ON;

IF COL_LENGTH('dbo.request_type_catalog', 'description_en') IS NULL
BEGIN
    ALTER TABLE dbo.request_type_catalog ADD description_en nvarchar(500) NULL;
    PRINT 'Colonne description_en ajoutée.';
END;
GO

SET XACT_ABORT ON;
SET NOCOUNT ON;

DECLARE @seed TABLE (type_code nvarchar(100), description_fr nvarchar(500), description_en nvarchar(500));
INSERT INTO @seed VALUES
 (N'ATTESTATION_TRAVAIL',  N'Attestation prouvant la relation de travail',                   N'Certificate proving the employment relationship'),
 (N'ATTESTATION_SALAIRE',  N'Attestation indiquant le salaire mensuel',                      N'Certificate stating the monthly salary'),
 (N'BULLETIN_PAIE',        N'Demande d''un duplicata de fiche de paie',                      N'Request for a duplicate pay slip'),
 (N'ATTESTATION_CONGE',    N'Attestation de prise en charge de congé approuvé',              N'Certificate confirming an approved leave'),
 (N'CHANGEMENT_ADRESSE',   N'Mise à jour de l''adresse personnelle dans le dossier RH',      N'Update of the home address in the HR file'),
 (N'CHANGEMENT_EMAIL',     N'Mise à jour de l''adresse e-mail personnelle',                  N'Update of the personal email address'),
 (N'CHANGEMENT_TELEPHONE', N'Mise à jour du numéro de téléphone personnel',                  N'Update of the personal phone number'),
 (N'CHANGEMENT_URGENCE',   N'Mise à jour du contact d''urgence',                             N'Update of the emergency contact'),
 (N'CHANGEMENT_PHOTO',     N'Demande de mise à jour de la photo du profil RH',               N'Request to update the HR profile photo'),
 (N'MISE_A_JOUR_BANCAIRE', N'Modification IBAN, RIB ou compte bancaire — validation L2',     N'Change of IBAN, RIB or bank account — L2 validation'),
 (N'DEMANDE_FORMATION',    N'Demande d''inscription à une formation professionnelle',        N'Request to enrol in a professional training course'),
 (N'MUTATION_INTERNE',     N'Demande de changement de département ou de site',               N'Request to change department or site'),
 (N'AUTRE',                N'Toute autre demande ne correspondant pas aux catégories ci-dessus', N'Any other request that does not fit the categories above');

-- Aperçu : lignes qui vont recevoir une description anglaise
SELECT t.id, t.pays_id, t.type_code, s.description_en
FROM   dbo.request_type_catalog t
JOIN   @seed s ON s.type_code = t.type_code AND s.description_fr = t.description
WHERE  t.description_en IS NULL
ORDER  BY t.pays_id, t.type_code;

BEGIN TRANSACTION;

UPDATE t
SET    t.description_en = s.description_en
FROM   dbo.request_type_catalog t
JOIN   @seed s ON s.type_code = t.type_code AND s.description_fr = t.description
WHERE  t.description_en IS NULL;

PRINT CONCAT(@@ROWCOUNT, ' description(s) anglaise(s) renseignée(s).');

COMMIT TRANSACTION;

-- Pour information : types avec une description française mais sans traduction
-- (descriptions personnalisées) — à compléter depuis /rh/admin?tab=request-types.
SELECT t.id, t.pays_id, t.type_code, t.description
FROM   dbo.request_type_catalog t
WHERE  t.description IS NOT NULL AND t.description_en IS NULL
ORDER  BY t.pays_id, t.type_code;
