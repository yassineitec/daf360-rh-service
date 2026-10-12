-- ─────────────────────────────────────────────────────────────────────────────
-- Profils égyptiens (pays_id = 53) : affecter le CDI de l'Égypte comme type de contrat
-- (Admin › Listes configurables › Type de contrat, configurable_list_types.code = 'CONTRACT_TYPE')
--
-- employee_profiles.contract_type reçoit l'id (en texte) de la valeur CDI du pays 53.
-- Seuls les profils sans type (NULL) sont touchés : un type déjà choisi est conservé.
--
-- Ordre d'exécution :
--   1. 2026-10-12_profils_egypte_type_contrat_hors_pays.sql  (vide le type des profils)
--   2. 2026-10-12_contract_type_cdi_egypte.sql               (crée le CDI de l'Égypte)
--   3. ce script
--
-- ATTENTION : 53 = Égypte dans la base visée — l'id peut différer d'une base à l'autre,
-- vérifier avec : SELECT id, french_label FROM dbo.pays WHERE id = 53;
-- Idempotent. À appliquer à la main (pas de Flyway). Cache des listes côté backend :
-- 10 min (ou redémarrer le RH).
-- ─────────────────────────────────────────────────────────────────────────────
SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @paysId     BIGINT = 53;
DECLARE @listTypeId BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE');
DECLARE @cdiId      BIGINT = (SELECT id FROM dbo.configurable_list_values
                              WHERE list_type_id = @listTypeId AND pays_id = @paysId AND value_code = 'CDI');

IF @cdiId IS NULL
    THROW 50003, 'CDI du pays 53 introuvable : exécuter d''abord 2026-10-12_contract_type_cdi_egypte.sql.', 1;

-- Avant : profils concernés
SELECT id AS profil_id, user_id, pays_id, contract_type
FROM   dbo.employee_profiles
WHERE  pays_id = @paysId;

UPDATE dbo.employee_profiles
SET    contract_type = CONVERT(varchar(50), @cdiId)
WHERE  pays_id = @paysId
  AND  contract_type IS NULL;

PRINT CONCAT(@@ROWCOUNT, ' profil(s) du pays 53 passé(s) en CDI (id = ', @cdiId, ').');

-- Après
SELECT id AS profil_id, user_id, pays_id, contract_type
FROM   dbo.employee_profiles
WHERE  pays_id = @paysId;
