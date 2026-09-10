-- =============================================================================
-- V97__notification_recipients_permission_unique.sql
--
-- Repare l'unicite des destinataires de notification, que V92 a rendue
-- inapplicable sans la corriger.
--
-- CE QUI S'EST PASSE
-- -----------------------------------------------------------------------------
-- V92 introduit le mode PERMISSION : un destinataire n'est plus « ce role » mais
-- « quiconque detient ce code de permission ». Ces lignes portent donc
-- role_id = NULL et permission_code renseigne.
--
-- Or `UX_NotifRecip_Rule_Role` est UNIQUE (routing_rule_id, role_id), et SQL
-- Server considere deux NULL comme EGAUX dans une contrainte d'unicite. Une
-- regle ne peut donc porter qu'UN SEUL destinataire en mode PERMISSION — alors
-- que V92 en prevoit deux pour CONTRACT_EXPIRY (RH_MANAGE_LIFECYCLE et
-- RH_APPROVE_RECRUITMENT_DEMAND).
--
-- Constate sur PROD le 2026-09-09 :
--   Msg 2627 UX_NotifRecip_Rule_Role, cle dupliquee (17, <NULL>)
--   Msg 2627 UX_EmailRecip_Rule_Role_Field, cle dupliquee (16, <NULL>, TO)
-- Les deux INSERT ont ete annules EN ENTIER (« statement has been terminated »),
-- donc AUCUN destinataire multi-permission n'existe : EMPLOYEE_STATUS_CHANGED et
-- CONTRACT_EXPIRY ne notifient personne. Rien ne plante — l'evenement part et
-- n'atteint aucun destinataire. Defaut silencieux, comme toute cette serie.
--
-- CE QUE FAIT CE SCRIPT
-- -----------------------------------------------------------------------------
-- 1. Remplace l'unicite par deux index FILTRES qui disent ce qu'on veut vraiment :
--      · un role au plus une fois par regle   (WHERE role_id IS NOT NULL)
--      · une permission au plus une fois par regle (WHERE permission_code IS NOT NULL)
--    Les deux modes coexistent alors sans se gener, et chacun reste protege du
--    doublon. Un index FILTRE et non une contrainte : une contrainte UNIQUE ne
--    peut pas porter de clause WHERE.
-- 2. Rejoue les INSERT que V92 n'a pas pu passer.
--
-- QUOTED_IDENTIFIER
-- -----------------------------------------------------------------------------
-- Les index filtres EXIGENT QUOTED_IDENTIFIER ON. sqlcmd le laisse OFF par
-- defaut (SSMS le met ON) : sans `-I`, ce script echoue en Msg 1934.
--
--   sqlcmd -S <server> -d DAF360_HR -C -I -i V97__notification_recipients_permission_unique.sql
-- Re-jouable : tout est garde.
-- Created: 2026-09-09
-- =============================================================================

USE [DAF360_HR];
GO

SET QUOTED_IDENTIFIER ON;
GO

-- ── 1. notification_routing_recipients ───────────────────────────────────────
-- Le nom peut designer une CONTRAINTE ou un INDEX selon la facon dont la table a
-- ete creee : on regarde plutot que de supposer, comme V74 a du apprendre a le
-- faire.
DECLARE @sql NVARCHAR(MAX);
DECLARE @isConstraint BIT;

IF EXISTS (SELECT 1 FROM sys.indexes
           WHERE name = 'UX_NotifRecip_Rule_Role'
             AND object_id = OBJECT_ID('dbo.notification_routing_recipients'))
BEGIN
    SELECT @isConstraint = is_unique_constraint
    FROM   sys.indexes
    WHERE  name = 'UX_NotifRecip_Rule_Role'
      AND  object_id = OBJECT_ID('dbo.notification_routing_recipients');

    SET @sql = CASE WHEN @isConstraint = 1
                    THEN N'ALTER TABLE [dbo].[notification_routing_recipients] DROP CONSTRAINT [UX_NotifRecip_Rule_Role];'
                    ELSE N'DROP INDEX [UX_NotifRecip_Rule_Role] ON [dbo].[notification_routing_recipients];' END;
    EXEC sp_executesql @sql;
    PRINT 'V97: UX_NotifRecip_Rule_Role supprimee.';
END
ELSE PRINT 'V97: UX_NotifRecip_Rule_Role deja absente.';
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'UX_NotifRecip_Rule_RoleOnly'
                 AND object_id = OBJECT_ID('dbo.notification_routing_recipients'))
BEGIN
    CREATE UNIQUE NONCLUSTERED INDEX [UX_NotifRecip_Rule_RoleOnly]
        ON [dbo].[notification_routing_recipients]([routing_rule_id], [role_id])
        WHERE [role_id] IS NOT NULL;
    PRINT 'V97: UX_NotifRecip_Rule_RoleOnly creee.';
END
ELSE PRINT 'V97: UX_NotifRecip_Rule_RoleOnly deja presente.';
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'UX_NotifRecip_Rule_Permission'
                 AND object_id = OBJECT_ID('dbo.notification_routing_recipients'))
BEGIN
    CREATE UNIQUE NONCLUSTERED INDEX [UX_NotifRecip_Rule_Permission]
        ON [dbo].[notification_routing_recipients]([routing_rule_id], [permission_code])
        WHERE [permission_code] IS NOT NULL;
    PRINT 'V97: UX_NotifRecip_Rule_Permission creee.';
END
ELSE PRINT 'V97: UX_NotifRecip_Rule_Permission deja presente.';
GO

