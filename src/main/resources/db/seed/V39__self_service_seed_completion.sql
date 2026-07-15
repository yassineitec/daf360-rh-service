-- =============================================================================
-- V39: Self Service seed completion
--
-- Fills the three gaps left by V12/V19:
--   1. 13 missing request types seeded for ALL pays (cross-join, idempotent)
--   2. REQUEST_SUBMITTED + REQUEST_INFO_NEEDED notification event types + routing
--   3. HR_MANAGE_EMPLOYEE_REQUESTS + RH_APPROVE_SENSITIVE_REQUESTS assigned to roles
--
-- Safe to re-run — every block is guarded with IF NOT EXISTS / NOT EXISTS checks.
-- =============================================================================

USE [DAF360_HR];

-- ============================================================================
-- STEP 0: Drop stale CHECK constraints that would block later inserts.
--
-- CK_RolePermissions_Permission: hardcoded permission allowlist from V32 —
--   does not include the self-service permissions inserted in Step 3.
--
-- CK_ReqTypeCatalog_Category: old enum (BENEFIT/ADMINISTRATIVE/DOCUMENT/
--   PERSONAL_DATA) — does not include PERSONAL_DATA_CHANGE, BANK_DETAILS,
--   CAREER, OTHER used in Step 1. Entity enum is the source of truth.
-- ============================================================================
IF EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'CK_RolePermissions_Permission'
      AND parent_object_id = OBJECT_ID('dbo.RolePermissions')
)
    ALTER TABLE [dbo].[RolePermissions] DROP CONSTRAINT CK_RolePermissions_Permission;

IF EXISTS (
    SELECT 1 FROM sys.check_constraints
    WHERE name = 'CK_ReqTypeCatalog_Category'
      AND parent_object_id = OBJECT_ID('dbo.request_type_catalog')
)
    ALTER TABLE [dbo].[request_type_catalog] DROP CONSTRAINT CK_ReqTypeCatalog_Category;

PRINT 'Step 0 complete — stale CHECK constraints dropped (if they existed).';

-- ============================================================================
-- STEP 1: Seed missing request types for all pays
-- V19 seeded only 5 DOCUMENT types for pays_id 179+53.
-- This adds the remaining 13 DEFAULT_TYPES for every pays in the system.
-- ============================================================================

DECLARE @types TABLE (
    type_code        NVARCHAR(100) NOT NULL,
    display_name_fr  NVARCHAR(255) NOT NULL,
    display_name_en  NVARCHAR(255) NOT NULL,
    category         VARCHAR(50)   NOT NULL,
    approval_level   CHAR(2)       NOT NULL,
    default_sla_days INT           NOT NULL
);

INSERT INTO @types (type_code, display_name_fr, display_name_en, category, approval_level, default_sla_days) VALUES
-- DOCUMENT types missing from V19
(N'BULLETIN_PAIE',           N'Bulletin de paie',                       N'Pay Slip',                      'DOCUMENT',             'L1', 3),
(N'ATTESTATION_ANCIENNETE',  N'Attestation d''ancienneté',               N'Seniority Certificate',         'DOCUMENT',             'L1', 3),
(N'ATTESTATION_CONGE',       N'Attestation de congé annuel',             N'Annual Leave Certificate',      'DOCUMENT',             'L1', 2),
-- PERSONAL_DATA_CHANGE (all 5)
(N'CHANGEMENT_ADRESSE',      N'Changement d''adresse',                   N'Address Change',                'PERSONAL_DATA_CHANGE', 'L1', 5),
(N'CHANGEMENT_EMAIL',        N'Changement d''email professionnel',        N'Professional Email Change',     'PERSONAL_DATA_CHANGE', 'L1', 3),
(N'CHANGEMENT_TELEPHONE',    N'Changement de numéro de téléphone',        N'Phone Number Change',           'PERSONAL_DATA_CHANGE', 'L1', 3),
(N'CHANGEMENT_URGENCE',      N'Mise à jour contact d''urgence',           N'Emergency Contact Update',      'PERSONAL_DATA_CHANGE', 'L1', 3),
(N'CHANGEMENT_PHOTO',        N'Mise à jour photo de profil',              N'Profile Photo Update',          'PERSONAL_DATA_CHANGE', 'L1', 3),
-- BANK_DETAILS — L2 approval required, 5-day SLA
(N'MISE_A_JOUR_BANCAIRE',    N'Mise à jour coordonnées bancaires',        N'Bank Details Update',           'BANK_DETAILS',         'L2', 5),
-- CAREER
(N'DEMANDE_FORMATION',       N'Demande de formation',                     N'Training Request',              'CAREER',               'L1', 7),
(N'MUTATION_INTERNE',        N'Demande de mutation interne',              N'Internal Transfer Request',     'CAREER',               'L1', 10),
(N'TELETRAVAIL_PONCTUEL',    N'Demande de télétravail ponctuel',          N'Remote Work Request',           'CAREER',               'L1', 5),
-- OTHER
(N'AUTRE',                   N'Autre demande',                            N'Other Request',                 'OTHER',                'L1', 5);

-- Cross-join: insert each type for every pays that doesn't already have it
INSERT INTO [dbo].[request_type_catalog]
    (pays_id, type_code, display_name_fr, display_name_en, category, approval_level, default_sla_days, is_active, created_at)
SELECT
    p.id,
    t.type_code,
    t.display_name_fr,
    t.display_name_en,
    t.category,
    t.approval_level,
    t.default_sla_days,
    1,
    SYSDATETIMEOFFSET()
