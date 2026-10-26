-- ─────────────────────────────────────────────────────────────────────────────
-- candidates.passport_number : n° de passeport séparé du CIN (candidates.national_id)
--
-- Le formulaire candidat n'avait qu'un champ « N° CIN / Passeport », stocké dans
-- national_id : à l'onboarding, un passeport atterrissait donc dans le champ CIN de la
-- fiche employé. Le candidat porte maintenant les deux, et l'onboarding pré-remplit chacun
-- dans sa colonne (employee_profiles.national_id / passport_number).
--
-- Idempotent. À appliquer à la main (pas de Flyway) AVANT de déployer le backend :
-- Hibernate lit cette colonne et ddl-auto ne la crée pas en local / prod.
-- Les anciens candidats ne sont pas reclassés : impossible de savoir si leur national_id
-- était un CIN ou un passeport.
-- ─────────────────────────────────────────────────────────────────────────────
SET XACT_ABORT ON;
SET NOCOUNT ON;

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME = 'candidates' AND COLUMN_NAME = 'passport_number')
    ALTER TABLE dbo.candidates ADD passport_number VARCHAR(100) NULL;
GO

-- Vérification
SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
FROM   INFORMATION_SCHEMA.COLUMNS
WHERE  TABLE_NAME = 'candidates' AND COLUMN_NAME IN ('national_id', 'passport_number');