-- ── 2. email_routing_recipients ──────────────────────────────────────────────
-- Meme defaut, avec `recipient_field` en plus : un destinataire peut etre en TO,
-- CC ou BCC, et l'unicite doit donc porter le champ.
DECLARE @sql2 NVARCHAR(MAX);
DECLARE @isConstraint2 BIT;

IF EXISTS (SELECT 1 FROM sys.indexes
           WHERE name = 'UX_EmailRecip_Rule_Role_Field'
             AND object_id = OBJECT_ID('dbo.email_routing_recipients'))
BEGIN
    SELECT @isConstraint2 = is_unique_constraint
    FROM   sys.indexes
    WHERE  name = 'UX_EmailRecip_Rule_Role_Field'
      AND  object_id = OBJECT_ID('dbo.email_routing_recipients');

    SET @sql2 = CASE WHEN @isConstraint2 = 1
                     THEN N'ALTER TABLE [dbo].[email_routing_recipients] DROP CONSTRAINT [UX_EmailRecip_Rule_Role_Field];'
                     ELSE N'DROP INDEX [UX_EmailRecip_Rule_Role_Field] ON [dbo].[email_routing_recipients];' END;
    EXEC sp_executesql @sql2;
    PRINT 'V97: UX_EmailRecip_Rule_Role_Field supprimee.';
END
ELSE PRINT 'V97: UX_EmailRecip_Rule_Role_Field deja absente.';
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'UX_EmailRecip_Rule_RoleOnly_Field'
                 AND object_id = OBJECT_ID('dbo.email_routing_recipients'))
BEGIN
    CREATE UNIQUE NONCLUSTERED INDEX [UX_EmailRecip_Rule_RoleOnly_Field]
        ON [dbo].[email_routing_recipients]([routing_rule_id], [role_id], [recipient_field])
        WHERE [role_id] IS NOT NULL;
    PRINT 'V97: UX_EmailRecip_Rule_RoleOnly_Field creee.';
END
ELSE PRINT 'V97: UX_EmailRecip_Rule_RoleOnly_Field deja presente.';
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'UX_EmailRecip_Rule_Permission_Field'
                 AND object_id = OBJECT_ID('dbo.email_routing_recipients'))
BEGIN
    CREATE UNIQUE NONCLUSTERED INDEX [UX_EmailRecip_Rule_Permission_Field]
        ON [dbo].[email_routing_recipients]([routing_rule_id], [permission_code], [recipient_field])
        WHERE [permission_code] IS NOT NULL;
    PRINT 'V97: UX_EmailRecip_Rule_Permission_Field creee.';
END
ELSE PRINT 'V97: UX_EmailRecip_Rule_Permission_Field deja presente.';
GO

-- ── 3. Rejouer ce que V92 n'a pas pu inserer ─────────────────────────────────
-- Copie conforme des deux blocs @multi de V92, gardes a l'identique. Sur une base
-- ou V92 etait passee entierement, ces INSERT ne trouvent rien a faire.
DECLARE @multi TABLE (code VARCHAR(100), permission VARCHAR(100));
INSERT INTO @multi (code, permission) VALUES
 ('EMPLOYEE_STATUS_CHANGED', 'RH_MANAGE_LIFECYCLE'),
 ('CONTRACT_EXPIRY',         'RH_MANAGE_LIFECYCLE'),
 ('CONTRACT_EXPIRY',         'RH_APPROVE_RECRUITMENT_DEMAND');

INSERT INTO [dbo].[notification_routing_recipients]
    (routing_rule_id, role_id, recipient_mode, permission_code, is_active)
SELECT ru.id, NULL, 'PERMISSION', m.permission, 1
FROM @multi m
JOIN [dbo].[notification_event_types]  et ON et.event_code    = m.code
JOIN [dbo].[notification_routing_rules] ru ON ru.event_type_id = et.id AND ru.is_active = 1
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[notification_routing_recipients] nr
    WHERE nr.routing_rule_id = ru.id AND nr.permission_code = m.permission);

PRINT 'V97: destinataires notification multi-permission rejoues.';

INSERT INTO [dbo].[email_routing_recipients]
    (routing_rule_id, role_id, recipient_field, recipient_mode, permission_code, is_active)
SELECT ru.id, NULL, 'TO', 'PERMISSION', m.permission, 1
FROM @multi m
JOIN [dbo].[notification_event_types]  et ON et.event_code    = m.code
JOIN [dbo].[notification_routing_rules] ru ON ru.event_type_id = et.id AND ru.is_active = 1
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[email_routing_recipients] er
    WHERE er.routing_rule_id = ru.id AND er.permission_code = m.permission
      AND er.recipient_field = 'TO');

PRINT 'V97: destinataires e-mail multi-permission rejoues.';
GO

-- ── Verification ─────────────────────────────────────────────────────────────
-- Attendu : CONTRACT_EXPIRY porte DEUX permissions, EMPLOYEE_STATUS_CHANGED une.
SELECT et.event_code,
       nr.recipient_mode,
       nr.permission_code,
       CASE WHEN nr.is_active = 1 THEN 'actif' ELSE 'inactif' END AS etat
FROM   [dbo].[notification_routing_recipients] nr
JOIN   [dbo].[notification_routing_rules] ru ON ru.id = nr.routing_rule_id
JOIN   [dbo].[notification_event_types]   et ON et.id = ru.event_type_id
WHERE  et.event_code IN ('CONTRACT_EXPIRY', 'EMPLOYEE_STATUS_CHANGED')
ORDER  BY et.event_code, nr.permission_code;

PRINT 'V97 complete.';
GO
