/* =============================================================================
   V56 — Tunisia working-time regimes + their break windows
   =============================================================================
   TN_STD   (year-round default)   08:00 → 17:45
              10:00–10:15   pause café       15 min  → ON_BREAK
              12:30–13:45   pause déjeuner   75 min  → LUNCH_BREAK
              16:00–16:15   pause café       15 min  → ON_BREAK
              window 585 min − 105 min break = 480 min = 8h/day × 5 = 40h/week

   TN_SUM   (seasonal 20 Jul → 14 Aug)  08:00 → 15:30
              11:30–12:00   pause            30 min  → ON_BREAK
              window 450 min − 30 min break  = 420 min = 7h/day × 5 = 35h/week

   Working days come from pays_weekends (Tunisia = SAT+SUN), so all breaks use
   applies_to_days = 'WEEKDAYS'. days_per_week = 5 follows from that.

   status_code is what makes a break actually change a pointage status. ON_BREAK
   and LUNCH_BREAK must exist and be ACTIVE in DAF360_LOG.status_definitions —
   check with db-diagnostics/03_DAF360_LOG.sql §3.4.

   SELF-SUFFICIENT: section 0 adds the three columns V55 introduces
   (seasonal_from, seasonal_to, status_code) if they are missing, so run order
   does not matter. Running V55 as well is still fine — both are guarded.

   Idempotent: regimes are keyed on (pays_id, code) and breaks on
   (regime_id, break_time_start), so re-running converges on the state described
   above instead of duplicating. A break window that is no longer wanted is
   deactivated, which is what makes a corrected time self-heal on re-run.
   ============================================================================= */
USE [DAF360_HR];
GO

SET NOCOUNT ON;
GO

/* ── 0. Prerequisite columns ───────────────────────────────────────────────────
   These are the same three guarded ALTERs as V55. They are repeated here on
   purpose so this script does not depend on run order.

   The first version of this file only CHECKED for them and used
   `RAISERROR + SET NOEXEC ON` to bail out. That does not work: NOEXEC suppresses
   EXECUTION but not COMPILATION, and column names are resolved when a batch is
   compiled — so every later batch still reported "Invalid column name
   'seasonal_from'" on top of the abort message. Creating the columns is both
   quieter and more useful than detecting their absence.

   Each ALTER sits in its own batch: a column added in the same batch that
   references it is not visible to the compiler yet.
   -------------------------------------------------------------------------- */
IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='working_time_regimes'
                 AND COLUMN_NAME='seasonal_from')
BEGIN
    ALTER TABLE [dbo].[working_time_regimes] ADD [seasonal_from] DATE NULL;
    PRINT 'Added working_time_regimes.seasonal_from';
END
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='working_time_regimes'
                 AND COLUMN_NAME='seasonal_to')
BEGIN
    ALTER TABLE [dbo].[working_time_regimes] ADD [seasonal_to] DATE NULL;
    PRINT 'Added working_time_regimes.seasonal_to';
END
GO

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA='dbo' AND TABLE_NAME='break_templates'
                 AND COLUMN_NAME='status_code')
BEGIN
    ALTER TABLE [dbo].[break_templates] ADD [status_code] VARCHAR(50) NULL;
    PRINT 'Added break_templates.status_code';
END
GO

/* The filtered index the resolver uses to find active seasonal regimes. */
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE object_id = OBJECT_ID('dbo.working_time_regimes')
                 AND name = 'IX_Regimes_Pays_Seasonal')
    CREATE NONCLUSTERED INDEX [IX_Regimes_Pays_Seasonal]
        ON [dbo].[working_time_regimes]([pays_id], [seasonal_from], [seasonal_to])
        WHERE [seasonal_from] IS NOT NULL;
GO

DECLARE @paysId BIGINT =
    (SELECT TOP 1 id FROM [dbo].[pays] WHERE iso_code = 'TN' AND deleted = 0 ORDER BY id);

IF @paysId IS NULL
BEGIN
    RAISERROR('V56 aborted: no pays row with iso_code = ''TN''.', 16, 1);
    SET NOEXEC ON;
END
GO

/* Re-read into a session variable usable by the statements below. */
DECLARE @paysId BIGINT =
    (SELECT TOP 1 id FROM [dbo].[pays] WHERE iso_code = 'TN' AND deleted = 0 ORDER BY id);

DECLARE @now DATETIME2 = SYSDATETIME();

/* Seasonal window. Both DATE columns, so this is ONE concrete window, not a
   recurring rule — bump the year each season (see the note at the bottom). */
