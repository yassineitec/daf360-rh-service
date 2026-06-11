-- =============================================================================
-- V12__notification_routing.sql
-- Creates 4 notification & email routing tables and seeds the initial rules.
--
-- PRE-CHECK (run before executing to confirm all 4 tables are absent):
-- SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
-- WHERE TABLE_SCHEMA='dbo'
-- AND TABLE_NAME IN (
--   'notification_event_types','notification_routing_rules',
--   'notification_routing_recipients','email_routing_recipients')
-- ORDER BY TABLE_NAME;
--
-- Safe to re-run — every block is guarded with IF NOT EXISTS / IF OBJECT_ID checks.
-- Never drops or renames existing tables or columns.
-- =============================================================================

USE [DAF360_HR];

-- ============================================================================
-- TABLE 1: notification_event_types
-- Master catalogue of all trigger events in the system.
-- ============================================================================

IF OBJECT_ID('dbo.notification_event_types','U') IS NULL
BEGIN
    CREATE TABLE [dbo].[notification_event_types] (
        [id]             BIGINT IDENTITY(1,1) NOT NULL,
        [event_code]     VARCHAR(100)         NOT NULL,
        [label_fr]       NVARCHAR(255)        NOT NULL,
        [label_en]       NVARCHAR(255)        NOT NULL,
        [description_fr] NVARCHAR(500)        NULL,
        [module]         VARCHAR(50)          NOT NULL,
        -- HR | PORTAL | TIMESHEET | FACTURATION
        [supports_email] BIT                  NOT NULL DEFAULT 0,
        -- 1 = event can also trigger an email (not just in-app)
        [is_system]      BIT                  NOT NULL DEFAULT 1,
        -- system events cannot be deleted by admin
        [is_active]      BIT                  NOT NULL DEFAULT 1,
        [created_at]     DATETIME2(6)         NOT NULL DEFAULT GETDATE(),
        CONSTRAINT [PK_notification_event_types] PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [UX_NotifEventType_Code]      UNIQUE ([event_code])
    );
    PRINT 'Created: notification_event_types';
END
ELSE PRINT 'Skipped: notification_event_types (already exists)';

-- ============================================================================
-- TABLE 2: notification_routing_rules
-- One row per (event_type x pays_id).
-- Controls send_inapp, send_email, title/body templates.
-- ============================================================================

IF OBJECT_ID('dbo.notification_routing_rules','U') IS NULL
BEGIN
    CREATE TABLE [dbo].[notification_routing_rules] (
        [id]                    BIGINT IDENTITY(1,1)  NOT NULL,
        [event_type_id]         BIGINT                NOT NULL,
        [pays_id]               BIGINT                NULL,
        -- NULL = applies to all entities; non-null overrides for a specific entity
        [send_inapp]            BIT                   NOT NULL DEFAULT 1,
        [send_email]            BIT                   NOT NULL DEFAULT 0,
        -- only effective when event_type.supports_email = 1
        [inapp_title_template]  NVARCHAR(255)         NOT NULL,
        -- placeholders: {candidateName} {firstName} {lastName}
        --               {ms365Email} {entity} {date}
        [inapp_body_template]   NVARCHAR(1000)        NOT NULL,
        [email_subject_template]NVARCHAR(255)         NULL,
        [email_body_template]   NVARCHAR(MAX)         NULL,
        -- HTML or plain text; same placeholders as inapp templates
        [is_active]             BIT                   NOT NULL DEFAULT 1,
        [updated_by]            BIGINT                NULL,
        [updated_at]            DATETIMEOFFSET(6)     NULL,
        [created_at]            DATETIMEOFFSET(6)     NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_notif_routing_rules]  PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_NotifRule_EventType]  FOREIGN KEY ([event_type_id])
            REFERENCES [dbo].[notification_event_types]([id]),
        CONSTRAINT [FK_NotifRule_Pays]       FOREIGN KEY ([pays_id])
            REFERENCES [dbo].[pays]([id]),
        CONSTRAINT [FK_NotifRule_UpdatedBy]  FOREIGN KEY ([updated_by])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [UX_NotifRule_Event_Pays] UNIQUE ([event_type_id],[pays_id])
    );
    PRINT 'Created: notification_routing_rules';
END
ELSE PRINT 'Skipped: notification_routing_rules (already exists)';

-- ============================================================================
-- TABLE 3: notification_routing_recipients
-- Which roles receive the IN-APP notification for each routing rule.
-- One row per (rule x role).
-- ============================================================================

