-- =============================================================================
-- V89__retire_two_request_types.sql
-- Retire deux types de demande du libre-service : ATTESTATION_ANCIENNETE et
-- TELETRAVAIL_PONCTUEL (demande du 2026-08-28).
--
-- ── POURQUOI is_active = 0 ET PAS UN DELETE ──────────────────────────────────
-- employee_requests.request_type_id pointe sur ces lignes. Un DELETE laisserait
-- les demandes deja soumises sans type — donc illisibles dans l'historique RH,
-- alors qu'elles ont ete traitees et gardent une valeur d'audit. La liste servie
-- au libre-service est deja filtree sur is_active (RequestTypeCatalogRepository
-- .findByPaysIdAndIsActiveTrue), donc desactiver suffit a les faire disparaitre
-- du formulaire tout en gardant l'historique consultable.
--
-- ── CE SCRIPT NE SUFFIT PAS SEUL ─────────────────────────────────────────────
-- RequestTypeCatalogService.seedDefaults() reinsere tout code de type absent
-- d'un pays, et l'endpoint POST /api/hr/request-types/seed peut etre appele par
-- un administrateur a tout moment. Les deux entrees ont donc ete retirees de
-- DEFAULT_TYPES dans le meme changement : sans cela, une ligne desactivee ici
-- reviendrait ACTIVE au prochain seed.
--
-- Le frontend (shell, page libre-service) a aussi perdu ses definitions de
-- champs, son icone, sa cle de description et ses entrees de repli — mais c'est
-- cosmetique : le catalogue vient de l'API, donc c'est CE script qui les retire
-- reellement de l'ecran.
--
-- Idempotente : un UPDATE sur une valeur deja posee ne change rien.
-- =============================================================================

UPDATE [dbo].[request_type_catalog]
SET [is_active] = 0,
    [updated_at] = SYSDATETIME()
WHERE [type_code] IN ('ATTESTATION_ANCIENNETE', 'TELETRAVAIL_PONCTUEL')
  AND [is_active] = 1;

PRINT 'V89: desactive ' + CAST(@@ROWCOUNT AS VARCHAR) + ' ligne(s) request_type_catalog.';
GO

-- ── Verification ─────────────────────────────────────────────────────────────
-- Doit ne renvoyer que des is_active = 0 :
--
-- SELECT pays_id, type_code, display_name_fr, is_active
-- FROM request_type_catalog
-- WHERE type_code IN ('ATTESTATION_ANCIENNETE', 'TELETRAVAIL_PONCTUEL')
-- ORDER BY pays_id;
--
-- Demandes deja soumises pour ces deux types — elles restent lisibles, c'est
-- voulu. Un nombre non nul explique pourquoi les lignes ne sont pas supprimees :
--
-- SELECT rtc.type_code, COUNT(*) AS demandes
-- FROM employee_requests er
-- JOIN request_type_catalog rtc ON rtc.id = er.request_type_id
-- WHERE rtc.type_code IN ('ATTESTATION_ANCIENNETE', 'TELETRAVAIL_PONCTUEL')
-- GROUP BY rtc.type_code;