FROM [dbo].[pays] p
CROSS JOIN @types t
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[request_type_catalog] rtc
    WHERE rtc.pays_id = p.id AND rtc.type_code = t.type_code
);

PRINT 'Step 1 complete — 13 missing request types seeded for all pays.';

-- ============================================================================
-- STEP 2: Add REQUEST_SUBMITTED and REQUEST_INFO_NEEDED event types + routing
-- V12 seeded REQUEST_APPROVED and REQUEST_REJECTED but not these two.
-- ============================================================================

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code = 'REQUEST_SUBMITTED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code, label_fr, label_en, description_fr, module, supports_email, is_system)
    VALUES (
        'REQUEST_SUBMITTED',
        N'Demande RH soumise',
        N'HR request submitted',
        N'Déclenché quand un employé soumet une demande de service',
        'HR', 1, 1
    );

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code = 'REQUEST_INFO_NEEDED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code, label_fr, label_en, description_fr, module, supports_email, is_system)
    VALUES (
        'REQUEST_INFO_NEEDED',
        N'Informations complémentaires requises',
        N'Additional information needed',
        N'Déclenché quand un officier RH demande des informations complémentaires',
        'HR', 0, 1
    );

-- Routing rule: REQUEST_SUBMITTED → notify HR managers (in-app + email)
IF NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_rules] rr
    JOIN  [dbo].[notification_event_types] et ON et.id = rr.event_type_id
    WHERE et.event_code = 'REQUEST_SUBMITTED' AND rr.pays_id IS NULL
)
    INSERT INTO [dbo].[notification_routing_rules]
        (event_type_id, pays_id, send_inapp, send_email,
         inapp_title_template, inapp_body_template,
         email_subject_template, email_body_template)
    SELECT et.id, NULL, 1, 1,
        N'Nouvelle demande RH à traiter',
        N'Une nouvelle demande de {requestType} a été soumise. Veuillez la traiter dans DAF360.',
        N'[DAF360] Nouvelle demande RH — {requestType}',
        N'<p>Bonjour,</p><p>Un employé a soumis une demande de <strong>{requestType}</strong>.<br/>Merci de vous connecter à DAF360 pour la traiter.</p><p>Cordialement,<br/>Système DAF360</p>'
    FROM [dbo].[notification_event_types] et
    WHERE et.event_code = 'REQUEST_SUBMITTED';

-- Recipients for REQUEST_SUBMITTED: roles holding HR_UPDATE_PROFILE permission
INSERT INTO [dbo].[notification_routing_recipients] (routing_rule_id, role_id)
SELECT rr.id, r.id
FROM [dbo].[notification_routing_rules] rr
JOIN [dbo].[notification_event_types] et ON et.id = rr.event_type_id
JOIN [dbo].[Roles] r ON r.deleted = 0
WHERE et.event_code = 'REQUEST_SUBMITTED'
  AND rr.pays_id IS NULL
  AND EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp
      WHERE rp.role_id = r.id AND rp.permission = 'HR_UPDATE_PROFILE'
  )
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[notification_routing_recipients] nr
      WHERE nr.routing_rule_id = rr.id AND nr.role_id = r.id
  );

-- Routing rule: REQUEST_INFO_NEEDED → notify employee (in-app only)
IF NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_rules] rr
    JOIN  [dbo].[notification_event_types] et ON et.id = rr.event_type_id
    WHERE et.event_code = 'REQUEST_INFO_NEEDED' AND rr.pays_id IS NULL
)
    INSERT INTO [dbo].[notification_routing_rules]
        (event_type_id, pays_id, send_inapp, send_email, inapp_title_template, inapp_body_template)
    SELECT et.id, NULL, 1, 0,
        N'Informations complémentaires requises',
        N'Votre demande de {requestType} nécessite des informations complémentaires. Veuillez vous connecter à DAF360.'
    FROM [dbo].[notification_event_types] et
    WHERE et.event_code = 'REQUEST_INFO_NEEDED';

PRINT 'Step 2 complete — REQUEST_SUBMITTED + REQUEST_INFO_NEEDED event types and routing seeded.';

-- ============================================================================
-- STEP 3: Assign new permissions to existing roles
-- HR_MANAGE_EMPLOYEE_REQUESTS → roles that already hold HR_UPDATE_PROFILE
-- RH_APPROVE_SENSITIVE_REQUESTS → roles that already hold HR_ADMIN_ROLES
-- ============================================================================

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT DISTINCT rp.role_id, 'HR_MANAGE_EMPLOYEE_REQUESTS'
FROM [dbo].[RolePermissions] rp
WHERE rp.permission = 'HR_UPDATE_PROFILE'
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp2
      WHERE rp2.role_id = rp.role_id
        AND rp2.permission = 'HR_MANAGE_EMPLOYEE_REQUESTS'
  );

INSERT INTO [dbo].[RolePermissions] (role_id, permission)
SELECT DISTINCT rp.role_id, 'RH_APPROVE_SENSITIVE_REQUESTS'
FROM [dbo].[RolePermissions] rp
WHERE rp.permission = 'HR_ADMIN_ROLES'
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp2
      WHERE rp2.role_id = rp.role_id
        AND rp2.permission = 'RH_APPROVE_SENSITIVE_REQUESTS'
  );

PRINT 'Step 3 complete — HR_MANAGE_EMPLOYEE_REQUESTS and RH_APPROVE_SENSITIVE_REQUESTS assigned to roles.';
PRINT 'V39 migration complete.';