DECLARE @summerFrom DATE = '2026-07-20';
DECLARE @summerTo   DATE = '2026-08-14';

/* ── 1. TN_STD — the year-round default ───────────────────────────────────── */
IF EXISTS (SELECT 1 FROM [dbo].[working_time_regimes] WHERE pays_id = @paysId AND code = 'TN_STD')
BEGIN
    UPDATE [dbo].[working_time_regimes]
       SET label_fr           = N'Tunisie — horaire standard',
           label_en           = N'Tunisia — standard hours',
           description_fr     = N'08:00–17:45, deux pauses de 15 min et pause déjeuner de 1h15. 8h travaillées par jour.',
           description_en     = N'08:00–17:45, two 15 min breaks and a 1h15 lunch break. 8 worked hours per day.',
           hours_per_week     = 40.00,
           days_per_week      = 5,
           start_time         = '08:00:00',
           end_time           = '17:45:00',
           break_duration_min = 105,          -- 15 + 75 + 15
           is_flexible        = 0,
           is_active          = 1,
           seasonal_from      = NULL,         -- year-round, never a seasonal override
           seasonal_to        = NULL,
           updated_at         = @now
     WHERE pays_id = @paysId AND code = 'TN_STD';
    PRINT 'TN_STD updated.';
END
ELSE
BEGIN
    INSERT INTO [dbo].[working_time_regimes]
        (pays_id, code, label_fr, label_en, description_fr, description_en,
         hours_per_week, days_per_week, start_time, end_time,
         is_flexible, is_default, is_active, break_duration_min, overtime_allowed,
         seasonal_from, seasonal_to, created_at)
    VALUES
        (@paysId, 'TN_STD',
         N'Tunisie — horaire standard', N'Tunisia — standard hours',
         N'08:00–17:45, deux pauses de 15 min et pause déjeuner de 1h15. 8h travaillées par jour.',
         N'08:00–17:45, two 15 min breaks and a 1h15 lunch break. 8 worked hours per day.',
         40.00, 5, '08:00:00', '17:45:00',
         0, 0, 1, 105, 0,
         NULL, NULL, @now);
    PRINT 'TN_STD created.';
END

/* ── 2. TN_SUM — the seasonal regime ──────────────────────────────────────── */
IF EXISTS (SELECT 1 FROM [dbo].[working_time_regimes] WHERE pays_id = @paysId AND code = 'TN_SUM')
BEGIN
    UPDATE [dbo].[working_time_regimes]
       SET label_fr           = N'Tunisie — horaire d''été (séance unique)',
           label_en           = N'Tunisia — summer hours (single session)',
           description_fr     = N'08:00–15:30 avec une pause de 30 min (11:30-12:00). 7h travaillées par jour.',
           description_en     = N'08:00–15:30 with one 30 min break (11:30-12:00). 7 worked hours per day.',
           hours_per_week     = 35.00,
           days_per_week      = 5,
           start_time         = '08:00:00',
           end_time           = '15:30:00',
           break_duration_min = 30,
           is_flexible        = 0,
           is_active          = 1,
           /* The seasonal window is what makes this outrank role assignments and
              personal overrides while it is active. */
           seasonal_from      = @summerFrom,
           seasonal_to        = @summerTo,
           updated_at         = @now
     WHERE pays_id = @paysId AND code = 'TN_SUM';
    PRINT 'TN_SUM updated (seasonal window set).';
END
ELSE
BEGIN
    INSERT INTO [dbo].[working_time_regimes]
        (pays_id, code, label_fr, label_en, description_fr, description_en,
         hours_per_week, days_per_week, start_time, end_time,
         is_flexible, is_default, is_active, break_duration_min, overtime_allowed,
         seasonal_from, seasonal_to, created_at)
    VALUES
        (@paysId, 'TN_SUM',
         N'Tunisie — horaire d''été (séance unique)', N'Tunisia — summer hours (single session)',
         N'08:00–15:30 avec une pause de 30 min (11:30-12:00). 7h travaillées par jour.',
         N'08:00–15:30 with one 30 min break (11:30-12:00). 7 worked hours per day.',
         35.00, 5, '08:00:00', '15:30:00',
         0, 0, 1, 30, 0,
         @summerFrom, @summerTo, @now);
    PRINT 'TN_SUM created.';
END

