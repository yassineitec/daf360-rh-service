-- ─────────────────────────────────────────────────────────────────────────────
-- Profils égyptiens (pays_id = 53) : vider le type de contrat
-- (Admin › Listes configurables › Type de contrat, configurable_list_types.code = 'CONTRACT_TYPE')
--
-- Les profils égyptiens pointaient encore vers le CDI de la Tunisie. On remet
-- employee_profiles.contract_type à NULL pour tous les profils du pays 53 ; le formulaire
-- affiche alors « Aucun type de contrat… » tant que la liste de l'Égypte est vide, et le type
-- se choisit dans l'écran une fois la liste remplie.
--
-- ATTENTION : 53 = Égypte dans la base visée — l'id peut différer d'une base à l'autre,
-- vérifier avec : SELECT id, french_label FROM dbo.pays WHERE id = 53;
-- À appliquer à la main (pas de Flyway). Cache des listes côté backend : 10 min (ou redémarrer le RH).
-- ─────────────────────────────────────────────────────────────────────────────

-- Avant : profils concernés
SELECT id AS profil_id, user_id, pays_id, contract_type
FROM   dbo.employee_profiles
WHERE  pays_id = 53;

UPDATE dbo.employee_profiles
SET    contract_type = NULL
WHERE  pays_id = 53;

-- Après : contract_type doit être NULL partout
SELECT id AS profil_id, user_id, pays_id, contract_type
FROM   dbo.employee_profiles
WHERE  pays_id = 53;