IF OBJECT_ID('dbo.notification_routing_recipients','U') IS NULL
BEGIN
    CREATE TABLE [dbo].[notification_routing_recipients] (
        [id]              BIGINT IDENTITY(1,1) NOT NULL,
        [routing_rule_id] BIGINT               NOT NULL,
        [role_id]         BIGINT               NOT NULL,
        [is_active]       BIT                  NOT NULL DEFAULT 1,
        [created_by]      BIGINT               NULL,
        [created_at]      DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_notif_routing_recipients]  PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_NotifRecip_Rule]           FOREIGN KEY ([routing_rule_id])
            REFERENCES [dbo].[notification_routing_rules]([id]),
        CONSTRAINT [FK_NotifRecip_Role]           FOREIGN KEY ([role_id])
            REFERENCES [dbo].[Roles]([id]),
        CONSTRAINT [FK_NotifRecip_CreatedBy]      FOREIGN KEY ([created_by])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [UX_NotifRecip_Rule_Role]      UNIQUE ([routing_rule_id],[role_id])
    );
    PRINT 'Created: notification_routing_recipients';
END
ELSE PRINT 'Skipped: notification_routing_recipients (already exists)';

-- ============================================================================
-- TABLE 4: email_routing_recipients
-- Which roles receive the EMAIL (TO / CC / BCC) for each routing rule.
-- One row per (rule x role x recipient_field).
-- ============================================================================

IF OBJECT_ID('dbo.email_routing_recipients','U') IS NULL
BEGIN
    CREATE TABLE [dbo].[email_routing_recipients] (
        [id]                BIGINT IDENTITY(1,1) NOT NULL,
        [routing_rule_id]   BIGINT               NOT NULL,
        [role_id]           BIGINT               NOT NULL,
        [recipient_field]   VARCHAR(10)          NOT NULL DEFAULT 'TO',
        -- TO | CC | BCC
        [is_active]         BIT                  NOT NULL DEFAULT 1,
        [created_by]        BIGINT               NULL,
        [created_at]        DATETIMEOFFSET(6)    NOT NULL DEFAULT SYSDATETIMEOFFSET(),
        CONSTRAINT [PK_email_routing_recipients]      PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [FK_EmailRecip_Rule]               FOREIGN KEY ([routing_rule_id])
            REFERENCES [dbo].[notification_routing_rules]([id]),
        CONSTRAINT [FK_EmailRecip_Role]               FOREIGN KEY ([role_id])
            REFERENCES [dbo].[Roles]([id]),
        CONSTRAINT [FK_EmailRecip_CreatedBy]          FOREIGN KEY ([created_by])
            REFERENCES [dbo].[Users]([id]),
        CONSTRAINT [CK_EmailRecip_Field]              CHECK ([recipient_field] IN ('TO','CC','BCC')),
        CONSTRAINT [UX_EmailRecip_Rule_Role_Field]    UNIQUE ([routing_rule_id],[role_id],[recipient_field])
    );
    PRINT 'Created: email_routing_recipients';
END
ELSE PRINT 'Skipped: email_routing_recipients (already exists)';

-- ============================================================================
-- SEED — EVENT TYPES
-- All 8 core HR events. Idempotent — skips codes that already exist.
-- ============================================================================

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code='CANDIDATE_ACCEPTED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code,label_fr,label_en,description_fr,module,supports_email,is_system)
    VALUES
        ('CANDIDATE_ACCEPTED','Candidat accepte','Candidate accepted',
         'Declenche quand un candidat est accepte par le HR Manager','HR',1,1);

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code='IT_EMAIL_SUBMITTED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code,label_fr,label_en,description_fr,module,supports_email,is_system)
    VALUES
        ('IT_EMAIL_SUBMITTED','Compte MS365 cree','MS365 account created',
         'Declenche quand le IT Manager soumet l email MS365 du candidat','HR',1,1);

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code='ONBOARDING_COMPLETED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code,label_fr,label_en,description_fr,module,supports_email,is_system)
    VALUES
        ('ONBOARDING_COMPLETED','Onboarding termine','Onboarding completed',
         'Declenche quand le dossier employe est complete par le RH','HR',1,1);

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code='LEAVE_SUBMITTED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code,label_fr,label_en,description_fr,module,supports_email,is_system)
    VALUES
        ('LEAVE_SUBMITTED','Demande de conge soumise','Leave request submitted',
         'Declenche quand un employe soumet une demande de conge','HR',0,1);

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code='LEAVE_APPROVED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code,label_fr,label_en,description_fr,module,supports_email,is_system)
    VALUES
        ('LEAVE_APPROVED','Demande de conge approuvee','Leave request approved',
         'Declenche quand une demande de conge est approuvee','HR',0,1);

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code='LEAVE_REJECTED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code,label_fr,label_en,description_fr,module,supports_email,is_system)
    VALUES
        ('LEAVE_REJECTED','Demande de conge rejetee','Leave request rejected',
         'Declenche quand une demande de conge est rejetee','HR',0,1);

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code='REQUEST_APPROVED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code,label_fr,label_en,description_fr,module,supports_email,is_system)
    VALUES
        ('REQUEST_APPROVED','Demande RH approuvee','HR request approved',
         'Declenche quand une demande employe est approuvee','HR',0,1);

