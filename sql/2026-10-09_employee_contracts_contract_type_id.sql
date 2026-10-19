-- ─────────────────────────────────────────────────────────────────────────────
-- Contrats du cycle de vie (employee_contracts) : code → id du type de contrat
-- + « nature » de chaque type de contrat (configurable_list_values.lifecycle_nature)
--
-- 1. Chaque valeur CONTRACT_TYPE porte une nature : CDI, CDD, CIVP, STAGE, FREELANCE ou
--    DETACHEMENT. Le moteur de contrats applique les règles de cette nature (date de fin,
--    renouvellement CDD, CIVP, statut initial, période d'essai, alertes). Un type ajouté
--    dans l'admin (« contrat ») suit donc les règles de sa nature — CDI par défaut,
--    modifiable dans Admin › Listes configurables › Type de contrat.
-- 2. employee_contracts.contract_type_code contient désormais l'id de la valeur de la liste
--    (comme employee_profiles.contract_type et candidates.contract_type), plus le code.
--    La contrainte CHECK qui figeait les 6 codes est supprimée.
--
-- contract_type_config reste défini par nature (une ligne par pays × nature) ; un pays
-- sans ligne reprend celle de la Tunisie (backend : ContractTypeConfigRepository.findForNature).
--
-- Idempotent. À appliquer à la main (pas de Flyway), AVANT de déployer le backend.
-- ─────────────────────────────────────────────────────────────────────────────
SET XACT_ABORT ON;
SET NOCOUNT ON;

-- 1. Colonne lifecycle_nature
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME = 'configurable_list_values' AND COLUMN_NAME = 'lifecycle_nature')
    ALTER TABLE dbo.configurable_list_values ADD lifecycle_nature VARCHAR(30) NULL;
GO

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_CLV_LifecycleNature')
    ALTER TABLE dbo.configurable_list_values ADD CONSTRAINT CK_CLV_LifecycleNature
        CHECK (lifecycle_nature IS NULL
               OR lifecycle_nature IN ('CDI', 'CDD', 'CIVP', 'STAGE', 'FREELANCE', 'DETACHEMENT'));
GO

DECLARE @listTypeId BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE');
IF @listTypeId IS NULL
BEGIN
    THROW 50001, 'Liste CONTRACT_TYPE introuvable dans configurable_list_types.', 1;
END;

-- Nature des types de contrat : leur propre code s'il en est une, sinon CDI
UPDATE dbo.configurable_list_values
SET    lifecycle_nature = CASE
           WHEN UPPER(LTRIM(RTRIM(value_code))) IN ('CDI', 'CDD', 'CIVP', 'STAGE', 'FREELANCE', 'DETACHEMENT')
               THEN UPPER(LTRIM(RTRIM(value_code)))
           WHEN UPPER(LTRIM(RTRIM(value_code))) = 'PORTAGE' THEN 'FREELANCE'
           ELSE 'CDI'
       END
WHERE  list_type_id = @listTypeId
  AND  lifecycle_nature IS NULL;

PRINT CONCAT(@@ROWCOUNT, ' type(s) de contrat : nature renseignée.');

SELECT id, pays_id, value_code, label_fr, lifecycle_nature
FROM   dbo.configurable_list_values
WHERE  list_type_id = @listTypeId
ORDER  BY pays_id, value_code;
GO

-- 2. employee_contracts : contrainte CHECK sur les 6 codes
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_EC_ContractType')
    ALTER TABLE dbo.employee_contracts DROP CONSTRAINT CK_EC_ContractType;
GO

-- 3. employee_contracts.contract_type_code : code → id
DECLARE @listTypeId BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE');

IF OBJECT_ID('tempdb..#ec_map') IS NOT NULL DROP TABLE #ec_map;

SELECT ec.id                                     AS contract_id,
       ec.pays_id,
       ec.contract_type_code                     AS old_value,
       COALESCE(tgt.id, cdi.id)                  AS new_id,
       COALESCE(tgt.value_code, cdi.value_code)  AS new_code,
       CASE WHEN tgt.id IS NULL THEN 1 ELSE 0 END AS par_defaut_cdi
INTO   #ec_map
FROM   dbo.employee_contracts ec
OUTER APPLY (
    SELECT TOP 1 v.id, v.value_code
    FROM   dbo.configurable_list_values v
    WHERE  v.list_type_id = @listTypeId
      AND  (v.pays_id IS NULL OR v.pays_id = ec.pays_id)
      AND  UPPER(LTRIM(RTRIM(v.value_code))) =
           CASE UPPER(LTRIM(RTRIM(ec.contract_type_code)))
               WHEN 'PORTAGE' THEN 'FREELANCE'
               ELSE UPPER(LTRIM(RTRIM(ec.contract_type_code)))
           END
    ORDER BY CASE WHEN v.pays_id IS NULL THEN 1 ELSE 0 END,
             CASE WHEN v.is_active = 1   THEN 0 ELSE 1 END,
             v.id
) tgt
OUTER APPLY (
    SELECT TOP 1 v.id, v.value_code
    FROM   dbo.configurable_list_values v
    WHERE  v.list_type_id = @listTypeId
      AND  (v.pays_id IS NULL OR v.pays_id = ec.pays_id)
      AND  UPPER(LTRIM(RTRIM(v.value_code))) = 'CDI'
    ORDER BY CASE WHEN v.pays_id IS NULL THEN 1 ELSE 0 END,
             CASE WHEN v.is_active = 1   THEN 0 ELSE 1 END,
             v.id
) cdi
WHERE  NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values x
                   WHERE x.list_type_id = @listTypeId
                     AND x.id = TRY_CAST(ec.contract_type_code AS BIGINT));

-- Aperçu
SELECT old_value, new_code, new_id, par_defaut_cdi, COUNT(*) AS nb_contrats
FROM   #ec_map
GROUP  BY old_value, new_code, new_id, par_defaut_cdi
ORDER  BY par_defaut_cdi DESC, old_value;

BEGIN TRANSACTION;

UPDATE ec
SET    ec.contract_type_code = CONVERT(varchar(30), m.new_id),
       ec.updated_at         = SYSDATETIMEOFFSET()
FROM   dbo.employee_contracts ec
JOIN   #ec_map m ON m.contract_id = ec.id
WHERE  m.new_id IS NOT NULL;

PRINT CONCAT(@@ROWCOUNT, ' contrat(s) converti(s).');

COMMIT TRANSACTION;

-- Vérification : doit renvoyer 0 ligne
SELECT ec.id, ec.pays_id, ec.contract_type_code
FROM   dbo.employee_contracts ec
WHERE  NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values v
                   WHERE v.list_type_id = @listTypeId
                     AND v.id = TRY_CAST(ec.contract_type_code AS BIGINT));

-- Lecture
SELECT ec.id, ec.employee_profile_id, ec.contract_type_code AS contract_type_id,
       v.value_code, v.label_fr, v.lifecycle_nature, ec.current_status_code
FROM   dbo.employee_contracts ec
LEFT   JOIN dbo.configurable_list_values v ON v.id = TRY_CAST(ec.contract_type_code AS BIGINT);

DROP TABLE #ec_map;
