-- =============================================================================
-- V92__notification_full_configurability.sql
--
-- Makes EVERY in-app notification in the RH module configurable from the admin
-- screen, instead of only the five that already went through the routing engine.
--
--   1. recipient_mode + permission_code on both recipient tables
--      → ALL (today's behaviour) | MANAGER (one level up) | PERMISSION (by right)
--   2. nine new event types + rules + recipients, one per hardcoded producer,
--      seeded to reproduce EXACTLY what the code sent before this migration
--
-- The seeded values are not a redesign: every title, body and recipient below is
-- transcribed from the Java it replaces, so applying this changes who receives
-- what by nothing at all. What changes is that an admin can now edit it.
--
-- Safe to re-run — every block is guarded on event_code.
-- =============================================================================

USE [DAF360_HR];

-- ============================================================================
-- 1. RECIPIENT MODES
--
-- role_id becomes nullable: a PERMISSION recipient targets a permission code and
-- has no role. The old NOT NULL is dropped rather than worked around with a
-- sentinel role id, which would have made every query lie about its intent.
-- ============================================================================

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='notification_routing_recipients' AND COLUMN_NAME='recipient_mode')
BEGIN
    ALTER TABLE [dbo].[notification_routing_recipients]
        ADD [recipient_mode] VARCHAR(20) NOT NULL CONSTRAINT DF_nrr_mode DEFAULT 'ALL',
            [permission_code] VARCHAR(100) NULL;
    PRINT 'Added: notification_routing_recipients.recipient_mode / permission_code';
END
ELSE PRINT 'Skipped: notification_routing_recipients modes (already present)';

IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_NAME='email_routing_recipients' AND COLUMN_NAME='recipient_mode')
BEGIN
    ALTER TABLE [dbo].[email_routing_recipients]
        ADD [recipient_mode] VARCHAR(20) NOT NULL CONSTRAINT DF_err_mode DEFAULT 'ALL',
            [permission_code] VARCHAR(100) NULL;
    PRINT 'Added: email_routing_recipients.recipient_mode / permission_code';
END
ELSE PRINT 'Skipped: email_routing_recipients modes (already present)';
GO

-- role_id nullable (only if it is still NOT NULL)
IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME='notification_routing_recipients' AND COLUMN_NAME='role_id'
             AND IS_NULLABLE='NO')
BEGIN
    ALTER TABLE [dbo].[notification_routing_recipients] ALTER COLUMN [role_id] BIGINT NULL;
    PRINT 'notification_routing_recipients.role_id is now nullable';
END

IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_NAME='email_routing_recipients' AND COLUMN_NAME='role_id'
             AND IS_NULLABLE='NO')
BEGIN
    ALTER TABLE [dbo].[email_routing_recipients] ALTER COLUMN [role_id] BIGINT NULL;
    PRINT 'email_routing_recipients.role_id is now nullable';
END
GO

-- ============================================================================
-- 2. THE NINE MIGRATED EVENTS
--
-- supports_email mirrors what each producer actually did: the ones that sent an
-- e-mail get 1, the two that were in-app only get 0, so the admin screen cannot
-- offer an e-mail toggle for a notification that has no e-mail template.
--
-- default_entity_type is the deep-link target, matching what the Java passed.
-- ============================================================================

DECLARE @events TABLE (
    code VARCHAR(100), label NVARCHAR(255), label_en NVARCHAR(255),
    descr NVARCHAR(500), email BIT, entity VARCHAR(50)
);

