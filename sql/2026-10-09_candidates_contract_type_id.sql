-- ─────────────────────────────────────────────────────────────────────────────
-- candidates.contract_type : id du type de contrat (comme employee_profiles.contract_type)
--
-- Le type de contrat d'un candidat est candidates.employment_type_id (id de la ligne
-- CONTRACT_TYPE de configurable_list_values). contract_type n'était plus écrit et restait
-- à NULL ; il contient désormais le même id, en texte. Le backend (Candidate.syncContractType)
-- le recopie à chaque création / modification de candidat.
--
-- Idempotent. À appliquer à la main (pas de Flyway) AVANT de déployer le backend :
-- l'ancienne contrainte CHECK (PERMANENT|FIXED_TERM|INTERN|CONSULTANT) refuserait un id.
-- ─────────────────────────────────────────────────────────────────────────────
SET XACT_ABORT ON;
SET NOCOUNT ON;

-- 1. La colonne existe-t-elle ? (sinon on la crée, varchar(50) comme employee_profiles)
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME = 'candidates' AND COLUMN_NAME = 'contract_type')
    ALTER TABLE dbo.candidates ADD contract_type VARCHAR(50) NULL;
GO

-- 2. Ancienne contrainte CHECK sur les 4 codes figés
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_Candidate_ContractType')
    ALTER TABLE dbo.candidates DROP CONSTRAINT CK_Candidate_ContractType;
GO

-- 3. Aperçu
SELECT contract_type AS avant, CONVERT(varchar(50), employment_type_id) AS apres, COUNT(*) AS nb
FROM   dbo.candidates
WHERE  ISNULL(contract_type, '') <> ISNULL(CONVERT(varchar(50), employment_type_id), '')
GROUP  BY contract_type, employment_type_id
ORDER  BY employment_type_id;

-- 4. Remplissage depuis employment_type_id
BEGIN TRANSACTION;

UPDATE dbo.candidates
SET    contract_type = CONVERT(varchar(50), employment_type_id)
WHERE  ISNULL(contract_type, '') <> ISNULL(CONVERT(varchar(50), employment_type_id), '');

PRINT CONCAT(@@ROWCOUNT, ' candidat(s) mis à jour.');

COMMIT TRANSACTION;
GO

-- 5. Vérification : doit renvoyer 0 ligne
SELECT id, contract_type, employment_type_id
FROM   dbo.candidates
WHERE  ISNULL(contract_type, '') <> ISNULL(CONVERT(varchar(50), employment_type_id), '');

-- Exemple de lecture
SELECT TOP 20 c.id, c.contract_type, c.employment_type_id, v.value_code, v.label_fr
FROM   dbo.candidates c
LEFT   JOIN dbo.configurable_list_values v ON v.id = TRY_CAST(c.contract_type AS BIGINT)
ORDER  BY c.id DESC;
