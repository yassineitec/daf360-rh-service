-- ─────────────────────────────────────────────────────────────────────────────
-- Validations de coût candidat : candidate_cost_approvals.contract_type_code → id
--
-- La colonne (nvarchar(20)) contient désormais l'id de la valeur CONTRACT_TYPE de
-- configurable_list_values — comme employee_profiles, candidates et employee_contracts —
-- au lieu d'un code (CDI…). Le backend expose toujours la nature (CDI, CDD…) à la paie.
--
-- Cible : le type du candidat (candidates.employment_type_id) quand il est renseigné — le
-- type exact validé ; sinon le code stocké retrouvé dans la liste ; sinon CDI.
--
-- Idempotent. À appliquer à la main (pas de Flyway), AVANT de déployer le backend.
-- ─────────────────────────────────────────────────────────────────────────────
SET XACT_ABORT ON;
SET NOCOUNT ON;

DECLARE @listTypeId BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE');
IF @listTypeId IS NULL
BEGIN
    THROW 50001, 'Liste CONTRACT_TYPE introuvable dans configurable_list_types.', 1;
END;

IF OBJECT_ID('tempdb..#cca_map') IS NOT NULL DROP TABLE #cca_map;

SELECT a.id AS approval_id, a.contract_type_code AS old_value,
       COALESCE(cand.id, byCode.id, cdi.id) AS new_id,
       CASE WHEN cand.id IS NOT NULL THEN 'type du candidat'
            WHEN byCode.id IS NOT NULL THEN 'code retrouvé'
            ELSE 'CDI par défaut' END AS origine
INTO   #cca_map
FROM   dbo.candidate_cost_approvals a
LEFT   JOIN dbo.candidates c ON c.id = a.candidate_id
OUTER APPLY (
    SELECT v.id FROM dbo.configurable_list_values v
    WHERE  v.list_type_id = @listTypeId AND v.id = c.employment_type_id
) cand
OUTER APPLY (
    SELECT TOP 1 v.id FROM dbo.configurable_list_values v
    WHERE  v.list_type_id = @listTypeId
      AND  (v.pays_id IS NULL OR v.pays_id = a.pays_id)
      AND  UPPER(LTRIM(RTRIM(v.value_code))) = UPPER(LTRIM(RTRIM(a.contract_type_code)))
    ORDER  BY CASE WHEN v.pays_id IS NULL THEN 1 ELSE 0 END, v.id
) byCode
OUTER APPLY (
    SELECT TOP 1 v.id FROM dbo.configurable_list_values v
    WHERE  v.list_type_id = @listTypeId
      AND  (v.pays_id IS NULL OR v.pays_id = a.pays_id)
      AND  UPPER(LTRIM(RTRIM(v.value_code))) = 'CDI'
    ORDER  BY CASE WHEN v.pays_id IS NULL THEN 1 ELSE 0 END, v.id
) cdi
WHERE  NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values x
                   WHERE x.list_type_id = @listTypeId
                     AND x.id = TRY_CAST(a.contract_type_code AS BIGINT));

-- Aperçu
SELECT old_value, new_id, origine, COUNT(*) AS nb
FROM   #cca_map GROUP BY old_value, new_id, origine ORDER BY old_value;

BEGIN TRANSACTION;

UPDATE a
SET    a.contract_type_code = CONVERT(nvarchar(20), m.new_id)
FROM   dbo.candidate_cost_approvals a
JOIN   #cca_map m ON m.approval_id = a.id
WHERE  m.new_id IS NOT NULL;

PRINT CONCAT(@@ROWCOUNT, ' validation(s) convertie(s).');

COMMIT TRANSACTION;

-- Vérification : doit renvoyer 0 ligne
SELECT a.id, a.contract_type_code
FROM   dbo.candidate_cost_approvals a
WHERE  NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values v
                   WHERE v.list_type_id = @listTypeId
                     AND v.id = TRY_CAST(a.contract_type_code AS BIGINT));

DROP TABLE #cca_map;
