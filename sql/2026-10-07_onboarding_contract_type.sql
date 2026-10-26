-- ─────────────────────────────────────────────────────────────────────────────
-- Onboarding › Type de contrat → liste configurable CONTRACT_TYPE
-- (Admin › Listes configurables › Type de contrat)
--
-- L'assistant d'onboarding proposait une liste figée PERMANENT / FIXED_TERM / INTERN /
-- CONSULTANT et l'écrivait dans employee_profiles.contract_type, alors que le profil, le
-- candidat et le backend (checkContractType, @ValidFixedTermContract) utilisent les codes
-- de la liste configurable (CDI, CDD, STAGE, FREELANCE…). Il propose désormais la liste ;
-- ce script aligne les profils déjà créés par l'ancien assistant.
--
-- Idempotent : ne touche que les 4 anciens codes. À appliquer à la main (pas de Flyway).
-- ─────────────────────────────────────────────────────────────────────────────

-- Aperçu avant migration
SELECT contract_type, COUNT(*) AS nb
FROM   dbo.employee_profiles
WHERE  contract_type IN ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT')
GROUP  BY contract_type;

BEGIN TRANSACTION;

UPDATE dbo.employee_profiles
SET    contract_type = CASE contract_type
                         WHEN 'PERMANENT'  THEN 'CDI'
                         WHEN 'FIXED_TERM' THEN 'CDD'
                         WHEN 'INTERN'     THEN 'STAGE'
                         WHEN 'CONSULTANT' THEN 'FREELANCE'
                       END,
       updated_at    = SYSDATETIMEOFFSET()
WHERE  contract_type IN ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT');

COMMIT TRANSACTION;

-- Vérification : doit renvoyer 0 ligne
SELECT id, contract_type
FROM   dbo.employee_profiles
WHERE  contract_type IN ('PERMANENT', 'FIXED_TERM', 'INTERN', 'CONSULTANT');