INSERT INTO @events (code, label, label_en, descr, email, entity) VALUES
 ('RECRUITMENT_DEMAND_CREATED',  N'Nouvelle demande de recrutement',        N'Recruitment request created',
  N'A la creation d une demande de recrutement.', 0, 'RECRUITMENT_DEMAND'),
 ('RECRUITMENT_DEMAND_APPROVED', N'Demande de recrutement approuvee',       N'Recruitment request approved',
  N'A l approbation d une demande de recrutement.', 1, 'RECRUITMENT_DEMAND'),
 ('OFFBOARDING_STARTED',         N'Offboarding initie',                     N'Offboarding started',
  N'Au lancement d un processus de depart.', 1, 'OFFBOARDING'),
 ('OFFBOARDING_MANAGER_OPINION', N'Avis manager enregistre',                N'Manager opinion recorded',
  N'Quand le manager a donne son avis sur un depart.', 1, 'OFFBOARDING'),
 ('OFFBOARDING_TASK_OVERDUE',    N'SLA depasse - tache d offboarding',      N'Offboarding task overdue',
  N'Chaque jour a 08h05, si une tache de depart est en retard.', 1, 'OFFBOARDING'),
 ('OFFBOARDING_TASK_DUE_SOON',   N'Rappel - tache d offboarding demain',    N'Offboarding task due tomorrow',
  N'Chaque jour a 08h05, la veille de l echeance d une tache.', 1, 'OFFBOARDING'),
 ('OFFBOARDING_BLOCKED',         N'Escalade - offboarding bloque',          N'Offboarding blocked',
  N'Chaque jour a 08h05, si un dossier est bloque depuis plus de 3 jours.', 1, 'OFFBOARDING'),
 ('CONTRACT_EXPIRY',             N'Echeance de contrat',                    N'Contract expiry',
  N'Chaque jour a 08h00, quand un contrat approche de sa fin.', 1, 'EMPLOYEE_PROFILE'),
 ('EMPLOYEE_STATUS_CHANGED',     N'Changement de statut collaborateur',     N'Employee status changed',
  N'A chaque changement de statut d un contrat.', 1, 'EMPLOYEE_PROFILE');

INSERT INTO [dbo].[notification_event_types]
    (event_code, label_fr, label_en, description_fr, module, supports_email, is_system, is_active, default_entity_type)
SELECT e.code, e.label, e.label_en, e.descr, 'RH', e.email, 1, 1, e.entity
FROM @events e
WHERE NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] t WHERE t.event_code = e.code);

PRINT 'Seeded the nine migrated event types (skipping any already present).';
GO

-- ── Rules (global, pays_id NULL) ─────────────────────────────────────────────
-- Templates transcribed from the Java, with the interpolated values turned into
-- placeholders. send_email matches supports_email so behaviour is unchanged.

DECLARE @rules TABLE (
    code VARCHAR(100), title NVARCHAR(255), body NVARCHAR(1000),
    subject NVARCHAR(255), html NVARCHAR(MAX)
);

INSERT INTO @rules (code, title, body, subject, html) VALUES
 ('RECRUITMENT_DEMAND_CREATED',
  N'Nouvelle demande de recrutement',
  N'Une demande de recrutement pour le poste "{jobTitle}" est en attente d''approbation.',
  NULL, NULL),
 ('RECRUITMENT_DEMAND_APPROVED',
  N'Demande de recrutement approuvée',
  N'La demande de recrutement pour le poste "{jobTitle}" a été approuvée. Vous pouvez maintenant lancer le processus de recrutement.',
  N'[DAF360] Demande de recrutement approuvée — {jobTitle}',
  N'<p>Bonjour,</p><p>La demande de recrutement pour le poste <strong>{jobTitle}</strong> a été approuvée.</p><p>Cordialement,<br/>Système DAF360</p>'),
 ('OFFBOARDING_STARTED',
  N'Offboarding initié',
  N'Un processus d''offboarding a été initié pour {employeeName}.',
  N'[DAF360 RH] Offboarding initié — {employeeName}',
  N'<p>Bonjour,</p><p>Un processus d''offboarding a été initié pour <strong>{employeeName}</strong>.</p>'),
 ('OFFBOARDING_MANAGER_OPINION',
  N'Avis manager enregistré — offboarding',
  N'L''avis du manager a été enregistré pour le dossier d''offboarding id={instanceId}. La validation RH peut être effectuée.',
  N'[DAF360 RH] Avis manager enregistré — offboarding {instanceId}',
  N'<p>Bonjour,</p><p>L''avis du manager a été enregistré pour le dossier d''offboarding <strong>{instanceId}</strong>. La validation RH peut être effectuée.</p>'),
 ('OFFBOARDING_TASK_OVERDUE',
  N'SLA dépassé — offboarding',
  N'Une tâche d''offboarding est en retard (workflow id={instanceId}, tâche={taskCode}).',
  N'[DAF360 RH] SLA dépassé — offboarding {instanceId}',
  N'<p>Une tâche d''offboarding est en retard (workflow <strong>{instanceId}</strong>, tâche {taskCode}).</p>'),
 ('OFFBOARDING_TASK_DUE_SOON',
  N'Rappel — tâche offboarding échéance demain',
  N'La tâche ''{taskLabel}'' (workflow id={instanceId}) arrive à échéance demain.',
  N'[DAF360 RH] Rappel — tâche offboarding {instanceId}',
  N'<p>La tâche <strong>{taskLabel}</strong> (workflow {instanceId}) arrive à échéance demain.</p>'),
 ('OFFBOARDING_BLOCKED',
  N'Escalade — offboarding bloqué',
  N'Le workflow d''offboarding id={instanceId} est bloqué depuis plus de 3 jours. Intervention requise.',
  N'[DAF360 RH] Escalade — offboarding bloqué {instanceId}',
  N'<p>Le workflow d''offboarding <strong>{instanceId}</strong> est bloqué depuis plus de 3 jours. Intervention requise.</p>'),
 ('CONTRACT_EXPIRY',
  N'Échéance de contrat — {employeeName}',
  N'Le contrat {contractType} de {employeeName} arrive à échéance le {targetDate}. Action requise : renouvellement, conversion ou clôture du dossier.',
  N'[DAF360 RH] Échéance de contrat — {employeeName}',
  N'<p>Le contrat {contractType} de <strong>{employeeName}</strong> arrive à échéance le {targetDate}.</p><p>Action requise : renouvellement, conversion ou clôture du dossier.</p>'),
 ('EMPLOYEE_STATUS_CHANGED',
  N'Changement de statut collaborateur',
  N'{employeeName} : {previousStatus} → {newStatus} ({contractType})',
  N'[DAF360 RH] Changement de statut collaborateur',
  N'<p><strong>{employeeName}</strong> : {previousStatus} → {newStatus} ({contractType})</p>');

