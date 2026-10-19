-- ─────────────────────────────────────────────────────────────────────────────
-- Type de contrat : créer le CDI de l'Égypte (pays_id = 53)
-- (Admin › Listes configurables › Type de contrat, configurable_list_types.code = 'CONTRACT_TYPE')
--
-- Le tri, le code paie et la nature (lifecycle_nature) sont recopiés d'un CDI existant
-- (celui d'un autre pays, ex. la Tunisie) pour que la valeur se comporte comme les autres.
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

IF @listTypeId IS NULL
    THROW 50002, 'Liste CONTRACT_TYPE introuvable dans configurable_list_types.', 1;

-- Modèle : un CDI existant d'un autre pays (tri, code paie, nature)
DECLARE @sortOrder INT, @payrollCode VARCHAR(20), @nature VARCHAR(30);
SELECT TOP 1 @sortOrder = sort_order, @payrollCode = payroll_contract_code, @nature = lifecycle_nature
FROM   dbo.configurable_list_values
WHERE  list_type_id = @listTypeId AND value_code = 'CDI'
ORDER  BY CASE WHEN pays_id IS NULL THEN 1 ELSE 0 END, id;

IF NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values
               WHERE list_type_id = @listTypeId AND pays_id = @paysId AND value_code = 'CDI')
BEGIN
    INSERT INTO dbo.configurable_list_values
        (list_type_id, pays_id, value_code, label_fr, label_en,
         sort_order, is_active, is_system, created_at, payroll_contract_code, lifecycle_nature)
    VALUES
        (@listTypeId, @paysId, 'CDI',
         N'Contrat à durée indéterminée (CDI)', N'Indefinite-Term Employment Contract',
         ISNULL(@sortOrder, 1), 1, 0, SYSDATETIMEOFFSET(), @payrollCode, ISNULL(@nature, 'CDI'));
    PRINT 'Type CDI créé pour le pays 53.';
END
ELSE
    PRINT 'Le type CDI existe déjà pour le pays 53 — rien à faire.';

-- Vérification
SELECT id, pays_id, value_code, label_fr, label_en, sort_order, is_active, payroll_contract_code, lifecycle_nature
FROM   dbo.configurable_list_values
WHERE  list_type_id = @listTypeId AND pays_id = @paysId;
