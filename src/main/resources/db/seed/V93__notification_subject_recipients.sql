-- =============================================================================
-- V93__notification_subject_recipients.sql
--
-- Makes "the person concerned" a configurable recipient instead of a hardcoded
-- bypass.
--
-- BEFORE: RoutingContext.directUserId RETURNED EARLY from recipient resolution.
-- Three events (ONBOARDING_COMPLETED, REQUEST_APPROVED, REQUEST_REJECTED) went to
-- one user and to nobody else, whatever the rule said — which left the admin
-- screen's Destinataires section completely dead for them, and made
-- "the employee AND their manager" impossible to express.
--
-- AFTER: two new recipient modes resolve from the dispatch rather than from a role:
--   SUBJECT            → the one user the event is about
--   MANAGER_OF_SUBJECT → holders of the parent of THAT user's role, same entity
--
-- This migration gives those three events an explicit SUBJECT recipient, so
-- behaviour is unchanged while the section becomes live and extendable.
--
-- Safe to re-run.
-- =============================================================================

USE [DAF360_HR];

-- ── 1. SUBJECT recipients for the three subject-driven events ────────────────
-- role_id and permission_code stay NULL: a context-based mode has no configured
-- target, and storing one would be a lie about who receives the notification.

DECLARE @subjectEvents TABLE (code VARCHAR(100), with_email BIT);

INSERT INTO @subjectEvents (code, with_email) VALUES
 ('ONBOARDING_COMPLETED', 1),   -- welcome mail already went to the new employee
 ('REQUEST_APPROVED',     0),   -- in-app only: this event has no e-mail template
 ('REQUEST_REJECTED',     0);

INSERT INTO [dbo].[notification_routing_recipients]
    (routing_rule_id, role_id, recipient_mode, permission_code, is_active)
SELECT ru.id, NULL, 'SUBJECT', NULL, 1
FROM @subjectEvents se
JOIN [dbo].[notification_event_types] et ON et.event_code = se.code
JOIN [dbo].[notification_routing_rules] ru
     ON ru.event_type_id = et.id AND ru.is_active = 1
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_recipients] nr
    WHERE nr.routing_rule_id = ru.id AND nr.recipient_mode = 'SUBJECT');

PRINT 'Added SUBJECT in-app recipients for the three subject-driven events.';

-- ONBOARDING_COMPLETED also mailed the new employee, so it needs a SUBJECT TO row.
INSERT INTO [dbo].[email_routing_recipients]
    (routing_rule_id, role_id, recipient_field, recipient_mode, permission_code, is_active)
SELECT ru.id, NULL, 'TO', 'SUBJECT', NULL, 1
FROM @subjectEvents se
JOIN [dbo].[notification_event_types] et ON et.event_code = se.code
JOIN [dbo].[notification_routing_rules] ru
     ON ru.event_type_id = et.id AND ru.is_active = 1
WHERE se.with_email = 1
  AND NOT EXISTS (
    SELECT 1 FROM [dbo].[email_routing_recipients] er
    WHERE er.routing_rule_id = ru.id AND er.recipient_mode = 'SUBJECT'
      AND er.recipient_field = 'TO');

PRINT 'Added SUBJECT email recipient for ONBOARDING_COMPLETED.';
GO

-- ── 2. Verify ───────────────────────────────────────────────────────────────
-- Every active rule with its recipients, mode by mode. The three events above
-- must each show exactly one SUBJECT row.
SELECT et.event_code,
       nr.recipient_mode,
       nr.role_id,
       r.frenchName AS role_name,
       nr.permission_code,
       COUNT(*) OVER (PARTITION BY et.event_code) AS recipients_on_event
FROM [dbo].[notification_event_types] et
JOIN [dbo].[notification_routing_rules] ru
     ON ru.event_type_id = et.id AND ru.is_active = 1
LEFT JOIN [dbo].[notification_routing_recipients] nr
     ON nr.routing_rule_id = ru.id AND nr.is_active = 1
LEFT JOIN [dbo].[Roles] r ON r.id = nr.role_id
WHERE et.is_active = 1
ORDER BY et.event_code, nr.recipient_mode;

-- ── 3. What you can now do from the admin screen ────────────────────────────
--
-- On ANY event, « Destinataires in-app » → « Ajouter » → Cibler :
--
--   « La personne concernée »        → the employee the event is about
--   « Le manager de la personne concernée » → their hierarchical manager
--
-- Both need no role and no permission: they are resolved from the event itself.
--
-- Two notes:
--   * They only work on events whose dispatch supplies a subject. Today that is
--     the three above; any new event should pass subjectUserId when it concerns
--     one person in particular.
--   * MANAGER_OF_SUBJECT does NOT fall back to the subject when no manager is
--     found — telling someone "your manager was informed" by informing them
--     instead would be actively misleading. It logs a warning and sends nothing.

PRINT 'V93 complete.';
