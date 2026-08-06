-- ============================================================
-- V67 — Per-entity timezone (IANA) for pointage / hours handling
--
-- ⚠️ Apply BEFORE deploying rh-service. The local/prod profiles run ddl-auto: none, so the
--    columns are NOT created for you: WorkingTimeRegime now maps
--    working_time_regimes.timezone, and every read/write of a regime fails with an
--    "Invalid column name 'timezone'" until this script has run.
--
-- WHY THIS EXISTS: every presence decision in the pointage module compares a clock
-- against an "HH:mm" string that HR typed as LOCAL time in the employee's country
-- (regime start/end, break windows). A single server clock is therefore correct in
-- exactly one country: a UTC JVM fires every transition 1h late for Tunisia and 4h
-- late for Dubai. The zone has to be data, resolved per employee.
--
-- WHY IANA IDS AND NOT OFFSETS ('GMT+1'): an offset is a fact about a moment; a zone
-- is a rule about a place. Europe/Paris is UTC+1 in winter and UTC+2 in summer, so a
-- stored '+1' is wrong ~7 months a year; Africa/Casablanca drops to UTC+0 for ~a month
-- each Ramadan on a window that moves ~11 days annually. IANA carries those rules and
-- is updated by tzdata, so the data never changes. Java also punishes offsets quietly:
-- ZoneId.of("GMT+1") parses fine and returns a zone with NO rules, so DST is ignored
-- with no error. Sub-hour zones (Asia/Kathmandu +5:45) are a further trap.
--
-- TWO COLUMNS, TWO PURPOSES:
--   pays.timezone                  the entity default — where its employees work
--   working_time_regimes.timezone  NULL = inherit from pays. Set only to make a regime
--                                  run on a different clock than its entity, which is
--                                  what makes business travel expressible: a personal
--                                  override regime (employee_profiles.regime_template_id
--                                  + regime_start_date/regime_end_date, PRIORITY 2 in
--                                  RegimeResolutionService) gives a TIME-BOXED zone that
--                                  expires by itself. Hours stay contractual, weekends
--                                  stay the entity's (pays_weekends) — only the clock moves.
--
-- NO FALLBACK TO THE SERVER ZONE: an entity with no timezone must disable its automation
-- loudly (resolution returns null → "no schedule configured"), never silently substitute
-- the container's zone — that is the original bug re-entering through the back door.
--
-- NOT stored as a Windows zone name: SQL Server's AT TIME ZONE accepts only those
-- ('W. Central Africa Standard Time'), which is precisely why all zone conversion in
-- this platform happens in Java and never in SQL.
--
--   sqlcmd -S <server> -d DAF360_HR -i V67__pays_regime_timezone.sql
-- Re-runnable: every block is guarded.
-- Created: 2026-08-05
-- ============================================================

USE [DAF360_HR];
GO

-- ── 1. Entity (pays) timezone ────────────────────────────────────────────────

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='pays' AND COLUMN_NAME='timezone')
BEGIN
  ALTER TABLE [dbo].[pays] ADD [timezone] VARCHAR(64) NULL;
  PRINT 'pays.timezone added.';
END
ELSE
  PRINT 'pays.timezone already present.';
GO

-- ── 2. Regime timezone override (NULL = inherit from pays) ───────────────────

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='working_time_regimes' AND COLUMN_NAME='timezone')
BEGIN
  ALTER TABLE [dbo].[working_time_regimes] ADD [timezone] VARCHAR(64) NULL;
  PRINT 'working_time_regimes.timezone added.';
END
ELSE
  PRINT 'working_time_regimes.timezone already present.';
GO

-- ── 3. Seed the entities we can identify from iso_code ───────────────────────
-- A SEED HEURISTIC ONLY. One iso_code does not imply one zone: the US, Russia,
-- Australia and Brazil span several, so those must be set per entity from the admin
-- panel. Guarded with IS NULL so a hand-corrected value is never overwritten.

UPDATE [dbo].[pays] SET [timezone]='Africa/Tunis'      WHERE [iso_code]='TN' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Africa/Casablanca' WHERE [iso_code]='MA' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Africa/Algiers'    WHERE [iso_code]='DZ' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Africa/Cairo'      WHERE [iso_code]='EG' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Africa/Dakar'      WHERE [iso_code]='SN' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Africa/Abidjan'    WHERE [iso_code]='CI' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Africa/Libreville' WHERE [iso_code]='GA' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Africa/Douala'     WHERE [iso_code]='CM' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Europe/Paris'      WHERE [iso_code]='FR' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Europe/Brussels'   WHERE [iso_code]='BE' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Europe/Madrid'     WHERE [iso_code]='ES' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Europe/Rome'       WHERE [iso_code]='IT' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Europe/London'     WHERE [iso_code]='GB' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Asia/Dubai'        WHERE [iso_code]='AE' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Asia/Riyadh'       WHERE [iso_code]='SA' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Asia/Qatar'        WHERE [iso_code]='QA' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Asia/Tokyo'        WHERE [iso_code]='JP' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='Asia/Kolkata'      WHERE [iso_code]='IN' AND [timezone] IS NULL;
UPDATE [dbo].[pays] SET [timezone]='America/Montreal'  WHERE [iso_code]='CA' AND [timezone] IS NULL;
GO

-- ── 4. Report what still needs configuring ───────────────────────────────────
-- Both reports list rows that make presence automation inert, so they can be fixed
-- in the admin panel rather than discovered from a silent scheduler.

PRINT '--- Entities WITH employees but NO timezone (their automation stays disabled) ---';
SELECT p.id, p.iso_code, p.french_label, COUNT(e.id) AS employee_count
FROM [dbo].[pays] p
JOIN [dbo].[employee_profiles] e ON e.pays_id = p.id AND e.deleted = 0
WHERE p.[timezone] IS NULL
GROUP BY p.id, p.iso_code, p.french_label
ORDER BY employee_count DESC;
GO

PRINT '--- Resulting timezone per entity ---';
SELECT [id], [iso_code], [french_label], [timezone]
FROM [dbo].[pays]
WHERE ISNULL([deleted], 0) = 0
ORDER BY [id];
GO

PRINT '--- Regimes overriding their entity clock (expect only travel/temporary regimes) ---';
SELECT r.[id], r.[code], r.[label_fr], r.[pays_id], r.[timezone] AS regime_timezone, p.[timezone] AS pays_timezone
FROM [dbo].[working_time_regimes] r
JOIN [dbo].[pays] p ON p.id = r.pays_id
WHERE r.[timezone] IS NOT NULL;
GO
