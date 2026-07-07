-- ============================================================================
-- V38 — Unify gender values across the app
-- ----------------------------------------------------------------------------
-- employee_profiles.gender has historically held three different vocabularies:
--   * onboarding write path  -> canonical GENDER list codes (MALE/FEMALE/OTHER/UNSPECIFIED)
--   * profile-detail edit     -> French labels (Homme/Femme/Autre/Non précisé)
--   * legacy data             -> MASCULIN/FEMININ and misc. single letters
-- This fold-in normalizes every existing row to the canonical GENDER list
-- value_codes (see V9__configurable_lists_seed.sql). Comparisons are done on a
-- trimmed, upper-cased copy so casing/whitespace variants are covered too.
-- Idempotent: re-running is a no-op once values are already canonical.
-- ============================================================================

-- Male
UPDATE [dbo].[employee_profiles]
SET gender = 'MALE'
WHERE gender IS NOT NULL
  AND UPPER(LTRIM(RTRIM(gender))) IN ('HOMME', 'MASCULIN', 'MASCULINO', 'MALE', 'M', 'H');

-- Female
UPDATE [dbo].[employee_profiles]
SET gender = 'FEMALE'
WHERE gender IS NOT NULL
  AND UPPER(LTRIM(RTRIM(gender))) IN ('FEMME', 'FEMININ', 'FÉMININ', 'FEMENINO', 'FEMALE', 'F');

-- Other
UPDATE [dbo].[employee_profiles]
SET gender = 'OTHER'
WHERE gender IS NOT NULL
  AND UPPER(LTRIM(RTRIM(gender))) IN ('AUTRE', 'OTHER', 'O');

-- Unspecified (also folds empty strings to the explicit code)
UPDATE [dbo].[employee_profiles]
SET gender = 'UNSPECIFIED'
WHERE gender IS NOT NULL
  AND UPPER(LTRIM(RTRIM(gender))) IN (
      'NON PRÉCISÉ', 'NON PRECISE', 'NON SPÉCIFIÉ', 'NON SPECIFIE',
      'NON SPÉCIFIÉE', 'NON SPECIFIEE', 'UNSPECIFIED', 'PLACEHOLDER', 'N/A', 'NA', ''
  );
