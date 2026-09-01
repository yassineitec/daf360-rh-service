-- =============================================================================
-- dev_seed_notifications_user207.sql
--
-- DEV/TEST DATA — not a migration. Seeds 7 notifications for user_id 207 that
-- exercise every case the header panel has to handle:
--   * newest-first ordering across several days
--   * unread (5) vs read (2) — the unread rail/bold treatment
--   * two modules — the module chip and the byModule unread counts
--   * three deep-link states: valid target, no target, unknown kind
--
-- Prints the id watermark at the end so the cleanup at the bottom can remove
-- exactly these rows and nothing else.
-- =============================================================================

USE [DAF360_HR];

-- ── Pre-checks ───────────────────────────────────────────────────────────────
-- user_id is FK-constrained to dbo.Users: the inserts fail outright if 207 is
-- not a real user, rather than creating orphan rows.
IF NOT EXISTS (SELECT 1 FROM [dbo].[Users] WHERE id = 207)
BEGIN
    RAISERROR('User 207 does not exist — aborting.', 16, 1);
    RETURN;
END

SELECT id, COALESCE(username, email) AS login, pays_id, role_id
FROM [dbo].[Users] WHERE id = 207;

SELECT COUNT(*) AS notifications_already_held FROM [dbo].[notifications] WHERE user_id = 207;

-- Watermark: everything inserted below has a higher id.
DECLARE @before BIGINT = ISNULL((SELECT MAX(id) FROM [dbo].[notifications]), 0);

-- ── Seed ─────────────────────────────────────────────────────────────────────
INSERT INTO [dbo].[notifications]
    (user_id, module, title, message, is_read, created_at, entity_type, entity_id, link)
VALUES
-- 1. UNREAD · 5 min ago · valid deep link
(207, 'RH',
 N'Nouveau candidat accepté — action requise',
 N'Le candidat Alice Martin a été accepté. Veuillez créer son compte Microsoft 365.',
 0, DATEADD(MINUTE, -5, SYSDATETIMEOFFSET()), 'CANDIDATE', 482, NULL),

-- 2. UNREAD · 2 h ago · valid deep link
(207, 'RH',
 N'SLA dépassé — offboarding',
 N'Une tâche d''offboarding est en retard (workflow id=117, tâche=RETOUR_MATERIEL).',
 0, DATEADD(HOUR, -2, SYSDATETIMEOFFSET()), 'OFFBOARDING', 117, NULL),

-- 3. UNREAD · 6 h ago · second module, so the chip and byModule counts differ
(207, 'POINTAGE',
 N'Pointage manquant',
 N'Aucune sortie enregistrée pour la journée du 30/08.',
 0, DATEADD(HOUR, -6, SYSDATETIMEOFFSET()), NULL, NULL, NULL),

-- 4. UNREAD · yesterday · valid deep link
(207, 'RH',
 N'Nouvelle demande de recrutement',
 N'Une demande de recrutement pour le poste "Ingénieur QHSE" est en attente d''approbation.',
 0, DATEADD(DAY, -1, SYSDATETIMEOFFSET()), 'RECRUITMENT_DEMAND', 34, NULL),

-- 5. UNREAD · 2 days ago · UNKNOWN entity kind.
--    'MISSION' is not in NotificationEntityType, so fromNullable() returns null and the
--    row must render as non-clickable instead of breaking the list. This is the
--    forward-compatibility path: a module we have not integrated yet writing its own kind.
(207, 'RH',
 N'Ordre de mission approuvé',
 N'Votre ordre de mission vers Tunis (02/09 – 06/09) a été approuvé.',
 0, DATEADD(DAY, -2, SYSDATETIMEOFFSET()), 'MISSION', 9, NULL),

-- 6. READ · 3 days ago · valid deep link — read rows keep their chip and target,
--    they just lose the rail and the bold title.
(207, 'RH',
 N'Changement de statut collaborateur',
 N'Jean Dupont : ACTIVE → NOTICE_PERIOD (CDI).',
 1, DATEADD(DAY, -3, SYSDATETIMEOFFSET()), 'EMPLOYEE_PROFILE', 58, NULL),

-- 7. READ · 5 days ago · no target at all — the ordinary "points nowhere" case,
--    which is what every row written before V90 looks like.
(207, 'RH',
 N'Rapport hebdomadaire disponible',
 N'Le rapport RH de la semaine 34 est disponible.',
 1, DATEADD(DAY, -5, SYSDATETIMEOFFSET()), NULL, NULL, NULL);

PRINT 'Seeded 7 notifications for user 207.';
SELECT @before AS delete_rows_with_id_greater_than;

-- ── Verify ───────────────────────────────────────────────────────────────────
-- Should read newest-first, 5 unread / 2 read, modules RH + POINTAGE.
SELECT id, module, title, is_read, entity_type, entity_id, created_at
FROM [dbo].[notifications]
WHERE user_id = 207
ORDER BY created_at DESC;

-- What GET /api/hr/notifications/unread-count should answer:
--   { "count": 5, "byModule": { "POINTAGE": 1, "RH": 4 } }
SELECT module, COUNT(*) AS unread
FROM [dbo].[notifications]
WHERE user_id = 207 AND is_read = 0
GROUP BY module;

-- ── Cleanup (run later, with the watermark printed above) ────────────────────
-- DELETE FROM [dbo].[notifications] WHERE user_id = 207 AND id > <watermark>;