IF NOT EXISTS (SELECT 1 FROM [dbo].[notification_event_types] WHERE event_code='REQUEST_REJECTED')
    INSERT INTO [dbo].[notification_event_types]
        (event_code,label_fr,label_en,description_fr,module,supports_email,is_system)
    VALUES
        ('REQUEST_REJECTED','Demande RH rejetee','HR request rejected',
         'Declenche quand une demande employe est rejetee','HR',0,1);

-- ============================================================================
-- SEED — ROUTING RULES
-- One global rule (pays_id=NULL) per event.
-- ============================================================================

-- Rule: CANDIDATE_ACCEPTED (in-app + email to IT team)
IF NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_rules] rr
    JOIN [dbo].[notification_event_types] et ON et.id=rr.event_type_id
    WHERE et.event_code='CANDIDATE_ACCEPTED' AND rr.pays_id IS NULL
)
    INSERT INTO [dbo].[notification_routing_rules]
        (event_type_id,pays_id,send_inapp,send_email,
         inapp_title_template,inapp_body_template,
         email_subject_template,email_body_template)
    SELECT et.id, NULL, 1, 1,
        N'Nouveau candidat accepte - Action requise',
        N'Le candidat {candidateName} a ete accepte. Veuillez creer son compte Microsoft 365 puis renseigner son adresse email dans le systeme DAF360.',
        N'[DAF360] Nouveau candidat accepte - {candidateName}',
        N'<p>Bonjour,</p><p>Le candidat <strong>{candidateName}</strong> a ete accepte et attend la creation de son compte Microsoft 365.</p><p>Merci de vous connecter a DAF360 pour completer le provisioning IT.</p><p>Cordialement,<br/>L equipe RH ARX</p>'
    FROM [dbo].[notification_event_types] et
    WHERE et.event_code='CANDIDATE_ACCEPTED';

-- Rule: IT_EMAIL_SUBMITTED (in-app + email to HR team)
IF NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_rules] rr
    JOIN [dbo].[notification_event_types] et ON et.id=rr.event_type_id
    WHERE et.event_code='IT_EMAIL_SUBMITTED' AND rr.pays_id IS NULL
)
    INSERT INTO [dbo].[notification_routing_rules]
        (event_type_id,pays_id,send_inapp,send_email,
         inapp_title_template,inapp_body_template,
         email_subject_template,email_body_template)
    SELECT et.id, NULL, 1, 1,
        N'Nouveau employe pret - Completer le dossier',
        N'Le compte Microsoft 365 de {candidateName} ({ms365Email}) a ete cree. Veuillez completer son dossier RH.',
        N'[DAF360] Dossier a completer - {candidateName}',
        N'<p>Bonjour,</p><p>Le departement IT a cree le compte Microsoft 365 de <strong>{candidateName}</strong> ({ms365Email}).</p><p>Merci de vous connecter a DAF360 pour completer le dossier RH du nouvel employe.</p><p>Cordialement,<br/>Systeme DAF360</p>'
    FROM [dbo].[notification_event_types] et
    WHERE et.event_code='IT_EMAIL_SUBMITTED';