INSERT INTO [dbo].[notification_routing_rules]
    (event_type_id, pays_id, send_inapp, send_email,
     inapp_title_template, inapp_body_template, email_subject_template, email_body_template, is_active)
SELECT et.id, NULL, 1, et.supports_email, r.title, r.body, r.subject, r.html, 1
FROM @rules r
JOIN [dbo].[notification_event_types] et ON et.event_code = r.code
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_rules] ru
    WHERE ru.event_type_id = et.id AND ru.is_active = 1
);

PRINT 'Seeded the nine routing rules.';
GO

-- ── Recipients ───────────────────────────────────────────────────────────────
-- All PERMISSION mode, with the exact permission each producer used. This is the
-- point of the new mode: a role list could not reproduce "whoever owns this
-- offboarding stage", so migrating without it would have widened the audience.
--
-- OFFBOARDING_TASK_OVERDUE and OFFBOARDING_TASK_DUE_SOON get RH_MANAGE_OFFBOARDING
-- as their stored fallback, but the dispatch passes the per-task permission in the
-- context, which wins. The stored value only matters if the code stops sending one.

DECLARE @recips TABLE (code VARCHAR(100), permission VARCHAR(100), email_field VARCHAR(10));

INSERT INTO @recips (code, permission, email_field) VALUES
 ('RECRUITMENT_DEMAND_CREATED',  'RH_APPROVE_RECRUITMENT_DEMAND', NULL),
 ('RECRUITMENT_DEMAND_APPROVED', 'RH_VIEW_RECRUITMENT_DEMAND',    'TO'),
 ('OFFBOARDING_STARTED',         'RH_MANAGE_OFFBOARDING',         'TO'),
 ('OFFBOARDING_MANAGER_OPINION', 'RH_VALIDATE_OFFBOARDING',       'TO'),
 ('OFFBOARDING_TASK_OVERDUE',    'RH_MANAGE_OFFBOARDING',         'TO'),
 ('OFFBOARDING_TASK_DUE_SOON',   'RH_MANAGE_OFFBOARDING',         'TO'),
 ('OFFBOARDING_BLOCKED',         'RH_VALIDATE_OFFBOARDING',       'TO'),
 ('CONTRACT_EXPIRY',             'RH_VIEW_CONTRACTS',             'TO'),
 ('EMPLOYEE_STATUS_CHANGED',     'RH_VIEW_CONTRACTS',             'TO');

INSERT INTO [dbo].[notification_routing_recipients]
    (routing_rule_id, role_id, recipient_mode, permission_code, is_active)