/* ── 3. Exactly one default for Tunisia ───────────────────────────────────── */
/* Two defaults make resolution non-deterministic (findFirst picks arbitrarily),
   so clear every other one before setting ours. NOTE: this takes is_default away
   from EGY1/'MASR', which is an Egypt regime currently flagged default on
   Tunisia — a data error worth fixing separately. It stays ACTIVE here because
   role assignments may still reference it. */
UPDATE [dbo].[working_time_regimes]
   SET is_default = 0, updated_at = @now
 WHERE pays_id = @paysId AND is_default = 1 AND code <> 'TN_STD';

UPDATE [dbo].[working_time_regimes]
   SET is_default = 1, updated_at = @now
 WHERE pays_id = @paysId AND code = 'TN_STD';
GO

/* ── 4. Break windows ─────────────────────────────────────────────────────── */
DECLARE @paysId  BIGINT = (SELECT TOP 1 id FROM [dbo].[pays] WHERE iso_code = 'TN' AND deleted = 0 ORDER BY id);
DECLARE @stdId   BIGINT = (SELECT id FROM [dbo].[working_time_regimes] WHERE pays_id = @paysId AND code = 'TN_STD');
DECLARE @sumId   BIGINT = (SELECT id FROM [dbo].[working_time_regimes] WHERE pays_id = @paysId AND code = 'TN_SUM');

/* Desired state, so re-running converges instead of appending duplicates. */
DECLARE @wanted TABLE (
    regime_id BIGINT, label_fr NVARCHAR(255), label_en NVARCHAR(255),
    t_start TIME(0), t_end TIME(0), duration_min INT, status_code VARCHAR(50), sort_order INT);

INSERT INTO @wanted VALUES
    (@stdId, N'Pause café du matin',   N'Morning break', '10:00:00', '10:15:00', 15, 'ON_BREAK',    0),
    (@stdId, N'Pause déjeuner',        N'Lunch break',   '12:30:00', '13:45:00', 75, 'LUNCH_BREAK', 1),
    (@stdId, N'Pause café de l''après-midi', N'Afternoon break', '16:00:00', '16:15:00', 15, 'ON_BREAK', 2),
    (@sumId, N'Pause',                 N'Break',         '11:30:00', '12:00:00', 30, 'ON_BREAK',    0);

/* Update rows that already exist at the same time on the same regime… */
UPDATE b
   SET b.label_fr        = w.label_fr,
       b.label_en        = w.label_en,
       b.break_time_end  = w.t_end,
       b.duration_min    = w.duration_min,
       b.status_code     = w.status_code,
       b.applies_to_days = 'WEEKDAYS',
       b.deduction_type  = 'AUTO',
       b.sort_order      = w.sort_order,
       b.is_active       = 1
  FROM [dbo].[break_templates] b
  JOIN @wanted w ON w.regime_id = b.regime_id AND b.break_time_start = w.t_start;

/* …and insert the ones that do not. */
INSERT INTO [dbo].[break_templates]
    (pays_id, regime_id, label_fr, label_en, deduction_type, duration_min,
     applies_to_days, min_work_hours_trigger, break_time_start, break_time_end,
     status_code, sort_order, is_active, created_at)
SELECT @paysId, w.regime_id, w.label_fr, w.label_en, 'AUTO', w.duration_min,
       'WEEKDAYS', NULL, w.t_start, w.t_end,
       w.status_code, w.sort_order, 1, SYSDATETIMEOFFSET()
FROM @wanted w
WHERE NOT EXISTS (SELECT 1 FROM [dbo].[break_templates] b
                  WHERE b.regime_id = w.regime_id AND b.break_time_start = w.t_start);

/* Retire any other break on these two regimes — a leftover window would fire a
   status change nobody asked for. Deactivated, not deleted, to keep history. */
UPDATE [dbo].[break_templates]
   SET is_active = 0
 WHERE regime_id IN (@stdId, @sumId)
   AND NOT EXISTS (SELECT 1 FROM @wanted w
                   WHERE w.regime_id = [dbo].[break_templates].regime_id
                     AND w.t_start   = [dbo].[break_templates].break_time_start);
GO

/* ── 5. Verification ──────────────────────────────────────────────────────── */
/* Still inside the NOEXEC guard: if the prerequisite check above aborted, these
   batches are skipped entirely rather than failing on a column that is not there
   yet — so the only message you see is the RAISERROR telling you to run V55. */
