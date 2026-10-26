-- ============================================================================
-- Type de contrat : la liste commune (pays_id NULL) est rattachée à la Tunisie
-- (Admin › Listes configurables › Type de contrat, configurable_list_types.code = 'CONTRACT_TYPE')
--
-- 1. La liste CONTRACT_TYPE passe « par pays » (is_per_pays = 1) : chaque pays ne voit
--    que ses propres types.
-- 2. Les valeurs communes (pays_id NULL) deviennent des valeurs de la Tunisie. Une valeur
--    dont le code existe déjà pour la Tunisie est laissée telle quelle (signalée en fin).
--
-- La Tunisie est cherchée par son libellé, pas par son id : il diffère d'une base à l'autre.
-- Idempotent : peut être rejoué sans effet. Se termine par COMMIT — aucune transaction
-- laissée ouverte (une transaction ouverte bloque le backend RH).
-- ============================================================================
SET NOCOUNT ON;
SET XACT_ABORT ON;

BEGIN TRAN;

DECLARE @tunisieId BIGINT = (SELECT TOP 1 id FROM dbo.pays WHERE french_label LIKE '%Tunisie%');
IF @tunisieId IS NULL
    THROW 50001, 'Pays Tunisie introuvable dans dbo.pays.', 1;

DECLARE @listTypeId BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE');
IF @listTypeId IS NULL
    THROW 50002, 'Liste CONTRACT_TYPE introuvable dans configurable_list_types.', 1;

-- 1. Liste par pays
UPDATE dbo.configurable_list_types SET is_per_pays = 1 WHERE id = @listTypeId;

-- 2. Valeurs communes → Tunisie
UPDATE v SET v.pays_id = @tunisieId
FROM dbo.configurable_list_values v
WHERE v.list_type_id = @listTypeId
  AND v.pays_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values x
                  WHERE x.list_type_id = v.list_type_id
                    AND x.pays_id = @tunisieId
                    AND x.value_code = v.value_code);

PRINT CONCAT(@@ROWCOUNT, ' type(s) rattaché(s) à la Tunisie (pays_id = ', @tunisieId, ').');

COMMIT;

-- Vérification : valeurs restées communes (code déjà présent pour la Tunisie)
SELECT v.id, v.value_code, v.label_fr, v.is_active
FROM dbo.configurable_list_values v
WHERE v.list_type_id = @listTypeId
  AND v.pays_id IS NULL;
