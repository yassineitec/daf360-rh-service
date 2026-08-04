-- =============================================================================
-- V55: Pointage presence automation — regime & break configuration
--
-- 1. working_time_regimes.seasonal_from / seasonal_to
--    A temporary (seasonal) schedule such as the summer "séance unique". When a
--    regime carries an active window it becomes THE regime for its entity and
--    outranks role assignments and personal overrides, per HR requirement
--    "the temporary regime is checked first".
--    NULL/NULL = an ordinary year-round regime (unchanged behaviour).
--
-- 2. break_templates.status_code
--    The pointage status a break switches the employee into. Matches
--    status_definitions.status in DAF360_LOG (cross-database, so it is a
--    convention rather than an FK). Break labels are free text ("Pause 10",
--    "pause café"), so a coffee break cannot be told from lunch any other way.
--    NULL = the break drives no presence transition.
--
-- All blocks are guarded so this script is safe to re-run.
-- =============================================================================
USE [DAF360_HR];
GO

-- ── 1. Seasonal window on regimes ────────────────────────────────────────────

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='working_time_regimes' AND COLUMN_NAME='seasonal_from')
    ALTER TABLE [dbo].[working_time_regimes] ADD [seasonal_from] DATE NULL;
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='working_time_regimes' AND COLUMN_NAME='seasonal_to')
    ALTER TABLE [dbo].[working_time_regimes] ADD [seasonal_to] DATE NULL;
GO

-- Resolution reads active seasonal regimes per entity on every tick.
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE object_id = OBJECT_ID('dbo.working_time_regimes') AND name = 'IX_Regimes_Pays_Seasonal')
    CREATE NONCLUSTERED INDEX [IX_Regimes_Pays_Seasonal]
        ON [dbo].[working_time_regimes]([pays_id], [seasonal_from], [seasonal_to])
        WHERE [seasonal_from] IS NOT NULL;
GO

-- ── 2. Break → pointage status mapping ───────────────────────────────────────

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='break_templates' AND COLUMN_NAME='status_code')
    ALTER TABLE [dbo].[break_templates] ADD [status_code] VARCHAR(50) NULL;
GO

-- ── 3. Data hygiene the automation depends on ────────────────────────────────
-- These are guarded reports, NOT silent corrections: the values are the client's
-- to set from the admin panel. Each SELECT lists rows that will make presence
-- automation inert, so they can be fixed in the UI.

-- 3a. Entities that have employees but no regime at all. Employees here resolve
--     nothing; the module now reports "no schedule configured" (it used to invent
--     an 08:00-17:00 day, which is why unconfigured entities looked configured).
PRINT '--- Entities with employees but no active regime ---';
SELECT p.pays_id, COUNT(*) AS employee_count
FROM [dbo].[employee_profiles] p
WHERE NOT EXISTS (SELECT 1 FROM [dbo].[working_time_regimes] r
                  WHERE r.pays_id = p.pays_id AND r.is_active = 1)
GROUP BY p.pays_id;
GO

-- 3b. Active regimes with no hours. Automation is skipped for these.
PRINT '--- Active regimes missing start_time/end_time ---';
SELECT id, pays_id, code, label_fr, start_time, end_time
FROM [dbo].[working_time_regimes]
WHERE is_active = 1 AND (start_time IS NULL OR end_time IS NULL);
GO

-- 3c. Break windows with no status mapping. These windows change no status until
--     status_code is set from the admin panel.
PRINT '--- Active timed breaks with no status_code ---';
SELECT id, regime_id, label_fr, break_time_start, break_time_end
FROM [dbo].[break_templates]
WHERE is_active = 1 AND break_time_start IS NOT NULL AND status_code IS NULL;
GO

-- 3d. Entities with no configured weekend. Working days fall back to SAT+SUN,
--     which is wrong wherever the weekend differs (e.g. FRI+SAT).
PRINT '--- Entities with employees but no pays_weekends rows ---';
SELECT DISTINCT p.pays_id
FROM [dbo].[employee_profiles] p
WHERE NOT EXISTS (SELECT 1 FROM [dbo].[pays_weekends] w WHERE w.pays_id = p.pays_id);
GO