PRINT '--- Tunisia regimes ---';
SELECT r.id, r.code, r.label_fr, r.start_time, r.end_time,
       r.hours_per_week, r.days_per_week, r.break_duration_min,
       r.is_default, r.is_active, r.seasonal_from, r.seasonal_to,
       CASE WHEN r.seasonal_from IS NOT NULL
                 AND r.seasonal_from <= CAST(GETDATE() AS date)
                 AND (r.seasonal_to IS NULL OR r.seasonal_to >= CAST(GETDATE() AS date))
            THEN 'ACTIVE NOW (overrides everything)' ELSE '' END AS seasonal_state
FROM [dbo].[working_time_regimes] r
JOIN [dbo].[pays] p ON p.id = r.pays_id
WHERE p.iso_code = 'TN'
ORDER BY r.code;
GO

PRINT '--- Break windows, with the net worked minutes each regime implies ---';
SELECT r.code AS regime, b.label_fr, b.break_time_start, b.break_time_end,
       b.duration_min, b.status_code, b.applies_to_days, b.is_active
FROM [dbo].[break_templates] b
JOIN [dbo].[working_time_regimes] r ON r.id = b.regime_id
JOIN [dbo].[pays] p ON p.id = r.pays_id
WHERE p.iso_code = 'TN' AND r.code IN ('TN_STD', 'TN_SUM')
ORDER BY r.code, b.break_time_start;
GO

PRINT '--- Arithmetic check: window - breaks must equal hours_per_week / days_per_week ---';
SELECT r.code,
       DATEDIFF(MINUTE, CAST(r.start_time AS time), CAST(r.end_time AS time)) AS window_min,
       ISNULL((SELECT SUM(DATEDIFF(MINUTE, b.break_time_start, b.break_time_end))
               FROM [dbo].[break_templates] b
               WHERE b.regime_id = r.id AND b.is_active = 1
                 AND b.break_time_start IS NOT NULL), 0) AS break_min,
       DATEDIFF(MINUTE, CAST(r.start_time AS time), CAST(r.end_time AS time))
         - ISNULL((SELECT SUM(DATEDIFF(MINUTE, b.break_time_start, b.break_time_end))
                   FROM [dbo].[break_templates] b
                   WHERE b.regime_id = r.id AND b.is_active = 1
                     AND b.break_time_start IS NOT NULL), 0) AS net_worked_min,
       CAST(r.hours_per_week / NULLIF(r.days_per_week, 0) * 60 AS int) AS declared_daily_min,
       CASE WHEN DATEDIFF(MINUTE, CAST(r.start_time AS time), CAST(r.end_time AS time))
                 - ISNULL((SELECT SUM(DATEDIFF(MINUTE, b.break_time_start, b.break_time_end))
                           FROM [dbo].[break_templates] b
                           WHERE b.regime_id = r.id AND b.is_active = 1
                             AND b.break_time_start IS NOT NULL), 0)
                 = CAST(r.hours_per_week / NULLIF(r.days_per_week, 0) * 60 AS int)
            THEN 'OK' ELSE 'MISMATCH — the chronometer delta will be wrong' END AS VERDICT
FROM [dbo].[working_time_regimes] r
JOIN [dbo].[pays] p ON p.id = r.pays_id
WHERE p.iso_code = 'TN' AND r.code IN ('TN_STD', 'TN_SUM');
GO

SET NOEXEC OFF;
GO

/* =============================================================================
   AFTER RUNNING
   1. status_code values must exist and be ACTIVE in DAF360_LOG:
        SELECT [status], active FROM DAF360_LOG.dbo.status_definitions
         WHERE [status] IN ('ON_BREAK','LUNCH_BREAK');
   2. Confirm Tunisia's weekend is configured, or working days fall back to
      SAT+SUN by accident rather than by data:
        SELECT * FROM pays_weekends WHERE pays_id =
          (SELECT id FROM pays WHERE iso_code = 'TN');
   3. The seasonal window is a fixed date range, NOT a recurring rule. On
      15 Aug 2026 TN_SUM stops applying and everyone falls back to TN_STD, which
      is correct — but next July someone must move the dates forward:
        UPDATE working_time_regimes
           SET seasonal_from = '2027-07-20', seasonal_to = '2027-08-14'
         WHERE code = 'TN_SUM'
           AND pays_id = (SELECT id FROM pays WHERE iso_code = 'TN');
      Tell me if you want a recurring (month/day) seasonal model instead — that
      is a schema change, not a data one.
   4. Unrelated leftovers on the Egypt regime EGY1 that this script does NOT
      touch: a junk break named 'test' (12:15–12:30) and EGY1 being flagged as
      Tunisia's default until step 3 above cleared it.
   ============================================================================= */
