-- =============================================================================
-- V91__notification_catalogue_cleanup.sql
--
-- Removes two things from the notification catalogue that are configurable in the
-- admin screen but can never actually send anything. Both mislead an admin into
-- tuning notifications that no code will ever raise.
--
--   1. MISSION_APPROVED — created from a documentation example, never wired.
--   2. LEAVE_SUBMITTED / LEAVE_APPROVED / LEAVE_REJECTED — seeded by V12 for a
--      leave flow this service does not own (dbo.absences is written by the
--      timesheet application, which is a separate integration).
--
-- Safe to re-run. Deletes nothing that any notification row depends on:
-- [dbo].[notifications] stores resolved text, never a reference to an event type.
-- =============================================================================

USE [DAF360_HR];

-- ============================================================================
-- 1. MISSION_APPROVED — remove entirely
--
-- No producer calls resolveAndDispatch("MISSION_APPROVED"), no routing rule
-- exists for it, and its 'MISSION' default_entity_type is not a value of the
-- NotificationEntityType enum, so its notifications could not deep-link either.
--
-- If mission notifications ARE wanted later, this is the recipe rather than a
-- loss: add MISSION to NotificationEntityType, map it in the shell's
-- notification-display.ts, re-insert the event type, create its rule from the
-- admin screen, and dispatch it from the missions service.
-- ============================================================================

DELETE r
FROM [dbo].[notification_routing_recipients] r
JOIN [dbo].[notification_routing_rules] ru ON ru.id = r.routing_rule_id
JOIN [dbo].[notification_event_types] et ON et.id = ru.event_type_id
WHERE et.event_code = 'MISSION_APPROVED';

DELETE e
FROM [dbo].[email_routing_recipients] e
JOIN [dbo].[notification_routing_rules] ru ON ru.id = e.routing_rule_id
JOIN [dbo].[notification_event_types] et ON et.id = ru.event_type_id
WHERE et.event_code = 'MISSION_APPROVED';

DELETE ru
FROM [dbo].[notification_routing_rules] ru
JOIN [dbo].[notification_event_types] et ON et.id = ru.event_type_id
WHERE et.event_code = 'MISSION_APPROVED';

DELETE FROM [dbo].[notification_event_types] WHERE event_code = 'MISSION_APPROVED';
PRINT 'Removed event type MISSION_APPROVED (if present).';

-- ============================================================================
-- 2. LEAVE_* — deactivate, do not delete
--
-- Deactivating rather than deleting on purpose: unlike MISSION_APPROVED these
-- were a deliberate design decision, their rules carry hand-written templates,
-- and the leave flow may well come back with the timesheet integration. is_active=0
-- hides them from the admin list (EVENT_TYPES_SQL filters on it) while keeping
-- the templates and recipient roles intact for that day.
-- ============================================================================

UPDATE [dbo].[notification_event_types]
   SET is_active = 0
 WHERE event_code IN ('LEAVE_SUBMITTED', 'LEAVE_APPROVED', 'LEAVE_REJECTED')
   AND is_active = 1;
PRINT 'Deactivated LEAVE_* event types (dormant until leave is owned by this service).';

-- ── Verify ───────────────────────────────────────────────────────────────────
-- Expect: 5 active events, all with a producer in rh-service.
SELECT event_code, module, default_entity_type, is_active
FROM [dbo].[notification_event_types]
ORDER BY is_active DESC, event_code;

PRINT 'V91 complete.';
