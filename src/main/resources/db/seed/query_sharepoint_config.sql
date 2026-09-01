-- =============================================================================
-- query_sharepoint_config.sql — READ-ONLY
--
-- La configuration SharePoint est répartie sur deux niveaux :
--
--   1. Le SITE et le tenant : configuration SERVEUR, pas en base
--      (application.yml / variables d'environnement) :
--        graph.tenant-id / client-id / client-secret
--        app.sharepoint-site-path   (défaut /sites/pini-tunisia)
--      → non interrogeable en SQL : voir le .env du service.
--
--   2. Les CHEMINS par entité et par type de document : en base,
--      [dbo].[sharepoint_locations] — c'est ce que ces requêtes lisent.
--
-- Deux autres tables sont du CACHE, pas de la configuration :
--   [dbo].[employee_sharepoint_folders]  résolution du dossier de chaque employé
--   [dbo].[sharepoint_delta_state]       état de synchronisation Graph
-- =============================================================================

USE [DAF360_HR];

-- ── 1. LA configuration : un modèle de chemin par (entité × type de document) ──
SELECT p.id                AS pays_id,
       p.french_label      AS pays,
       p.iso_code,
       sl.doc_kind,
       sl.path_template,
       sl.is_active,
       sl.updated_at,
       u.fullName          AS updated_by
FROM [dbo].[sharepoint_locations] sl
JOIN [dbo].[pays] p       ON p.id = sl.pays_id
LEFT JOIN [dbo].[Users] u ON u.id = sl.updated_by
ORDER BY p.french_label, sl.doc_kind;

-- ── 2. Vue croisée : quelle entité a configuré quel type ? ────────────────────
-- Une cellule vide est une entité pour laquelle ce type de document n'a AUCUN
-- chemin — la lecture retombe alors sur le comportement par défaut du code.
SELECT p.id AS pays_id, p.french_label AS pays,
       MAX(CASE WHEN sl.doc_kind = 'PHOTO'              THEN sl.path_template END) AS photo,
       MAX(CASE WHEN sl.doc_kind = 'PAYSLIP'            THEN sl.path_template END) AS bulletin,
       MAX(CASE WHEN sl.doc_kind = 'SALARY_CERTIFICATE' THEN sl.path_template END) AS attestation
FROM [dbo].[pays] p
LEFT JOIN [dbo].[sharepoint_locations] sl
       ON sl.pays_id = p.id AND sl.is_active = 1
-- pays porte un drapeau `deleted` : sans ce filtre, une entité retirée apparaît
-- comme une configuration manquante.
WHERE (p.deleted = 0 OR p.deleted IS NULL)
GROUP BY p.id, p.french_label
ORDER BY p.french_label;

-- ── 3. Entités SANS aucun chemin actif ───────────────────────────────────────
-- À vérifier après l'ajout d'un pays : c'est la cause habituelle d'un document
-- ou d'une photo introuvable pour une seule entité.
SELECT p.id, p.french_label
FROM [dbo].[pays] p
WHERE (p.deleted = 0 OR p.deleted IS NULL)
  AND NOT EXISTS (
    SELECT 1 FROM [dbo].[sharepoint_locations] sl
    WHERE sl.pays_id = p.id AND sl.is_active = 1)
ORDER BY p.french_label;

-- ── 4. Lignes inactives ou orphelines ───────────────────────────────────────
-- doc_kind absent de l'enum Java DocKind est ignoré À LA LECTURE, sans erreur :
-- une ligne peut donc être préparée avant que le code qui la consomme existe —
-- mais une faute de frappe reste elle aussi silencieuse. À relire ici.
SELECT p.french_label AS pays, sl.doc_kind, sl.path_template, sl.is_active
FROM [dbo].[sharepoint_locations] sl
JOIN [dbo].[pays] p ON p.id = sl.pays_id
WHERE sl.is_active = 0
   OR sl.doc_kind NOT IN ('PHOTO', 'PAYSLIP', 'SALARY_CERTIFICATE')
ORDER BY p.french_label, sl.doc_kind;

-- ── 5. Cache de résolution des dossiers employés (≠ configuration) ───────────
-- Table volontairement creuse : aucune ligne pour un employé qui se résout du
-- premier coup. Une ligne existe soit parce qu'une découverte a abouti
-- (source = DISCOVERED), soit parce qu'un administrateur a corrigé un dossier que
-- la convention de nommage ne permet pas de deviner (source = MANUAL).
--
-- MANUAL n'est jamais écrasé par une découverte.
-- status / last_error servent de cache NÉGATIF : un échec est mémorisé avec sa
-- raison, ce qui évite de rejouer ~5 appels Graph à chaque rendu d'avatar.
SELECT TOP 100 esf.*
FROM [dbo].[employee_sharepoint_folders] esf
ORDER BY esf.employee_profile_id;

-- Répartition des états du cache — un pic de MANUAL signale une convention de
-- nommage qui ne correspond plus à la réalité du SharePoint.
SELECT doc_kind, source, status, COUNT(*) AS nb
FROM [dbo].[employee_sharepoint_folders]
GROUP BY doc_kind, source, status
ORDER BY doc_kind, source, status;

-- ── 6. État de synchronisation Graph ────────────────────────────────────────
SELECT * FROM [dbo].[sharepoint_delta_state];
