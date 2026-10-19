-- ─────────────────────────────────────────────────────────────────────────────
-- Liste configurable CONTRACT_TYPE (Admin › Listes configurables › Type de contrat)
--
-- Prérequis de toute la série 2026-10-06 → 2026-10-09 (type de contrat) : ces scripts
-- cherchent configurable_list_types.code = 'CONTRACT_TYPE' et s'arrêtent (THROW) s'il
-- n'existe pas. La liste avait été créée à la main via l'admin sur certaines bases ; ce
-- script la crée sur les autres.
--
-- 1. Le type CONTRACT_TYPE (commun, is_per_pays = 0 — le script 2026-10-09_contract_type_tunisie
--    le passe ensuite par pays et rattache les valeurs à la Tunisie).
-- 2. Les valeurs de l'ancienne liste EMPLOYMENT_TYPE sont recopiées (même code, même pays),
--    puis CDI / CDD / STAGE / FREELANCE sont garanties en valeurs communes.
--
-- Idempotent : peut être rejoué sans effet. À exécuter sur DAF360_HR, AVANT 2026-10-06.
-- ─────────────────────────────────────────────────────────────────────────────
SET XACT_ABORT ON;
SET NOCOUNT ON;

BEGIN TRAN;

-- 1. Type de liste
IF NOT EXISTS (SELECT 1 FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE')
    INSERT INTO dbo.configurable_list_types (code, label_fr, label_en, is_per_pays, is_system, created_at)
    VALUES ('CONTRACT_TYPE', N'Type de contrat', 'Contract type', 0, 0, GETDATE());

DECLARE @ctId BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE');
DECLARE @etId BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'EMPLOYMENT_TYPE');

-- 2a. Recopie de EMPLOYMENT_TYPE (même code, même pays)
IF @etId IS NOT NULL
    INSERT INTO dbo.configurable_list_values
        (list_type_id, pays_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
    SELECT @ctId, ev.pays_id, ev.value_code, ev.label_fr, ev.label_en, ev.sort_order, ev.is_active, 0, SYSDATETIMEOFFSET()
    FROM dbo.configurable_list_values ev
    WHERE ev.list_type_id = @etId
      AND NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values x
                      WHERE x.list_type_id = @ctId
                        AND x.value_code = ev.value_code
                        AND ISNULL(x.pays_id, -1) = ISNULL(ev.pays_id, -1));

-- 2b. Valeurs minimales (communes) si absentes
INSERT INTO dbo.configurable_list_values
    (list_type_id, pays_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
SELECT @ctId, NULL, s.code, s.label_fr, s.label_en, s.ord, 1, 0, SYSDATETIMEOFFSET()
FROM (VALUES ('CDI',       N'CDI — Contrat à durée indéterminée', 'Permanent contract',  1),
             ('CDD',       N'CDD — Contrat à durée déterminée',   'Fixed-term contract', 2),
             ('STAGE',     N'Stage',                              'Internship',          3),
             ('FREELANCE', N'Freelance / Consultant',             'Freelance',           4)
     ) s(code, label_fr, label_en, ord)
WHERE NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values x
                  WHERE x.list_type_id = @ctId AND x.value_code = s.code);

COMMIT;

-- Vérification
SELECT v.id, v.pays_id, v.value_code, v.label_fr, v.is_active
FROM dbo.configurable_list_values v
WHERE v.list_type_id = @ctId
ORDER BY v.pays_id, v.sort_order;