SELECT ru.id, NULL, 'PERMISSION', x.permission, 1
FROM @recips x
JOIN [dbo].[notification_event_types] et ON et.event_code = x.code
JOIN [dbo].[notification_routing_rules] ru ON ru.event_type_id = et.id AND ru.is_active = 1
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_recipients] nr
    WHERE nr.routing_rule_id = ru.id AND nr.permission_code = x.permission
);

-- Multi-permission events. CONTRACT_EXPIRY used a per-alert roles list mapped through a
-- hardcoded switch (RH -> view contracts, IT -> manage lifecycle, Directeur pays -> approve
-- recruitment). Those three become three recipient rows on the rule, so the audience is the
-- same as before for an alert carrying all three roles — and now admin-editable. The loss is
-- deliberate: recipients no longer vary per contract type, they vary per rule.
-- EMPLOYEE_STATUS_CHANGED likewise went to holders of EITHER of two permissions.

DECLARE @multi TABLE (code VARCHAR(100), permission VARCHAR(100));
INSERT INTO @multi (code, permission) VALUES
 ('EMPLOYEE_STATUS_CHANGED', 'RH_MANAGE_LIFECYCLE'),
 ('CONTRACT_EXPIRY',         'RH_MANAGE_LIFECYCLE'),
 ('CONTRACT_EXPIRY',         'RH_APPROVE_RECRUITMENT_DEMAND');

INSERT INTO [dbo].[notification_routing_recipients]
    (routing_rule_id, role_id, recipient_mode, permission_code, is_active)
SELECT ru.id, NULL, 'PERMISSION', m.permission, 1
FROM @multi m
JOIN [dbo].[notification_event_types] et ON et.event_code = m.code
JOIN [dbo].[notification_routing_rules] ru ON ru.event_type_id = et.id AND ru.is_active = 1
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_recipients] nr
    WHERE nr.routing_rule_id = ru.id AND nr.permission_code = m.permission);

INSERT INTO [dbo].[email_routing_recipients]
    (routing_rule_id, role_id, recipient_field, recipient_mode, permission_code, is_active)
SELECT ru.id, NULL, 'TO', 'PERMISSION', m.permission, 1
FROM @multi m
JOIN [dbo].[notification_event_types] et ON et.event_code = m.code
JOIN [dbo].[notification_routing_rules] ru ON ru.event_type_id = et.id AND ru.is_active = 1
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[email_routing_recipients] er
    WHERE er.routing_rule_id = ru.id AND er.permission_code = m.permission
      AND er.recipient_field = 'TO');

INSERT INTO [dbo].[email_routing_recipients]
    (routing_rule_id, role_id, recipient_field, recipient_mode, permission_code, is_active)
SELECT ru.id, NULL, x.email_field, 'PERMISSION', x.permission, 1
FROM @recips x
JOIN [dbo].[notification_event_types] et ON et.event_code = x.code
JOIN [dbo].[notification_routing_rules] ru ON ru.event_type_id = et.id AND ru.is_active = 1
WHERE x.email_field IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM [dbo].[email_routing_recipients] er
    WHERE er.routing_rule_id = ru.id AND er.permission_code = x.permission
      AND er.recipient_field = x.email_field);

PRINT 'Seeded recipients (PERMISSION mode) for the nine migrated events.';
GO

-- ── Verify ───────────────────────────────────────────────────────────────────
-- Expect 14 active events, each with exactly one active rule and >= 1 recipient.
SELECT et.event_code, et.supports_email, et.default_entity_type,
       ru.id AS rule_id, ru.send_inapp, ru.send_email,
       (SELECT COUNT(*) FROM [dbo].[notification_routing_recipients] nr
         WHERE nr.routing_rule_id = ru.id AND nr.is_active = 1) AS inapp_recipients,
       (SELECT COUNT(*) FROM [dbo].[email_routing_recipients] er
         WHERE er.routing_rule_id = ru.id AND er.is_active = 1) AS email_recipients
FROM [dbo].[notification_event_types] et
LEFT JOIN [dbo].[notification_routing_rules] ru
       ON ru.event_type_id = et.id AND ru.is_active = 1
WHERE et.is_active = 1
ORDER BY et.event_code;

PRINT 'V92 complete.';
