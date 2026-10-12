-- Read-only : quels scripts « type de contrat » (2026-10-06 → 10-09) sont déjà appliqués ?
-- À exécuter sur DAF360_HR. Aucune écriture.
SET NOCOUNT ON;

DECLARE @lt BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE');

SELECT script, CASE WHEN applied = 1 THEN 'OK - appliqué' ELSE '>>> À EXÉCUTER' END AS etat, detail
FROM (
  SELECT 1 AS ord, '2026-10-06_type_contrat_configurable' AS script,
         CASE WHEN OBJECT_ID('dbo.CK_EmpProfile_ContractType') IS NULL THEN 1 ELSE 0 END AS applied,
         'CHECK CK_EmpProfile_ContractType supprimée' AS detail
  UNION ALL
  SELECT 2, '2026-10-07_onboarding_contract_type',
         CASE WHEN NOT EXISTS (SELECT 1 FROM dbo.employee_profiles
                               WHERE contract_type IN ('PERMANENT','FIXED_TERM','INTERN','CONSULTANT')) THEN 1 ELSE 0 END,
         'plus aucun profil PERMANENT/FIXED_TERM/INTERN/CONSULTANT'
  UNION ALL
  SELECT 3, '2026-10-08_profile_contract_type_id',
         CASE WHEN NOT EXISTS (SELECT 1 FROM dbo.employee_profiles
                               WHERE NULLIF(LTRIM(contract_type),'') IS NOT NULL
                                 AND TRY_CAST(contract_type AS BIGINT) IS NULL) THEN 1 ELSE 0 END,
         CONCAT((SELECT COUNT(*) FROM dbo.employee_profiles
                 WHERE NULLIF(LTRIM(contract_type),'') IS NOT NULL AND TRY_CAST(contract_type AS BIGINT) IS NULL),
                ' profil(s) avec un code au lieu d''un id')
  UNION ALL
  SELECT 4, '2026-10-09_candidates_contract_type_id',
         CASE WHEN COL_LENGTH('dbo.candidates','contract_type') IS NOT NULL
               AND OBJECT_ID('dbo.CK_Candidate_ContractType') IS NULL THEN 1 ELSE 0 END,
         'colonne candidates.contract_type + CHECK CK_Candidate_ContractType supprimée'
  UNION ALL
  SELECT 5, '2026-10-09_employee_contracts_contract_type_id',
         CASE WHEN COL_LENGTH('dbo.configurable_list_values','lifecycle_nature') IS NOT NULL
               AND OBJECT_ID('dbo.CK_EC_ContractType') IS NULL THEN 1 ELSE 0 END,
         'colonne lifecycle_nature + CHECK CK_EC_ContractType supprimée'
  UNION ALL
  SELECT 6, '2026-10-09_cost_approvals_contract_type_id',
         CASE WHEN NOT EXISTS (SELECT 1 FROM dbo.candidate_cost_approvals
                               WHERE contract_type_code IS NOT NULL
                                 AND TRY_CAST(contract_type_code AS BIGINT) IS NULL) THEN 1 ELSE 0 END,
         'contract_type_code ne contient plus que des ids'
  UNION ALL
  SELECT 7, '2026-10-09_historique_type_contrat_list',
         CASE WHEN OBJECT_ID('dbo.FK_HistoriqueContrat_ContractType') IS NOT NULL THEN 1 ELSE 0 END,
         'FK historique -> configurable_list_values présente'
  UNION ALL
  SELECT 8, '2026-10-09_contract_type_tunisie',
         CASE WHEN (SELECT is_per_pays FROM dbo.configurable_list_types WHERE id = @lt) = 1 THEN 1 ELSE 0 END,
         CONCAT('is_per_pays = 1 ; valeurs encore communes : ',
                (SELECT COUNT(*) FROM dbo.configurable_list_values WHERE list_type_id = @lt AND pays_id IS NULL))
  UNION ALL
  SELECT 9, '(6068d6a) Roles.englishName',
         CASE WHEN COL_LENGTH('dbo.Roles','englishName') IS NOT NULL THEN 1 ELSE 0 END,
         'colonne ajoutée (Hibernate ou à la main)'
) t
ORDER BY ord;
