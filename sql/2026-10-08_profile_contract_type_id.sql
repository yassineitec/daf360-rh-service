-- ─────────────────────────────────────────────────────────────────────────────
-- employee_profiles.contract_type : code → id de configurable_list_values
-- (Admin › Listes configurables › Type de contrat, configurable_list_types.code = 'CONTRACT_TYPE')
--
-- La colonne reste un varchar(50), mais contient désormais l'id de la ligne de la liste
-- (ex. 'cdi' → '42', id de la valeur CDI) au lieu du code. Le backend (ContractTypeRefs)
-- convertit le code envoyé par les écrans en id à l'écriture, et relit le code via cet id.
--
-- Correspondance : value_code identique (sans tenir compte de la casse ni des espaces),
-- anciens codes de l'assistant d'onboarding repris (PERMANENT→CDI, FIXED_TERM→CDD,
-- INTERN→STAGE, CONSULTANT→FREELANCE). Une valeur propre au pays du profil passe avant
-- une valeur globale, une valeur active avant une désactivée.
--
-- Idempotent : une ligne déjà convertie (id existant de la liste) n'est pas retouchée.
-- Tout ou rien : si une valeur ne trouve aucune ligne dans la liste, rien n'est modifié et
-- les profils concernés sont listés — ajouter la valeur dans l'admin puis relancer.
-- À appliquer à la main (pas de Flyway) AVANT de déployer le backend correspondant.
-- ─────────────────────────────────────────────────────────────────────────────
SET XACT_ABORT ON;
SET NOCOUNT ON;

DECLARE @listTypeId BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE');
IF @listTypeId IS NULL
BEGIN
    THROW 50001, 'Liste CONTRACT_TYPE introuvable dans configurable_list_types.', 1;
END;

-- Profils à convertir et leur cible
IF OBJECT_ID('tempdb..#ct_map') IS NOT NULL DROP TABLE #ct_map;

SELECT ep.id                      AS profile_id,
       ep.pays_id,
       ep.contract_type           AS old_value,
       tgt.id                     AS new_id,
       tgt.value_code             AS new_code
INTO   #ct_map
FROM   dbo.employee_profiles ep
OUTER APPLY (
    SELECT TOP 1 v.id, v.value_code
    FROM   dbo.configurable_list_values v
    WHERE  v.list_type_id = @listTypeId
      AND  (v.pays_id IS NULL OR v.pays_id = ep.pays_id)
      AND  UPPER(LTRIM(RTRIM(v.value_code))) =
           CASE UPPER(LTRIM(RTRIM(ep.contract_type)))
               WHEN 'PERMANENT'  THEN 'CDI'
               WHEN 'FIXED_TERM' THEN 'CDD'
               WHEN 'INTERN'     THEN 'STAGE'
               WHEN 'CONSULTANT' THEN 'FREELANCE'
               ELSE UPPER(LTRIM(RTRIM(ep.contract_type)))
           END
    ORDER BY CASE WHEN v.pays_id IS NULL THEN 1 ELSE 0 END,
             CASE WHEN v.is_active = 1   THEN 0 ELSE 1 END,
             v.id
) tgt
WHERE  ep.contract_type IS NOT NULL
  AND  LTRIM(RTRIM(ep.contract_type)) <> ''
  -- déjà converti : un id existant de la liste CONTRACT_TYPE
  AND  NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values x
                   WHERE x.list_type_id = @listTypeId
                     AND x.id = TRY_CAST(ep.contract_type AS BIGINT));

-- Aperçu
SELECT old_value, new_code, new_id, COUNT(*) AS nb_profils
FROM   #ct_map
GROUP  BY old_value, new_code, new_id
ORDER  BY old_value;

-- Valeurs sans correspondance : on s'arrête sans rien modifier
IF EXISTS (SELECT 1 FROM #ct_map WHERE new_id IS NULL)
BEGIN
    SELECT profile_id, pays_id, old_value AS contract_type_sans_correspondance
    FROM   #ct_map
    WHERE  new_id IS NULL
    ORDER  BY old_value, profile_id;
    THROW 50002, 'Des valeurs de contract_type n''ont aucune ligne dans CONTRACT_TYPE (voir la liste ci-dessus) : rien n''a été modifié.', 1;
END;

BEGIN TRANSACTION;

UPDATE ep
SET    ep.contract_type = CONVERT(varchar(50), m.new_id),
       ep.updated_at    = SYSDATETIMEOFFSET()
FROM   dbo.employee_profiles ep
JOIN   #ct_map m ON m.profile_id = ep.id;

PRINT CONCAT(@@ROWCOUNT, ' profil(s) converti(s).');

COMMIT TRANSACTION;

-- Vérification : doit renvoyer 0 ligne
SELECT ep.id, ep.contract_type
FROM   dbo.employee_profiles ep
WHERE  ep.contract_type IS NOT NULL
  AND  LTRIM(RTRIM(ep.contract_type)) <> ''
  AND  NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values v
                   WHERE v.list_type_id = @listTypeId
                     AND v.id = TRY_CAST(ep.contract_type AS BIGINT));

-- Exemple : le profil 40225
SELECT ep.id, ep.contract_type AS contract_type_id, v.value_code, v.label_fr
FROM   dbo.employee_profiles ep
LEFT   JOIN dbo.configurable_list_values v ON v.id = TRY_CAST(ep.contract_type AS BIGINT)
WHERE  ep.id = 40225;

DROP TABLE #ct_map;
