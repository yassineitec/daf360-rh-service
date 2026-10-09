-- ─────────────────────────────────────────────────────────────────────────────
-- Historique des contrats : type_contrat → liste configurable CONTRACT_TYPE
--
-- historique_contrat_collaborateur.id_type_contrat pointait vers une table à part,
-- type_contrat (Admin › Référentiels), alors que profils, candidats et contrats utilisent la
-- liste configurable CONTRACT_TYPE. Une seule liste désormais :
--   1. chaque ligne de type_contrat est reprise dans CONTRACT_TYPE (même code) — créée si
--      elle n'y est pas, comme valeur partagée (toutes entités) ;
--   2. id_type_contrat est repointé vers l'id de configurable_list_values ;
--   3. la clé étrangère vers type_contrat est remplacée par une clé vers configurable_list_values.
-- La table type_contrat est conservée (plus lue par l'application) : à supprimer plus tard.
--
-- Idempotent (les étapes 2-3 ne s'exécutent que tant que la FK vers type_contrat existe).
-- À appliquer à la main (pas de Flyway), AVANT de déployer le backend correspondant.
-- ─────────────────────────────────────────────────────────────────────────────
SET XACT_ABORT ON;
SET NOCOUNT ON;

DECLARE @listTypeId BIGINT = (SELECT id FROM dbo.configurable_list_types WHERE code = 'CONTRACT_TYPE');
IF @listTypeId IS NULL
BEGIN
    THROW 50001, 'Liste CONTRACT_TYPE introuvable dans configurable_list_types.', 1;
END;

DECLARE @oldFk SYSNAME = (
    SELECT TOP 1 fk.name
    FROM   sys.foreign_keys fk
    WHERE  fk.parent_object_id     = OBJECT_ID('dbo.historique_contrat_collaborateur')
      AND  fk.referenced_object_id = OBJECT_ID('dbo.type_contrat'));

IF @oldFk IS NULL
BEGIN
    PRINT 'Déjà migré : historique_contrat_collaborateur ne référence plus type_contrat.';
END
ELSE
BEGIN
    BEGIN TRANSACTION;

    -- 1. Reprise des types dans la liste (valeur partagée), nature déduite du libellé
    INSERT INTO dbo.configurable_list_values
           (list_type_id, pays_id, value_code, label_fr, label_en, sort_order,
            is_active, is_system, created_at, lifecycle_nature)
    SELECT @listTypeId, NULL, tc.code, tc.label_fr, tc.label_en, 100,
           tc.is_active, 0, SYSDATETIMEOFFSET(),
           CASE WHEN UPPER(tc.code) LIKE '%FTC%' OR UPPER(tc.code) LIKE '%CDD%'
                  OR UPPER(tc.label_fr) LIKE '%FIXED%' OR UPPER(tc.label_fr) LIKE '%TERMIN%'
                THEN 'CDD' ELSE 'CDI' END
    FROM   dbo.type_contrat tc
    WHERE  NOT EXISTS (SELECT 1 FROM dbo.configurable_list_values v
                       WHERE v.list_type_id = @listTypeId
                         AND UPPER(LTRIM(RTRIM(v.value_code))) = UPPER(LTRIM(RTRIM(tc.code))));

    PRINT CONCAT(@@ROWCOUNT, ' type(s) ajouté(s) à la liste CONTRACT_TYPE.');

    -- Correspondance ancien id → id de la liste (valeur partagée d'abord)
    SELECT tc.id AS old_id, v.id AS new_id, tc.code
    INTO   #tc_map
    FROM   dbo.type_contrat tc
    CROSS APPLY (
        SELECT TOP 1 v.id
        FROM   dbo.configurable_list_values v
        WHERE  v.list_type_id = @listTypeId
          AND  UPPER(LTRIM(RTRIM(v.value_code))) = UPPER(LTRIM(RTRIM(tc.code)))
        ORDER  BY CASE WHEN v.pays_id IS NULL THEN 0 ELSE 1 END, v.id
    ) v;

    SELECT m.code, m.old_id, m.new_id, COUNT(h.id_historique_contrat) AS nb_historiques
    FROM   #tc_map m
    LEFT   JOIN dbo.historique_contrat_collaborateur h ON h.id_type_contrat = m.old_id
    GROUP  BY m.code, m.old_id, m.new_id;

    -- 2. Clé étrangère vers type_contrat retirée, puis repointage
    EXEC ('ALTER TABLE dbo.historique_contrat_collaborateur DROP CONSTRAINT [' + @oldFk + ']');

    UPDATE h
    SET    h.id_type_contrat = m.new_id
    FROM   dbo.historique_contrat_collaborateur h
    JOIN   #tc_map m ON m.old_id = h.id_type_contrat;

    PRINT CONCAT(@@ROWCOUNT, ' ligne(s) d''historique repointée(s).');

    -- 3. Nouvelle clé étrangère vers la liste configurable
    ALTER TABLE dbo.historique_contrat_collaborateur
        ADD CONSTRAINT FK_HistoriqueContrat_ContractType
        FOREIGN KEY (id_type_contrat) REFERENCES dbo.configurable_list_values (id);

    COMMIT TRANSACTION;
    DROP TABLE #tc_map;
END;

-- Vérification
SELECT h.id_historique_contrat, h.id_collaborateur, h.id_type_contrat,
       v.value_code, v.label_fr, v.lifecycle_nature
FROM   dbo.historique_contrat_collaborateur h
LEFT   JOIN dbo.configurable_list_values v ON v.id = h.id_type_contrat;