-- Rule: ONBOARDING_COMPLETED (in-app + email to new employee)
IF NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_rules] rr
    JOIN [dbo].[notification_event_types] et ON et.id=rr.event_type_id
    WHERE et.event_code='ONBOARDING_COMPLETED' AND rr.pays_id IS NULL
)
    INSERT INTO [dbo].[notification_routing_rules]
        (event_type_id,pays_id,send_inapp,send_email,
         inapp_title_template,inapp_body_template,
         email_subject_template,email_body_template)
    SELECT et.id, NULL, 1, 1,
        N'Bienvenue chez ARX !',
        N'Votre dossier est complet. Connectez-vous au portail DAF360 avec votre compte Microsoft 365.',
        N'Bienvenue chez ARX - Activez votre compte DAF360',
        N'<p>Bonjour {firstName},</p><p>Nous sommes ravis de vous accueillir au sein d ARX. Votre dossier a ete complete avec succes.</p><p>Connectez-vous avec votre adresse Microsoft 365 : {ms365Email}</p><p>Bienvenue dans l equipe !<br/>L equipe RH ARX</p>'
    FROM [dbo].[notification_event_types] et
    WHERE et.event_code='ONBOARDING_COMPLETED';

-- Rules for leave & request events (in-app only, no email)
DECLARE @leaveSubmittedId BIGINT=(SELECT id FROM notification_event_types WHERE event_code='LEAVE_SUBMITTED');
DECLARE @leaveApprovedId  BIGINT=(SELECT id FROM notification_event_types WHERE event_code='LEAVE_APPROVED');
DECLARE @leaveRejectedId  BIGINT=(SELECT id FROM notification_event_types WHERE event_code='LEAVE_REJECTED');
DECLARE @reqApprovedId    BIGINT=(SELECT id FROM notification_event_types WHERE event_code='REQUEST_APPROVED');
DECLARE @reqRejectedId    BIGINT=(SELECT id FROM notification_event_types WHERE event_code='REQUEST_REJECTED');

IF NOT EXISTS (SELECT 1 FROM notification_routing_rules WHERE event_type_id=@leaveSubmittedId AND pays_id IS NULL)
    INSERT INTO notification_routing_rules (event_type_id,pays_id,send_inapp,send_email,inapp_title_template,inapp_body_template)
    VALUES(@leaveSubmittedId,NULL,1,0,N'Nouvelle demande de conge',N'{candidateName} a soumis une demande de conge. Veuillez la traiter dans DAF360.');

IF NOT EXISTS (SELECT 1 FROM notification_routing_rules WHERE event_type_id=@leaveApprovedId AND pays_id IS NULL)
    INSERT INTO notification_routing_rules (event_type_id,pays_id,send_inapp,send_email,inapp_title_template,inapp_body_template)
    VALUES(@leaveApprovedId,NULL,1,0,N'Demande de conge approuvee',N'Votre demande de conge a ete approuvee.');

IF NOT EXISTS (SELECT 1 FROM notification_routing_rules WHERE event_type_id=@leaveRejectedId AND pays_id IS NULL)
    INSERT INTO notification_routing_rules (event_type_id,pays_id,send_inapp,send_email,inapp_title_template,inapp_body_template)
    VALUES(@leaveRejectedId,NULL,1,0,N'Demande de conge rejetee',N'Votre demande de conge a ete rejetee.');

IF NOT EXISTS (SELECT 1 FROM notification_routing_rules WHERE event_type_id=@reqApprovedId AND pays_id IS NULL)
    INSERT INTO notification_routing_rules (event_type_id,pays_id,send_inapp,send_email,inapp_title_template,inapp_body_template)
    VALUES(@reqApprovedId,NULL,1,0,N'Demande RH approuvee',N'Votre demande a ete approuvee.');

IF NOT EXISTS (SELECT 1 FROM notification_routing_rules WHERE event_type_id=@reqRejectedId AND pays_id IS NULL)
    INSERT INTO notification_routing_rules (event_type_id,pays_id,send_inapp,send_email,inapp_title_template,inapp_body_template)
    VALUES(@reqRejectedId,NULL,1,0,N'Demande RH rejetee',N'Votre demande a ete rejetee.');

-- ============================================================================
-- SEED — IN-APP RECIPIENTS
-- Roles resolved by frenchName — never by hardcoded ID.
-- From V4: 'Responsable IT', 'Responsable IT (Egypt)', frenchName like 'Ressources Humaines (RH)'
-- ============================================================================

-- CANDIDATE_ACCEPTED → all roles holding IT_PROVISIONING permission
INSERT INTO [dbo].[notification_routing_recipients] (routing_rule_id,role_id)
SELECT rr.id, r.id
FROM [dbo].[notification_routing_rules] rr
JOIN [dbo].[notification_event_types] et ON et.id=rr.event_type_id
JOIN [dbo].[Roles] r ON r.deleted=0
WHERE et.event_code='CANDIDATE_ACCEPTED'
  AND rr.pays_id IS NULL
  AND EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp
      WHERE rp.role_id=r.id AND rp.permission='IT_PROVISIONING'
  )
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[notification_routing_recipients] nr
      WHERE nr.routing_rule_id=rr.id AND nr.role_id=r.id
  );

