-- ============================================================
-- V71 — Complete the urgency and experience scales
--
-- Data only, no schema change. Nothing breaks without it; the two new steps simply do not
-- resolve and the hiring form rejects them.
--
-- WHY: the self-service hiring form drives urgency and experience as SLIDERS with a fixed,
-- hardcoded scale — the scale must always be there, with no dependency on a list fetch. But
-- recruitment_demands.urgency_level_id and .experience_level_id are real FKs to
-- configurable_list_values (V31 §57, V32 §87), so a slider position cannot be stored as a
-- number: every step needs a row to point at.
--
-- V31 seeded 4 urgency levels (FAIBLE/NORMAL/URGENT/TRES_URGENT) and V32 seeded 4 experience
-- levels (JUNIOR/CONFIRME/SENIOR/EXPERT). The form's scales are urgency 1–5 and an experience
-- scale that starts BELOW junior, so two values are missing:
--
--   URGENCY_LEVEL    + CRITIQUE  (5th step, sort 5)
--   EXPERIENCE_LEVEL + DEBUTANT  "Fraîchement diplômé" (first step, sort 0 — before JUNIOR=1)
--
-- DEBUTANT gets sort_order 0 rather than renumbering the others: sort_order only has to
-- ORDER, and shifting JUNIOR/CONFIRME/SENIOR/EXPERT would rewrite rows that existing demands
-- already reference for no gain.
--
-- Both list types are global (is_per_pays = 0 / no pays_id on the values), so these are too —
-- the scale must read the same for every entity.
--
-- ⚠️ PREREQUISITE: V32 MUST BE APPLIED FIRST.
-- V32 creates the EXPERIENCE_LEVEL / CSP_CATEGORY / EDUCATION_LEVEL list types and the
-- recruitment_demands columns that reference them. This file's DEBUTANT insert is keyed on
-- `WHERE t.code = 'EXPERIENCE_LEVEL'`, so without V32 it silently inserts NOTHING — no error,
-- just a missing step. The report at the bottom is what catches that.
--
--   sqlcmd -S <server> -d DAF360_HR -i V32__enhanced_recruitment_demand.sql   -- first
--   sqlcmd -S <server> -d DAF360_HR -i V71__urgency_experience_scale_values.sql
-- Re-runnable: NOT EXISTS guards, same shape as V31/V32.
-- Created: 2026-08-07
-- ============================================================

-- ── URGENCY_LEVEL + CRITIQUE ────────────────────────────────────────────────
INSERT INTO [dbo].[configurable_list_values]
    (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
SELECT t.id, 'CRITIQUE', N'Critique', 'Critical', 5, 1, 0, SYSDATETIMEOFFSET()
FROM [dbo].[configurable_list_types] t
WHERE t.code = 'URGENCY_LEVEL'
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[configurable_list_values] x
      WHERE x.list_type_id = t.id AND x.value_code = 'CRITIQUE'
  );
GO

-- ── EXPERIENCE_LEVEL + DEBUTANT ─────────────────────────────────────────────
INSERT INTO [dbo].[configurable_list_values]
    (list_type_id, value_code, label_fr, label_en, sort_order, is_active, is_system, created_at)
SELECT t.id, 'DEBUTANT', N'Fraîchement diplômé', 'Fresh graduate', 0, 1, 0, SYSDATETIMEOFFSET()
FROM [dbo].[configurable_list_types] t
WHERE t.code = 'EXPERIENCE_LEVEL'
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[configurable_list_values] x
      WHERE x.list_type_id = t.id AND x.value_code = 'DEBUTANT'
  );
GO

-- The form resolves these BY CODE, so a mismatch here is what would silently break it.
PRINT '--- Urgency scale (must be FAIBLE, NORMAL, URGENT, TRES_URGENT, CRITIQUE) ---';
SELECT v.sort_order, v.value_code, v.label_fr
FROM [dbo].[configurable_list_values] v
JOIN [dbo].[configurable_list_types] t ON t.id = v.list_type_id
WHERE t.code = 'URGENCY_LEVEL' AND v.is_active = 1
ORDER BY v.sort_order;
GO

PRINT '--- Experience scale (must be DEBUTANT, JUNIOR, CONFIRME, SENIOR, EXPERT) ---';
SELECT v.sort_order, v.value_code, v.label_fr
FROM [dbo].[configurable_list_values] v
JOIN [dbo].[configurable_list_types] t ON t.id = v.list_type_id
WHERE t.code = 'EXPERIENCE_LEVEL' AND v.is_active = 1
ORDER BY v.sort_order;
GO

PRINT 'V71 applied: urgency CRITIQUE + experience DEBUTANT.';
GO
