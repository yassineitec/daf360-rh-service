-- ─────────────────────────────────────────────────────────────────────────────
-- Type de contrat → une seule liste configurable
-- (Admin › Listes configurables › Type de contrat, configurable_list_types.code = 'CONTRACT_TYPE')
--
-- Profils (/rh/profiles) et candidats (/rh/candidates) lisent désormais tous deux la liste
-- CONTRACT_TYPE. Idempotent : peut être rejoué sans effet.
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Profils : employee_profiles.contract_type n'est plus limité à
--    PERMANENT|FIXED_TERM|INTERN|CONSULTANT ; la valeur doit être un value_code actif de
--    CONTRACT_TYPE (contrôle fait par EmployeeProfileService.checkContractType).
--    On supprime la contrainte CHECK qui figeait les 4 anciens codes.
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_EmpProfile_ContractType')
    ALTER TABLE dbo.employee_profiles DROP CONSTRAINT CK_EmpProfile_ContractType;
GO

-- 2. Candidats : candidates.employment_type_id pointait vers une valeur de l'ancienne liste
--    EMPLOYMENT_TYPE. On la remplace par la valeur de CONTRACT_TYPE de même code et même
--    pays (CDI → CDI, …), pour que le formulaire candidat retrouve la valeur sélectionnée.
--    Une valeur sans équivalent dans CONTRACT_TYPE est laissée telle quelle.
UPDATE c
SET    c.employment_type_id = nv.id
FROM   dbo.candidates c
JOIN   dbo.configurable_list_values ov ON ov.id = c.employment_type_id
JOIN   dbo.configurable_list_types  ot ON ot.id = ov.list_type_id AND ot.code = 'EMPLOYMENT_TYPE'
JOIN   dbo.configurable_list_types  nt ON nt.code = 'CONTRACT_TYPE'
JOIN   dbo.configurable_list_values nv ON nv.list_type_id = nt.id
                                      AND nv.value_code   = ov.value_code
                                      AND ISNULL(nv.pays_id, -1) = ISNULL(ov.pays_id, -1);
GO