-- IT_EMAIL_SUBMITTED → all roles holding HR_ONBOARDING permission
INSERT INTO [dbo].[notification_routing_recipients] (routing_rule_id,role_id)
SELECT rr.id, r.id
FROM [dbo].[notification_routing_rules] rr
JOIN [dbo].[notification_event_types] et ON et.id=rr.event_type_id
JOIN [dbo].[Roles] r ON r.deleted=0
WHERE et.event_code='IT_EMAIL_SUBMITTED'
  AND rr.pays_id IS NULL
  AND EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp
      WHERE rp.role_id=r.id AND rp.permission='HR_ONBOARDING'
  )
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[notification_routing_recipients] nr
      WHERE nr.routing_rule_id=rr.id AND nr.role_id=r.id
  );

-- LEAVE_SUBMITTED → all roles holding RESPONSE_LEAVE permission
INSERT INTO [dbo].[notification_routing_recipients] (routing_rule_id,role_id)
SELECT rr.id, r.id
FROM [dbo].[notification_routing_rules] rr
JOIN [dbo].[notification_event_types] et ON et.id=rr.event_type_id
JOIN [dbo].[Roles] r ON r.deleted=0
WHERE et.event_code='LEAVE_SUBMITTED'
  AND rr.pays_id IS NULL
  AND EXISTS (
      SELECT 1 FROM [dbo].[RolePermissions] rp
      WHERE rp.role_id=r.id AND rp.permission='RESPONSE_LEAVE'
  )
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[notification_routing_recipients] nr
      WHERE nr.routing_rule_id=rr.id AND nr.role_id=r.id
  );

-- ============================================================================
-- SEED — EMAIL RECIPIENTS
-- Roles resolved by frenchName (exact names from V4 migration).
-- ============================================================================

-- CANDIDATE_ACCEPTED email TO → Responsable IT + Responsable IT (Egypt)
INSERT INTO [dbo].[email_routing_recipients] (routing_rule_id,role_id,recipient_field)
SELECT rr.id, r.id, 'TO'
FROM [dbo].[notification_routing_rules] rr
JOIN [dbo].[notification_event_types] et ON et.id=rr.event_type_id
JOIN [dbo].[Roles] r ON r.frenchName IN ('Responsable IT','Responsable IT (Egypt)') AND r.deleted=0
WHERE et.event_code='CANDIDATE_ACCEPTED'
  AND rr.pays_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[email_routing_recipients] er
      WHERE er.routing_rule_id=rr.id AND er.role_id=r.id AND er.recipient_field='TO'
  );

-- IT_EMAIL_SUBMITTED email TO → Ressources Humaines (RH)
INSERT INTO [dbo].[email_routing_recipients] (routing_rule_id,role_id,recipient_field)
SELECT rr.id, r.id, 'TO'
FROM [dbo].[notification_routing_rules] rr
JOIN [dbo].[notification_event_types] et ON et.id=rr.event_type_id
JOIN [dbo].[Roles] r ON r.frenchName='Ressources Humaines (RH)' AND r.deleted=0
WHERE et.event_code='IT_EMAIL_SUBMITTED'
  AND rr.pays_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[email_routing_recipients] er
      WHERE er.routing_rule_id=rr.id AND er.role_id=r.id AND er.recipient_field='TO'
  );

-- IT_EMAIL_SUBMITTED email CC → Directeur des Ressources Humaines (DRH)
INSERT INTO [dbo].[email_routing_recipients] (routing_rule_id,role_id,recipient_field)
SELECT rr.id, r.id, 'CC'
FROM [dbo].[notification_routing_rules] rr
JOIN [dbo].[notification_event_types] et ON et.id=rr.event_type_id
JOIN [dbo].[Roles] r ON r.frenchName='Directeur des Ressources Humaines (DRH)' AND r.deleted=0
WHERE et.event_code='IT_EMAIL_SUBMITTED'
  AND rr.pays_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM [dbo].[email_routing_recipients] er
      WHERE er.routing_rule_id=rr.id AND er.role_id=r.id AND er.recipient_field='CC'
  );

PRINT 'V12 complete — 4 tables created, event types and routing rules seeded.';